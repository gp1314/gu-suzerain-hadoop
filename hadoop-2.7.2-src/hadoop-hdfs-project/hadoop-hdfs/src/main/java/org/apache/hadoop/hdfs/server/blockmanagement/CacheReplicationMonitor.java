/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.blockmanagement;

import static org.apache.hadoop.util.ExitUtil.terminate;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.fs.UnresolvedLinkException;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.CacheDirective;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeDescriptor.CachedBlocksList.Type;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.BlockUCState;
import org.apache.hadoop.hdfs.server.namenode.CacheManager;
import org.apache.hadoop.hdfs.server.namenode.CachePool;
import org.apache.hadoop.hdfs.server.namenode.CachedBlock;
import org.apache.hadoop.hdfs.server.namenode.FSDirectory;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.hdfs.server.namenode.INode;
import org.apache.hadoop.hdfs.server.namenode.INodeDirectory;
import org.apache.hadoop.hdfs.server.namenode.INodeFile;
import org.apache.hadoop.hdfs.server.namenode.snapshot.Snapshot;
import org.apache.hadoop.hdfs.util.ReadOnlyList;
import org.apache.hadoop.util.GSet;
import org.apache.hadoop.util.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
;

/**
 * Scans the namesystem, scheduling blocks to be cached as appropriate.
 *
 * The CacheReplicationMonitor does a full scan when the NameNode first
 * starts up, and at configurable intervals afterwards.
 */
@InterfaceAudience.LimitedPrivate({"HDFS"})
public class CacheReplicationMonitor extends Thread implements Closeable {

  private static final Logger LOG =
      LoggerFactory.getLogger(CacheReplicationMonitor.class);

  private final FSNamesystem namesystem;

  private final BlockManager blockManager;

  private final CacheManager cacheManager;

  private final GSet<CachedBlock, CachedBlock> cachedBlocks;

  /**
   * Pseudorandom number source
   */
  private static final Random random = new Random();

  /**
   * The interval at which we scan the namesystem for caching changes.
   */
  private final long intervalMs;

  /**
   * The CacheReplicationMonitor (CRM) lock. Used to synchronize starting and
   * waiting for rescan operations.
   */
  private final ReentrantLock lock;

  /**
   * Notifies the scan thread that an immediate rescan is needed.
   */
  private final Condition doRescan;

  /**
   * Notifies waiting threads that a rescan has finished.
   */
  private final Condition scanFinished;

  /**
   * The number of rescans completed. Used to wait for scans to finish.
   * Protected by the CacheReplicationMonitor lock.
   */
  private long completedScanCount = 0;

  /**
   * The scan we're currently performing, or -1 if no scan is in progress.
   * Protected by the CacheReplicationMonitor lock.
   */
  private long curScanCount = -1;

  /**
   * The number of rescans we need to complete.  Protected by the CRM lock.
   */
  private long neededScanCount = 0;

  /**
   * True if this monitor should terminate. Protected by the CRM lock.
   */
  private boolean shutdown = false;

  /**
   * Mark status of the current scan.
   */
  private boolean mark = false;

  /**
   * Cache directives found in the previous scan.
   */
  private int scannedDirectives;

  /**
   * Blocks found in the previous scan.
   */
  private long scannedBlocks;

  public CacheReplicationMonitor(FSNamesystem namesystem,
      CacheManager cacheManager, long intervalMs, ReentrantLock lock) {
    this.namesystem = namesystem;
    this.blockManager = namesystem.getBlockManager();
    this.cacheManager = cacheManager;
    this.cachedBlocks = cacheManager.getCachedBlocks();
    this.intervalMs = intervalMs;
    this.lock = lock;
    this.doRescan = this.lock.newCondition();
    this.scanFinished = this.lock.newCondition();
  }

  @Override
  public void run() {
    long startTimeMs = 0;
    Thread.currentThread().setName("CacheReplicationMonitor(" +
        System.identityHashCode(this) + ")");
    LOG.info("Starting CacheReplicationMonitor with interval " +
             intervalMs + " milliseconds");
    try {
      long curTimeMs = Time.monotonicNow();
      while (true) {
        lock.lock();
        try {
          while (true) {
            if (shutdown) {
              LOG.debug("Shutting down CacheReplicationMonitor");
              return;
            }//已经完成的扫描次数小于需要扫描的次数，需要执行扫描
            if (completedScanCount < neededScanCount) {
              LOG.debug("Rescanning because of pending operations");
              break;
            }
            long delta = (startTimeMs + intervalMs) - curTimeMs;
            if (delta <= 0) {//超过扫描时间间隔，需要执行扫描
              LOG.debug("Rescanning after {} milliseconds", (curTimeMs - startTimeMs));
              break;
            }
            doRescan.await(delta, TimeUnit.MILLISECONDS);
            curTimeMs = Time.monotonicNow();
          }
        } finally {
          lock.unlock();
        }
        startTimeMs = curTimeMs;
        mark = !mark;
        rescan();
        curTimeMs = Time.monotonicNow();
        // Update synchronization-related variables.
        lock.lock();
        try {
          completedScanCount = curScanCount;
          curScanCount = -1;
          scanFinished.signalAll();
        } finally {
          lock.unlock();
        }
        LOG.debug("Scanned {} directive(s) and {} block(s) in {} millisecond(s).",
            scannedDirectives, scannedBlocks, (curTimeMs - startTimeMs));
      }
    } catch (InterruptedException e) {
      LOG.info("Shutting down CacheReplicationMonitor.");
      return;
    } catch (Throwable t) {
      LOG.error("Thread exiting", t);
      terminate(1, t);
    }
  }

  /**
   * Waits for a rescan to complete. This doesn't guarantee consistency with
   * pending operations, only relative recency, since it will not force a new
   * rescan if a rescan is already underway.
   * <p>
   * Note that this call will release the FSN lock, so operations before and
   * after are not atomic.
   */
  public void waitForRescanIfNeeded() {
    Preconditions.checkArgument(!namesystem.hasWriteLock(),
        "Must not hold the FSN write lock when waiting for a rescan.");
    Preconditions.checkArgument(lock.isHeldByCurrentThread(),
        "Must hold the CRM lock when waiting for a rescan.");
    if (neededScanCount <= completedScanCount) {
      return;
    }
    // If no scan is already ongoing, mark the CRM as dirty and kick
    //如果当前没有扫描任务，则唤醒检查线程进行扫描
    if (curScanCount < 0) {
      doRescan.signal();
    }
    // Wait until the scan finishes and the count advances
    //等待直到扫描完成
    while ((!shutdown) && (completedScanCount < neededScanCount)) {
      try {
        scanFinished.await();
      } catch (InterruptedException e) {
        LOG.warn("Interrupted while waiting for CacheReplicationMonitor"
            + " rescan", e);
        break;
      }
    }
  }

  /**
   * Indicates to the CacheReplicationMonitor that there have been CacheManager
   * changes that require a rescan.
   */
  public void setNeedsRescan() {
    Preconditions.checkArgument(lock.isHeldByCurrentThread(),
        "Must hold the CRM lock when setting the needsRescan bit.");
    if (curScanCount >= 0) {
      // If there is a scan in progress, we need to wait for the scan after
      // that.
      neededScanCount = curScanCount + 1;
    } else {
      // If there is no scan in progress, we need to wait for the next scan.
      neededScanCount = completedScanCount + 1;
    }
  }

  /**
   * Shut down the monitor thread.
   */
  @Override
  public void close() throws IOException {
    Preconditions.checkArgument(namesystem.hasWriteLock());
    lock.lock();
    try {
      if (shutdown) return;
      // Since we hold both the FSN write lock and the CRM lock here,
      // we know that the CRM thread cannot be currently modifying
      // the cache manager state while we're closing it.
      // Since the CRM thread checks the value of 'shutdown' after waiting
      // for a lock, we know that the thread will not modify the cache
      // manager state after this point.
      shutdown = true;
      doRescan.signalAll();
      scanFinished.signalAll();
    } finally {
      lock.unlock();
    }
  }

  private void rescan() throws InterruptedException {
    scannedDirectives = 0;
    scannedBlocks = 0;
    try {
      namesystem.writeLock();
      try {
        lock.lock();
        if (shutdown) {
          throw new InterruptedException("CacheReplicationMonitor was " +
              "shut down.");
        }
        curScanCount = completedScanCount + 1;
      } finally {
        lock.unlock();
      }

      resetStatistics();
      rescanCacheDirectives();
      rescanCachedBlockMap();
      blockManager.getDatanodeManager().resetLastCachingDirectiveSentTime();
    } finally {
      namesystem.writeUnlock();
    }
  }

  private void resetStatistics() {
    for (CachePool pool: cacheManager.getCachePools()) {
      pool.resetStatistics();
    }
    for (CacheDirective directive: cacheManager.getCacheDirectives()) {
      directive.resetStatistics();
    }
  }

  /**
   * Scan all CacheDirectives.  Use the information to figure out
   * what cache replication factor each block should have.
   */
  private void rescanCacheDirectives() {
    FSDirectory fsDir = namesystem.getFSDirectory();
    final long now = new Date().getTime();
    for (CacheDirective directive : cacheManager.getCacheDirectives()) {
      scannedDirectives++;
      // Skip processing this entry if it has expired
      //如果缓存命令过期，直接跳过
      if (directive.getExpiryTime() > 0 && directive.getExpiryTime() <= now) {
        LOG.debug("Directive {}: the directive expired at {} (now = {})",
             directive.getId(), directive.getExpiryTime(), now);
        continue;
      }
      String path = directive.getPath();
      INode node;
      try {
        node = fsDir.getINode(path);
      } catch (UnresolvedLinkException e) {
        // We don't cache through symlinks
        LOG.debug("Directive {}: got UnresolvedLinkException while resolving "
                + "path {}", directive.getId(), path
        );
        continue;
      }
      if (node == null)  {
        LOG.debug("Directive {}: No inode found at {}", directive.getId(),
            path);
      } else if (node.isDirectory()) {
        INodeDirectory dir = node.asDirectory();
        ReadOnlyList<INode> children = dir
            .getChildrenList(Snapshot.CURRENT_STATE_ID);
        for (INode child : children) {
          if (child.isFile()) {
            rescanFile(directive, child.asFile());
          }
        }
      } else if (node.isFile()) {
        rescanFile(directive, node.asFile());
      } else {
        LOG.debug("Directive {}: ignoring non-directive, non-file inode {} ",
            directive.getId(), node);
      }
    }
  }
  
  /**
   * Apply a CacheDirective to a file.
   * 
   * @param directive The CacheDirective to apply.
   * @param file The file.
   */
  private void rescanFile(CacheDirective directive, INodeFile file) {
    BlockInfoContiguous[] blockInfos = file.getBlocks();

    // Increment the "needed" statistics
    directive.addFilesNeeded(1);
    // We don't cache UC blocks, don't add them to the total here
    //跳过正在构建状态的数据块
    long neededTotal = file.computeFileSizeNotIncludingLastUcBlock() *
        directive.getReplication();
    directive.addBytesNeeded(neededTotal);

    // The pool's bytesNeeded is incremented as we scan. If the demand
    // thus far plus the demand of this file would exceed the pool's limit,
    // do not cache this file.
    //如果缓存池需要缓存的数据量达到上限，则不能再缓存数据
    CachePool pool = directive.getPool();
    if (pool.getBytesNeeded() > pool.getLimit()) {
      LOG.debug("Directive {}: not scanning file {} because " +
          "bytesNeeded for pool {} is {}, but the pool's limit is {}",
          directive.getId(),
          file.getFullPathName(),
          pool.getPoolName(),
          pool.getBytesNeeded(),
          pool.getLimit());
      return;
    }

    long cachedTotal = 0;
    for (BlockInfoContiguous blockInfo : blockInfos) {
      if (!blockInfo.getBlockUCState().equals(BlockUCState.COMPLETE)) {
        // We don't try to cache blocks that are under construction.
    	  //跳过正在构建状态的数据块
        LOG.trace("Directive {}: can't cache block {} because it is in state "
                + "{}, not COMPLETE.", directive.getId(), blockInfo,
            blockInfo.getBlockUCState()
        );
        continue;
      }
      Block block = new Block(blockInfo.getBlockId());
      CachedBlock ncblock = new CachedBlock(block.getBlockId(),
          directive.getReplication(), mark);
      CachedBlock ocblock = cachedBlocks.get(ncblock);
      if (ocblock == null) {
        cachedBlocks.put(ncblock);
        ocblock = ncblock;
      } else {
        // Update bytesUsed using the current replication levels.
        // Assumptions: we assume that all the blocks are the same length
        // on each datanode.  We can assume this because we're only caching
        // blocks in state COMPLETE.
        // Note that if two directives are caching the same block(s), they will
        // both get them added to their bytesCached.
        List<DatanodeDescriptor> cachedOn =
            ocblock.getDatanodes(Type.CACHED);
        long cachedByBlock = Math.min(cachedOn.size(),
            directive.getReplication()) * blockInfo.getNumBytes();
        cachedTotal += cachedByBlock;

        if ((mark != ocblock.getMark()) ||
            (ocblock.getReplication() < directive.getReplication())) {
          //
          // Overwrite the block's replication and mark in two cases:
          //
          // 1. If the mark on the CachedBlock is different from the mark for
          // this scan, that means the block hasn't been updated during this
          // scan, and we should overwrite whatever is there, since it is no
          // longer valid.
          // 如果mark不同，代表这个块没有被更新，需要覆盖这个缓存块
          // 2. If the replication in the CachedBlock is less than what the
          // directive asks for, we want to increase the block's replication
          // field to what the directive asks for.
          // 如果该副本数比缓存命令要求的小，需要增加这个数据块的缓存副本
          ocblock.setReplicationAndMark(directive.getReplication(), mark);
        }
      }
      LOG.trace("Directive {}: setting replication for block {} to {}",
          directive.getId(), blockInfo, ocblock.getReplication());
    }
    // Increment the "cached" statistics
    directive.addBytesCached(cachedTotal);
    if (cachedTotal == neededTotal) {
      directive.addFilesCached(1);
    }
    LOG.debug("Directive {}: caching {}: {}/{} bytes", directive.getId(),
        file.getFullPathName(), cachedTotal, neededTotal);
  }

  private String findReasonForNotCaching(CachedBlock cblock, 
          BlockInfoContiguous blockInfo) {
    if (blockInfo == null) {
      // Somehow, a cache report with the block arrived, but the block
      // reports from the DataNode haven't (yet?) described such a block.
      // Alternately, the NameNode might have invalidated the block, but the
      // DataNode hasn't caught up.  In any case, we want to tell the DN
      // to uncache this.
      //收到了缓存块的汇报信息，但是datanode的汇报信息没收到
      //或者，是namenode将该块设置为了损坏状态，但是datanode还没有删除
      return "not tracked by the BlockManager";
    } else if (!blockInfo.isComplete()) {
      // When a cached block changes state from complete to some other state
      // on the DataNode (perhaps because of append), it will begin the
      // uncaching process.  However, the uncaching process is not
      // instantaneous, especially if clients have pinned the block.  So
      // there may be a period of time when incomplete blocks remain cached
      // on the DataNodes.
      //可能追加操作等原因导致缓存块不在completed状态
      //因为取消缓存的操作也不是瞬时完成的，特别是客户端pinned数据块
      //因此可能会有缓存数据块不是completed状态的情况
      return "not complete";
    } else if (cblock.getReplication() == 0) {
      // Since 0 is not a valid value for a cache directive's replication
      // field, seeing a replication of 0 on a CacheBlock means that it
      // has never been reached by any sweep.
      return "not needed by any directives";
    } else if (cblock.getMark() != mark) { 
      // Although the block was needed in the past, we didn't reach it during
      // the current sweep.  Therefore, it doesn't need to be cached any more.
      // Need to set the replication to 0 so it doesn't flip back to cached
      // when the mark flips on the next scan
      //虽然这个块在过去是需要的，但是我们在当前清理过程中没有到达它。因此，不需要再缓存它了。
      //需要将副本设置为0，以便在下次扫描时标记不会再缓存
      cblock.setReplicationAndMark((short)0, mark);
      return "no longer needed by any directives";
    }
    return null;
  }

  /**
   * Scan through the cached block map.
   * Any blocks which are under-replicated should be assigned new Datanodes.
   * Blocks that are over-replicated should be removed from Datanodes.
   */
  private void rescanCachedBlockMap() {
    for (Iterator<CachedBlock> cbIter = cachedBlocks.iterator();
        cbIter.hasNext(); ) {
      scannedBlocks++;
      CachedBlock cblock = cbIter.next();
      List<DatanodeDescriptor> pendingCached =
          cblock.getDatanodes(Type.PENDING_CACHED);
      List<DatanodeDescriptor> cached =
          cblock.getDatanodes(Type.CACHED);
      List<DatanodeDescriptor> pendingUncached =
          cblock.getDatanodes(Type.PENDING_UNCACHED);
      // Remove nodes from PENDING_UNCACHED if they were actually uncached.
      for (Iterator<DatanodeDescriptor> iter = pendingUncached.iterator();
          iter.hasNext(); ) {
        DatanodeDescriptor datanode = iter.next();
        //如果该缓存块不在datanode的缓存队列中，将其从等待取消缓存队列中移除
        if (!cblock.isInList(datanode.getCached())) {
          LOG.trace("Block {}: removing from PENDING_UNCACHED for node {} "
              + "because the DataNode uncached it.", cblock.getBlockId(),
              datanode.getDatanodeUuid());
          datanode.getPendingUncached().remove(cblock);
          iter.remove();
        }
      }
      BlockInfoContiguous blockInfo = blockManager.
            getStoredBlock(new Block(cblock.getBlockId()));
      String reason = findReasonForNotCaching(cblock, blockInfo);
      int neededCached = 0;
      if (reason != null) {
        LOG.trace("Block {}: can't cache block because it is {}",
            cblock.getBlockId(), reason);
      } else {
        neededCached = cblock.getReplication();
      }
      int numCached = cached.size();
      if (numCached >= neededCached) {
        // If we have enough replicas, drop all pending cached.
    	// 如果有足够的副本已经缓存，删除所有等待缓存的副本
        for (Iterator<DatanodeDescriptor> iter = pendingCached.iterator();
            iter.hasNext(); ) {
          DatanodeDescriptor datanode = iter.next();
          datanode.getPendingCached().remove(cblock);
          iter.remove();
          LOG.trace("Block {}: removing from PENDING_CACHED for node {}"
                  + "because we already have {} cached replicas and we only" +
                  " need {}",
              cblock.getBlockId(), datanode.getDatanodeUuid(), numCached,
              neededCached
          );
        }
      }
      if (numCached < neededCached) {
        // If we don't have enough replicas, drop all pending uncached.
    	// 如果我们没有足够的副本，放弃所有取消缓存操作。
        for (Iterator<DatanodeDescriptor> iter = pendingUncached.iterator();
            iter.hasNext(); ) {
          DatanodeDescriptor datanode = iter.next();
          datanode.getPendingUncached().remove(cblock);
          iter.remove();
          LOG.trace("Block {}: removing from PENDING_UNCACHED for node {} "
                  + "because we only have {} cached replicas and we need " +
                  "{}", cblock.getBlockId(), datanode.getDatanodeUuid(),
              numCached, neededCached
          );
        }
      }
      int neededUncached = numCached -
          (pendingUncached.size() + neededCached);
      if (neededUncached > 0) {
    	  //添加待取消缓存节点
        addNewPendingUncached(neededUncached, cblock, cached,
            pendingUncached);
      } else {
        int additionalCachedNeeded = neededCached -
            (numCached + pendingCached.size());
        if (additionalCachedNeeded > 0) {
        	//添加待缓存节点
          addNewPendingCached(additionalCachedNeeded, cblock, cached,
              pendingCached);
        }
      }
      if ((neededCached == 0) &&
          pendingUncached.isEmpty() &&
          pendingCached.isEmpty()) {
        // we have nothing more to do with this block.
        LOG.trace("Block {}: removing from cachedBlocks, since neededCached "
                + "== 0, and pendingUncached and pendingCached are empty.",
            cblock.getBlockId()
        );
        cbIter.remove();
      }
    }
  }

  /**
   * Add new entries to the PendingUncached list.
   *
   * @param neededUncached   The number of replicas that need to be uncached.
   * @param cachedBlock      The block which needs to be uncached.
   * @param cached           A list of DataNodes currently caching the block.
   * @param pendingUncached  A list of DataNodes that will soon uncache the
   *                         block.
   */
  private void addNewPendingUncached(int neededUncached,
      CachedBlock cachedBlock, List<DatanodeDescriptor> cached,
      List<DatanodeDescriptor> pendingUncached) {
    // Figure out which replicas can be uncached.
	  //找出那些副本可以被取消缓存
    LinkedList<DatanodeDescriptor> possibilities =
        new LinkedList<DatanodeDescriptor>();
    for (DatanodeDescriptor datanode : cached) {
      if (!pendingUncached.contains(datanode)) {
        possibilities.add(datanode);
      }
    }
    while (neededUncached > 0) {
      if (possibilities.isEmpty()) {
        LOG.warn("Logic error: we're trying to uncache more replicas than " +
            "actually exist for " + cachedBlock);
        return;
      }
      //随机从可能的队列中选一个节点加入取消缓存队列
      DatanodeDescriptor datanode =
        possibilities.remove(random.nextInt(possibilities.size()));
      pendingUncached.add(datanode);
      boolean added = datanode.getPendingUncached().add(cachedBlock);
      assert added;
      neededUncached--;
    }
  }
  
  /**
   * Add new entries to the PendingCached list.
   *
   * @param neededCached     The number of replicas that need to be cached.
   * @param cachedBlock      The block which needs to be cached.
   * @param cached           A list of DataNodes currently caching the block.
   * @param pendingCached    A list of DataNodes that will soon cache the
   *                         block.
   */
  private void addNewPendingCached(final int neededCached,
      CachedBlock cachedBlock, List<DatanodeDescriptor> cached,
      List<DatanodeDescriptor> pendingCached) {
    // To figure out which replicas can be cached, we consult the
    // blocksMap.  We don't want to try to cache a corrupt replica, though.
    BlockInfoContiguous blockInfo = blockManager.
          getStoredBlock(new Block(cachedBlock.getBlockId()));
    if (blockInfo == null) {//检查如果block的记录不存在，直接返回
      LOG.debug("Block {}: can't add new cached replicas," +
          " because there is no record of this block " +
          "on the NameNode.", cachedBlock.getBlockId());
      return;
    }
    if (!blockInfo.isComplete()) {//block不是completed状态也直接返回
      LOG.debug("Block {}: can't cache this block, because it is not yet"
          + " complete.", cachedBlock.getBlockId());
      return;
    }
    // Filter the list of replicas to only the valid targets
    List<DatanodeDescriptor> possibilities =
        new LinkedList<DatanodeDescriptor>();
    int numReplicas = blockInfo.getCapacity();
    Collection<DatanodeDescriptor> corrupt =
        blockManager.getCorruptReplicas(blockInfo);
    int outOfCapacity = 0;
    for (int i = 0; i < numReplicas; i++) {
      DatanodeDescriptor datanode = blockInfo.getDatanode(i);
      if (datanode == null) {
        continue;
      }
      if (datanode.isDecommissioned() || datanode.isDecommissionInProgress()) {
        continue;
      }
      if (corrupt != null && corrupt.contains(datanode)) {
        continue;
      }
      if (pendingCached.contains(datanode) || cached.contains(datanode)) {
        continue;
      }
      long pendingBytes = 0;
      // Subtract pending cached blocks from effective capacity
      //统计需要缓存的大小，该值为负数
      Iterator<CachedBlock> it = datanode.getPendingCached().iterator();
      while (it.hasNext()) {
        CachedBlock cBlock = it.next();
        BlockInfoContiguous info =
            blockManager.getStoredBlock(new Block(cBlock.getBlockId()));
        if (info != null) {
          pendingBytes -= info.getNumBytes();
        }
      }
      it = datanode.getPendingUncached().iterator();
      // Add pending uncached blocks from effective capacity
      //去掉等待取消缓存的大小，这样能释放部分缓存空间
      while (it.hasNext()) {
        CachedBlock cBlock = it.next();
        BlockInfoContiguous info =
            blockManager.getStoredBlock(new Block(cBlock.getBlockId()));
        if (info != null) {
          pendingBytes += info.getNumBytes();
        }
      }
      //如果剩余空间的大小小于block的大小，则空间不足，不能进行缓存
      long pendingCapacity = pendingBytes + datanode.getCacheRemaining();
      if (pendingCapacity < blockInfo.getNumBytes()) {
        LOG.trace("Block {}: DataNode {} is not a valid possibility " +
            "because the block has size {}, but the DataNode only has {}" +
            "bytes of cache remaining ({} pending bytes, {} already cached.",
            blockInfo.getBlockId(), datanode.getDatanodeUuid(),
            blockInfo.getNumBytes(), pendingCapacity, pendingBytes,
            datanode.getCacheRemaining());
        outOfCapacity++;
        continue;
      }
      possibilities.add(datanode);
    }
    //选择一批数据节点，将缓存块加入该节点的带缓存队列中
    List<DatanodeDescriptor> chosen = chooseDatanodesForCaching(possibilities,
        neededCached, blockManager.getDatanodeManager().getStaleInterval());
    for (DatanodeDescriptor datanode : chosen) {
      LOG.trace("Block {}: added to PENDING_CACHED on DataNode {}",
          blockInfo.getBlockId(), datanode.getDatanodeUuid());
      pendingCached.add(datanode);
      boolean added = datanode.getPendingCached().add(cachedBlock);
      assert added;
    }
    // We were unable to satisfy the requested replication factor
    if (neededCached > chosen.size()) {
      LOG.debug("Block {}: we only have {} of {} cached replicas."
              + " {} DataNodes have insufficient cache capacity.",
          blockInfo.getBlockId(),
          (cachedBlock.getReplication() - neededCached + chosen.size()),
          cachedBlock.getReplication(), outOfCapacity
      );
    }
  }

  /**
   * Chooses datanode locations for caching from a list of valid possibilities.
   * Non-stale nodes are chosen before stale nodes.
   * 
   * @param possibilities List of candidate datanodes
   * @param neededCached Number of replicas needed
   * @param staleInterval Age of a stale datanode
   * @return A list of chosen datanodes
   */
  private static List<DatanodeDescriptor> chooseDatanodesForCaching(
      final List<DatanodeDescriptor> possibilities, final int neededCached,
      final long staleInterval) {
    // Make a copy that we can modify
    List<DatanodeDescriptor> targets =
        new ArrayList<DatanodeDescriptor>(possibilities);
    // Selected targets
    List<DatanodeDescriptor> chosen = new LinkedList<DatanodeDescriptor>();

    // Filter out stale datanodes
    List<DatanodeDescriptor> stale = new LinkedList<DatanodeDescriptor>();
    Iterator<DatanodeDescriptor> it = targets.iterator();
    while (it.hasNext()) {
      DatanodeDescriptor d = it.next();
      if (d.isStale(staleInterval)) {
        it.remove();
        stale.add(d);
      }
    }
    // Select targets
    while (chosen.size() < neededCached) {
      // Try to use stale nodes if we're out of non-stale nodes, else we're done
    	//如果target队列中不够选择，就尝试从stale状态的节点中选择
      if (targets.isEmpty()) {
        if (!stale.isEmpty()) {
          targets = stale;
        } else {
          break;
        }
      }
      // Select a random target
      DatanodeDescriptor target =
          chooseRandomDatanodeByRemainingCapacity(targets);
      chosen.add(target);
      targets.remove(target);
    }
    return chosen;
  }

  /**
   * Choose a single datanode from the provided list of possible
   * targets, weighted by the percentage of free space remaining on the node.
   * 
   * @return The chosen datanode
   */
  private static DatanodeDescriptor chooseRandomDatanodeByRemainingCapacity(
      final List<DatanodeDescriptor> targets) {
    // Use a weighted probability to choose the target datanode
    float total = 0;
    for (DatanodeDescriptor d : targets) {
      total += d.getCacheRemainingPercent();
    }
    // Give each datanode a portion of keyspace equal to its relative weight
    // [0, w1) selects d1, [w1, w2) selects d2, etc.
    //按权重等级划分成多个区间，然后随机选址一个权重拿出对应节点
    TreeMap<Integer, DatanodeDescriptor> lottery =
        new TreeMap<Integer, DatanodeDescriptor>();
    int offset = 0;
    for (DatanodeDescriptor d : targets) {
      // Since we're using floats, be paranoid about negative values
      int weight =
          Math.max(1, (int)((d.getCacheRemainingPercent() / total) * 1000000));
      offset += weight;
      lottery.put(offset, d);
    }
    // Choose a number from [0, offset), which is the total amount of weight,
    // to select the winner
    DatanodeDescriptor winner =
        lottery.higherEntry(random.nextInt(offset)).getValue();
    return winner;
  }
}
