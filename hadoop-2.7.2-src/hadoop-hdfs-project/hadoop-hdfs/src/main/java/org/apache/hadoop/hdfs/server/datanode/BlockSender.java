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
package org.apache.hadoop.hdfs.server.datanode;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

import org.apache.commons.logging.Log;
import org.apache.hadoop.fs.ChecksumException;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.datatransfer.PacketHeader;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsVolumeReference;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.LengthInputStream;
import org.apache.hadoop.hdfs.util.DataTransferThrottler;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.ReadaheadPool.ReadaheadRequest;
import org.apache.hadoop.io.nativeio.NativeIO;
import org.apache.hadoop.net.SocketOutputStream;
import org.apache.hadoop.util.DataChecksum;
import org.apache.htrace.Sampler;
import org.apache.htrace.Trace;
import org.apache.htrace.TraceScope;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

/**
 * Reads a block from the disk and sends it to a recipient.
 * 
 * Data sent from the BlockeSender in the following format:
 * <br><b>Data format:</b> <pre>
 *    +--------------------------------------------------+
 *    | ChecksumHeader | Sequence of data PACKETS...     |
 *    +--------------------------------------------------+ 
 * </pre>   
 * <b>ChecksumHeader format:</b> <pre>
 *    +--------------------------------------------------+
 *    | 1 byte CHECKSUM_TYPE | 4 byte BYTES_PER_CHECKSUM |
 *    +--------------------------------------------------+ 
 * </pre>   
 * An empty packet is sent to mark the end of block and read completion.
 * 
 * PACKET Contains a packet header, checksum and data. Amount of data
 * carried is set by BUFFER_SIZE.
 * <pre>
 *   +-----------------------------------------------------+
 *   | Variable length header. See {@link PacketHeader}    |
 *   +-----------------------------------------------------+
 *   | x byte checksum data. x is defined below            |
 *   +-----------------------------------------------------+
 *   | actual data ......                                  |
 *   +-----------------------------------------------------+
 * 
 *   Data is made of Chunks. Each chunk is of length <= BYTES_PER_CHECKSUM.
 *   A checksum is calculated for each chunk.
 *  
 *   x = (length of data + BYTE_PER_CHECKSUM - 1)/BYTES_PER_CHECKSUM *
 *       CHECKSUM_SIZE
 *  
 *   CHECKSUM_SIZE depends on CHECKSUM_TYPE (usually, 4 for CRC32) 
 *  </pre>
 *  
 *  The client reads data until it receives a packet with 
 *  "LastPacketInBlock" set to true or with a zero length. If there is 
 *  no checksum error, it replies to DataNode with OP_STATUS_CHECKSUM_OK.
 */
class BlockSender implements java.io.Closeable {
  static final Log LOG = DataNode.LOG;
  static final Log ClientTraceLog = DataNode.ClientTraceLog;
  private static final boolean is32Bit = 
      System.getProperty("sun.arch.data.model").equals("32");
  /**
   * Minimum buffer used while sending data to clients. Used only if
   * transferTo() is enabled. 64KB is not that large. It could be larger, but
   * not sure if there will be much more improvement.
   */
  private static final int MIN_BUFFER_WITH_TRANSFERTO = 64*1024;
  private static final int TRANSFERTO_BUFFER_SIZE = Math.max(
      HdfsConstants.IO_FILE_BUFFER_SIZE, MIN_BUFFER_WITH_TRANSFERTO);
  
  /** the block to read from */
  private final ExtendedBlock block;
  /** Stream to read block data from */
  private InputStream blockIn;
  /** updated while using transferTo() */
  private long blockInPosition = -1;
  /** Stream to read checksum */
  private DataInputStream checksumIn;
  /** Checksum utility */
  private final DataChecksum checksum;
  /** Initial position to read */
  private long initialOffset;
  /** Current position of read */
  private long offset;
  /** Position of last byte to read from block file */
  private final long endOffset;
  /** Number of bytes in chunk used for computing checksum */
  //一个chunk的大小
  private final int chunkSize;
  /** Number bytes of checksum computed for a chunk */
  //一个chunk对应的checksum大小
  private final int checksumSize;
  /** If true, failure to read checksum is ignored */
  private final boolean corruptChecksumOk;
  /** Sequence number of packet being sent */
  private long seqno;
  /** Set to true if transferTo is allowed for sending data to the client */
  private final boolean transferToAllowed;
  /** Set to true once entire requested byte range has been sent to the client */
  //将整个请求的字节范围发送到客户端后，设置为true
  private boolean sentEntireByteRange;
  /** When true, verify checksum while reading from checksum file */
  private final boolean verifyChecksum;
  /** Format used to print client trace log messages */
  private final String clientTraceFmt;
  private volatile ChunkChecksum lastChunkChecksum = null;
  private DataNode datanode;
  
  /** The file descriptor of the block being sent */
  private FileDescriptor blockInFd;
  /** The reference to the volume where the block is located */
  private FsVolumeReference volumeRef;

  // Cache-management related fields
  private final long readaheadLength;

  private ReadaheadRequest curReadahead;

  private final boolean alwaysReadahead;
  
  private final boolean dropCacheBehindLargeReads;
  
  private final boolean dropCacheBehindAllReads;
  
  private long lastCacheDropOffset;
  
  @VisibleForTesting
  static long CACHE_DROP_INTERVAL_BYTES = 1024 * 1024; // 1MB
  
  /**
   * See {{@link BlockSender#isLongRead()}
   */
  private static final long LONG_READ_THRESHOLD_BYTES = 256 * 1024;
  

  /**
   * Constructor
   * 
   * @param block Block that is being read
   * @param startOffset starting offset to read from
   * @param length length of data to read
   * @param corruptChecksumOk if true, corrupt checksum is okay
   * @param verifyChecksum verify checksum while reading the data
   * @param sendChecksum send checksum to client.
   * @param datanode datanode from which the block is being read
   * @param clientTraceFmt format string used to print client trace logs
   * @throws IOException
   */
  BlockSender(ExtendedBlock block, long startOffset, long length,
              boolean corruptChecksumOk, boolean verifyChecksum,
              boolean sendChecksum, DataNode datanode, String clientTraceFmt,
              CachingStrategy cachingStrategy)
      throws IOException {
    try {
      this.block = block;
      this.corruptChecksumOk = corruptChecksumOk;
      this.verifyChecksum = verifyChecksum;
      this.clientTraceFmt = clientTraceFmt;

      /*
       * If the client asked for the cache to be dropped behind all reads,
       * we honor that.  Otherwise, we use the DataNode defaults.
       * When using DataNode defaults, we use a heuristic where we only
       * drop the cache for large reads.
       */
      //如果缓存策略设置了读取后删除缓存，则根据设置读取后始终删除/不删除 缓存
      //如果没有设置读取后删除缓存，则设置读取文件不删除缓存，对于读取较大文件根据配置设置是否删除缓存，默认不删除
      if (cachingStrategy.getDropBehind() == null) {
        this.dropCacheBehindAllReads = false;
        this.dropCacheBehindLargeReads =
            datanode.getDnConf().dropCacheBehindReads;
      } else {
        this.dropCacheBehindAllReads =
            this.dropCacheBehindLargeReads =
                 cachingStrategy.getDropBehind().booleanValue();
      }
      /*
       * Similarly, if readahead was explicitly requested, we always do it.
       * Otherwise, we read ahead based on the DataNode settings, and only
       * when the reads are large.
       */
      //如果设置了预读取，则根据cachingStrategy.readahead参数设置预读取大小
      //如果没有设置预读取大小，则根据配置文件设置大小，默认为4M
      if (cachingStrategy.getReadahead() == null) {
        this.alwaysReadahead = false;
        this.readaheadLength = datanode.getDnConf().readaheadLength;
      } else {
        this.alwaysReadahead = true;
        this.readaheadLength = cachingStrategy.getReadahead().longValue();
      }
      this.datanode = datanode;
      //不管是否在datanode方校验，都要发送检验数据
      if (verifyChecksum) {
        // To simplify implementation, callers may not specify verification
        // without sending.
        Preconditions.checkArgument(sendChecksum,
            "If verifying checksum, currently must also send it.");
      }
      //获取副本，以及副本的有效长度
      final Replica replica;
      final long replicaVisibleLength;
      synchronized(datanode.data) { 
        replica = getReplica(block, datanode);
        replicaVisibleLength = replica.getVisibleLength();
      }
      // if there is a write in progress
      //如果副本正在写
      ChunkChecksum chunkChecksum = null;
      if (replica instanceof ReplicaBeingWritten) {
        final ReplicaBeingWritten rbw = (ReplicaBeingWritten)replica;
        //等待数据块长度达到 startOffset + length的读取要求，最多等待3秒
        waitForMinLength(rbw, startOffset + length);
        chunkChecksum = rbw.getLastChecksumAndDataLen();
      }
      //检查副本的时间戳，如果读取的时间戳较大，则抛出异常；如果读取的时间戳较小，则重置读取时间戳
      if (replica.getGenerationStamp() < block.getGenerationStamp()) {
        throw new IOException("Replica gen stamp < block genstamp, block="
            + block + ", replica=" + replica);
      } else if (replica.getGenerationStamp() > block.getGenerationStamp()) {
        if (DataNode.LOG.isDebugEnabled()) {
          DataNode.LOG.debug("Bumping up the client provided"
              + " block's genstamp to latest " + replica.getGenerationStamp()
              + " for block " + block);
        }
        block.setGenerationStamp(replica.getGenerationStamp());
      }
      //如果副本有效长度小于0，则抛出异常
      if (replicaVisibleLength < 0) {
        throw new IOException("Replica is not readable, block="
            + block + ", replica=" + replica);
      }
      if (DataNode.LOG.isDebugEnabled()) {
        DataNode.LOG.debug("block=" + block + ", replica=" + replica);
      }

      // transferToFully() fails on 32 bit platforms for block sizes >= 2GB,
      // use normal transfer in those cases
      //在32位平台上传输大小不能超过2G
      this.transferToAllowed = datanode.getDnConf().transferToAllowed &&
        (!is32Bit || length <= Integer.MAX_VALUE);

      // Obtain a reference before reading data
      //获取存储目录的引用
      this.volumeRef = datanode.data.getVolume(block).obtainReference();

      /* 
       * (corruptChecksumOK, meta_file_exist): operation
       * True,   True: will verify checksum  
       * True,  False: No verify, e.g., need to read data from a corrupted file 
       * False,  True: will verify checksum
       * False, False: throws IOException file not found
       */
      //读取meta文件，获取DataChecksum
      DataChecksum csum = null;
      if (verifyChecksum || sendChecksum) {
        LengthInputStream metaIn = null;
        boolean keepMetaInOpen = false;
        try {
          metaIn = datanode.data.getMetaDataInputStream(block);
          if (!corruptChecksumOk || metaIn != null) {
            if (metaIn == null) {
              //need checksum but meta-data not found
              throw new FileNotFoundException("Meta-data not found for " +
                  block);
            }

            // The meta file will contain only the header if the NULL checksum
            // type was used, or if the replica was written to transient storage.
            // Checksum verification is not performed for replicas on transient
            // storage.  The header is important for determining the checksum
            // type later when lazy persistence copies the block to non-transient
            // storage and computes the checksum.
            if (metaIn.getLength() > BlockMetadataHeader.getHeaderSize()) {
              checksumIn = new DataInputStream(new BufferedInputStream(
                  metaIn, HdfsConstants.IO_FILE_BUFFER_SIZE));
  
              csum = BlockMetadataHeader.readDataChecksum(checksumIn, block);
              keepMetaInOpen = true;
            }
          } else {
            LOG.warn("Could not find metadata file for " + block);
          }
        } finally {
          if (!keepMetaInOpen) {
            IOUtils.closeStream(metaIn);
          }
        }
      }
      if (csum == null) {
        // The number of bytes per checksum here determines the alignment
        // of reads: we always start reading at a checksum chunk boundary,
        // even if the checksum type is NULL. So, choosing too big of a value
        // would risk sending too much unnecessary data. 512 (1 disk sector)
        // is likely to result in minimal extra IO.
    	//数据块总是需要从校验和块边界开始阅读，即使校验类型为空
    	//选择较大读取值时没有必要的
        csum = DataChecksum.newDataChecksum(DataChecksum.Type.NULL, 512);
      }

      /*
       * If chunkSize is very large, then the metadata file is mostly
       * corrupted. For now just truncate bytesPerchecksum to blockLength.
       */
      //限制每个校验和对应的chunk大小，不能超过了文件的有效数据大小
      int size = csum.getBytesPerChecksum();
      if (size > 10*1024*1024 && size > replicaVisibleLength) {
        csum = DataChecksum.newDataChecksum(csum.getChecksumType(),
            Math.max((int)replicaVisibleLength, 10*1024*1024));
        size = csum.getBytesPerChecksum();        
      }
      chunkSize = size;//校验块大小
      checksum = csum;//校验算法
      checksumSize = checksum.getChecksumSize();//校验和长度
      length = length < 0 ? replicaVisibleLength : length;

      // end is either last byte on disk or the length for which we have a 
      // checksum
      //end要么是正在读取副本的最后一个chunk的位置，要么就是文件的最后一个byte的位置
      long end = chunkChecksum != null ? chunkChecksum.getDataLength()
          : replica.getBytesOnDisk();
      //校验读取的偏移是否超过范围
      if (startOffset < 0 || startOffset > end
          || (length + startOffset) > end) {
        String msg = " Offset " + startOffset + " and length " + length
        + " don't match block " + block + " ( blockLen " + end + " )";
        LOG.warn(datanode.getDNRegistrationForBP(block.getBlockPoolId()) +
            ":sendBlock() : " + msg);
        throw new IOException(msg);
      }
      
      // Ensure read offset is position at the beginning of chunk
      //确保读偏移量在chunk的开头位置
      offset = startOffset - (startOffset % chunkSize);
      if (length >= 0) {
        // Ensure endOffset points to end of chunk.
    	//确保结束偏移在chunk的结束位置
        long tmpLen = startOffset + length;
        if (tmpLen % chunkSize != 0) {
          tmpLen += (chunkSize - tmpLen % chunkSize);
        }
        if (tmpLen < end) {
          // will use on-disk checksum here since the end is a stable chunk
          //结束位置还在数据块内，则可以使用磁盘上的校验值
          end = tmpLen;
        } else if (chunkChecksum != null) {
          // last chunk is changing. flag that we need to use in-memory checksum 
          //目前有写线程正在更改这个校验块，则使用内存中的校验值
          this.lastChunkChecksum = chunkChecksum;
        }
      }
      endOffset = end;

      // seek to the right offsets
      //将meta文件的输入流游标设置到正确偏移上
      if (offset > 0 && checksumIn != null) {
        long checksumSkip = (offset / chunkSize) * checksumSize;
        // note blockInStream is seeked when created below
        if (checksumSkip > 0) {
          // Should we use seek() for checksum file as well?
          IOUtils.skipFully(checksumIn, checksumSkip);
        }
      }
      //packet序列号设置为0
      seqno = 0;

      if (DataNode.LOG.isDebugEnabled()) {
        DataNode.LOG.debug("replica=" + replica);
      }
      //将副本文件的输入流游标设置到正确的偏移
      blockIn = datanode.data.getBlockInputStream(block, offset); // seek to offset
      
      if (blockIn instanceof FileInputStream) {
        blockInFd = ((FileInputStream)blockIn).getFD();
      } else {
        blockInFd = null;
      }
    } catch (IOException ioe) {
      IOUtils.closeStream(this);
      IOUtils.closeStream(blockIn);
      throw ioe;
    }
  }

  /**
   * close opened files.
   */
  @Override
  public void close() throws IOException {
	//条件满足，移除预读取的缓存
    if (blockInFd != null &&
        ((dropCacheBehindAllReads) ||
         (dropCacheBehindLargeReads && isLongRead()))) {
      try {
        NativeIO.POSIX.getCacheManipulator().posixFadviseIfPossible(
            block.getBlockName(), blockInFd, lastCacheDropOffset,
            offset - lastCacheDropOffset,
            NativeIO.POSIX.POSIX_FADV_DONTNEED);
      } catch (Exception e) {
        LOG.warn("Unable to drop cache on file close", e);
      }
    }
    if (curReadahead != null) {
      curReadahead.cancel();
    }
    //关闭meta文件的读取流
    IOException ioe = null;
    if(checksumIn!=null) {
      try {
        checksumIn.close(); // close checksum file
      } catch (IOException e) {
        ioe = e;
      }
      checksumIn = null;
    }   
    //关闭副本文件的读取流
    if(blockIn!=null) {
      try {
        blockIn.close(); // close data file
      } catch (IOException e) {
        ioe = e;
      }
      blockIn = null;
      blockInFd = null;
    }
    //释放存储目录的引用
    if (volumeRef != null) {
      IOUtils.cleanup(null, volumeRef);
      volumeRef = null;
    }
    // throw IOException if there is any
    if(ioe!= null) {
      throw ioe;
    }
  }
  
  private static Replica getReplica(ExtendedBlock block, DataNode datanode)
      throws ReplicaNotFoundException {
    Replica replica = datanode.data.getReplica(block.getBlockPoolId(),
        block.getBlockId());
    if (replica == null) {
      throw new ReplicaNotFoundException(block);
    }
    return replica;
  }
  
  /**
   * Wait for rbw replica to reach the length
   * @param rbw replica that is being written to
   * @param len minimum length to reach
   * @throws IOException on failing to reach the len in given wait time
   */
  private static void waitForMinLength(ReplicaBeingWritten rbw, long len)
      throws IOException {
    // Wait for 3 seconds for rbw replica to reach the minimum length
    for (int i = 0; i < 30 && rbw.getBytesOnDisk() < len; i++) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException ie) {
        throw new IOException(ie);
      }
    }
    long bytesOnDisk = rbw.getBytesOnDisk();
    if (bytesOnDisk < len) {
      throw new IOException(
          String.format("Need %d bytes, but only %d bytes available", len,
              bytesOnDisk));
    }
  }
  
  /**
   * Converts an IOExcpetion (not subclasses) to SocketException.
   * This is typically done to indicate to upper layers that the error 
   * was a socket error rather than often more serious exceptions like 
   * disk errors.
   */
  private static IOException ioeToSocketException(IOException ioe) {
    if (ioe.getClass().equals(IOException.class)) {
      // "se" could be a new class in stead of SocketException.
      IOException se = new SocketException("Original Exception : " + ioe);
      se.initCause(ioe);
      /* Change the stacktrace so that original trace is not truncated
       * when printed.*/ 
      se.setStackTrace(ioe.getStackTrace());
      return se;
    }
    // otherwise just return the same exception.
    return ioe;
  }

  /**
   * @param datalen Length of data 
   * @return number of chunks for data of given size
   */
  private int numberOfChunks(long datalen) {
    return (int) ((datalen + chunkSize - 1)/chunkSize);
  }
  
  /**
   * Sends a packet with up to maxChunks chunks of data.
   * 
   * @param pkt buffer used for writing packet data
   * @param maxChunks maximum number of chunks to send
   * @param out stream to send data to
   * @param transferTo use transferTo to send data
   * @param throttler used for throttling data transfer bandwidth
   */
  private int sendPacket(ByteBuffer pkt, int maxChunks, OutputStream out,
      boolean transferTo, DataTransferThrottler throttler) throws IOException {
	//获取能发送的数据长度
    int dataLen = (int) Math.min(endOffset - offset,
                             (chunkSize * (long) maxChunks));
    //计算要发送的数据的chunk个数
    int numChunks = numberOfChunks(dataLen); // Number of chunks be sent in the packet
    //计算校验数据长度
    int checksumDataLen = numChunks * checksumSize;
    //计算数据包长度
    int packetLen = dataLen + checksumDataLen + 4;
    //判断是否是最后一个数据包
    boolean lastDataPacket = offset + dataLen == endOffset && dataLen > 0;
    
    // The packet buffer is organized as follows:
    // _______HHHHCCCCD?D?D?D?
    //        ^   ^
    //        |   \ checksumOff
    //        \ headerOff
    // _ padding, since the header is variable-length
    // H = header and length prefixes
    // C = checksums
    // D? = data, if transferTo is false.
    //将数据包头域写入缓存中
    int headerLen = writePacketHeader(pkt, dataLen, packetLen);
    
    // Per above, the header doesn't start at the beginning of the
    // buffer
    //数据包头域在缓存中的位置
    int headerOff = pkt.position() - headerLen;
    //校验数据在缓存中的开始位置
    int checksumOff = pkt.position();
    byte[] buf = pkt.array();
    //将校验数据写入缓存
    if (checksumSize > 0 && checksumIn != null) {
      readChecksum(buf, checksumOff, checksumDataLen);

      // write in progress that we need to use to get last checksum
      //如果是最后一个数据包，则将最后一个checksum拷贝到写入缓存中
      if (lastDataPacket && lastChunkChecksum != null) {
        int start = checksumOff + checksumDataLen - checksumSize;
        byte[] updatedChecksum = lastChunkChecksum.getChecksum();
        
        if (updatedChecksum != null) {
          System.arraycopy(updatedChecksum, 0, buf, start, checksumSize);
        }
      }
    }
    //数据区域在缓存中的开始位置
    int dataOff = checksumOff + checksumDataLen;
    if (!transferTo) { // normal transfer
      //如果不是transferTo模式，则拷贝数据到缓存中
      IOUtils.readFully(blockIn, buf, dataOff, dataLen);
      //校验和是否正确
      if (verifyChecksum) {
        verifyChecksum(buf, dataOff, dataLen, numChunks, checksumOff);
      }
    }
    
    try {
      //transferTo模式
      if (transferTo) {
        SocketOutputStream sockOut = (SocketOutputStream)out;
        // First write header and checksums
        //将头域和校验和写入输出流中
        sockOut.write(buf, headerOff, dataOff - headerOff);
        
        // no need to flush since we know out is not a buffered stream
        //将数据从数据块文件直接零拷贝到IO流中
        FileChannel fileCh = ((FileInputStream)blockIn).getChannel();
        LongWritable waitTime = new LongWritable();
        LongWritable transferTime = new LongWritable();
        sockOut.transferToFully(fileCh, blockInPosition, dataLen, 
            waitTime, transferTime);
        datanode.metrics.addSendDataPacketBlockedOnNetworkNanos(waitTime.get());
        datanode.metrics.addSendDataPacketTransferNanos(transferTime.get());
        blockInPosition += dataLen;
      } else {
        // normal transfer
    	//正常模式下，将缓存中所有数据（包括头域，校验和以及实际数据）写入输出流中
        out.write(buf, headerOff, dataOff + dataLen - headerOff);
      }
    } catch (IOException e) {
      if (e instanceof SocketTimeoutException) {
        /*
         * writing to client timed out.  This happens if the client reads
         * part of a block and then decides not to read the rest (but leaves
         * the socket open).
         * 
         * Reporting of this case is done in DataXceiver#run
         */
      } else {
        /* Exception while writing to the client. Connection closure from
         * the other end is mostly the case and we do not care much about
         * it. But other things can go wrong, especially in transferTo(),
         * which we do not want to ignore.
         *
         * The message parsing below should not be considered as a good
         * coding example. NEVER do it to drive a program logic. NEVER.
         * It was done here because the NIO throws an IOException for EPIPE.
         */
        String ioem = e.getMessage();
        if (!ioem.startsWith("Broken pipe") && !ioem.startsWith("Connection reset")) {
          LOG.error("BlockSender.sendChunks() exception: ", e);
        }
        datanode.getBlockScanner().markSuspectBlock(
              volumeRef.getVolume().getStorageID(),
              block);
      }
      throw ioeToSocketException(e);
    }
    //使用节流器控制写入速度
    if (throttler != null) { // rebalancing so throttle
      throttler.throttle(packetLen);
    }

    return dataLen;
  }
  
  /**
   * Read checksum into given buffer
   * @param buf buffer to read the checksum into
   * @param checksumOffset offset at which to write the checksum into buf
   * @param checksumLen length of checksum to write
   * @throws IOException on error
   */
  private void readChecksum(byte[] buf, final int checksumOffset,
      final int checksumLen) throws IOException {
    if (checksumSize <= 0 && checksumIn == null) {
      return;
    }
    try {
      checksumIn.readFully(buf, checksumOffset, checksumLen);
    } catch (IOException e) {
      LOG.warn(" Could not read or failed to veirfy checksum for data"
          + " at offset " + offset + " for block " + block, e);
      IOUtils.closeStream(checksumIn);
      checksumIn = null;
      if (corruptChecksumOk) {
        if (checksumOffset < checksumLen) {
          // Just fill the array with zeros.
          Arrays.fill(buf, checksumOffset, checksumLen, (byte) 0);
        }
      } else {
        throw e;
      }
    }
  }
  
  /**
   * Compute checksum for chunks and verify the checksum that is read from
   * the metadata file is correct.
   * 
   * @param buf buffer that has checksum and data
   * @param dataOffset position where data is written in the buf
   * @param datalen length of data
   * @param numChunks number of chunks corresponding to data
   * @param checksumOffset offset where checksum is written in the buf
   * @throws ChecksumException on failed checksum verification
   */
  public void verifyChecksum(final byte[] buf, final int dataOffset,
      final int datalen, final int numChunks, final int checksumOffset)
      throws ChecksumException {
    int dOff = dataOffset;
    int cOff = checksumOffset;
    int dLeft = datalen;

    for (int i = 0; i < numChunks; i++) {
      checksum.reset();
      int dLen = Math.min(dLeft, chunkSize);
      checksum.update(buf, dOff, dLen);
      if (!checksum.compare(buf, cOff)) {
        long failedPos = offset + datalen - dLeft;
        throw new ChecksumException("Checksum failed at " + failedPos,
            failedPos);
      }
      dLeft -= dLen;
      dOff += dLen;
      cOff += checksumSize;
    }
  }
  
  /**
   * sendBlock() is used to read block and its metadata and stream the data to
   * either a client or to another datanode. 
   * 
   * @param out  stream to which the block is written to
   * @param baseStream optional. if non-null, <code>out</code> is assumed to 
   *        be a wrapper over this stream. This enables optimizations for
   *        sending the data, e.g. 
   *        {@link SocketOutputStream#transferToFully(FileChannel, 
   *        long, int)}.
   * @param throttler for sending data.
   * @return total bytes read, including checksum data.
   */
  long sendBlock(DataOutputStream out, OutputStream baseStream, 
                 DataTransferThrottler throttler) throws IOException {
    TraceScope scope =
        Trace.startSpan("sendBlock_" + block.getBlockId(), Sampler.NEVER);
    try {
      return doSendBlock(out, baseStream, throttler);
    } finally {
      scope.close();
    }
  }

  private long doSendBlock(DataOutputStream out, OutputStream baseStream,
        DataTransferThrottler throttler) throws IOException {
    if (out == null) {
      throw new IOException( "out stream is null" );
    }
    initialOffset = offset;
    long totalRead = 0;
    OutputStream streamForSendChunks = out;
    
    lastCacheDropOffset = initialOffset;
    //如果是较长读取，则设置建议按顺序访问此文件描述符
    if (isLongRead() && blockInFd != null) {
      // Advise that this file descriptor will be accessed sequentially.
      NativeIO.POSIX.getCacheManipulator().posixFadviseIfPossible(
          block.getBlockName(), blockInFd, 0, 0,
          NativeIO.POSIX.POSIX_FADV_SEQUENTIAL);
    }
    
    // Trigger readahead of beginning of file if configured.
    //触发一次预读取，将数据加载到缓存区
    manageOsCache();

    final long startTime = ClientTraceLog.isDebugEnabled() ? System.nanoTime() : 0;
    try {
      int maxChunksPerPacket;
      int pktBufSize = PacketHeader.PKT_MAX_HEADER_LEN;
      //判断是不是transferTo，用零拷贝方式传到客户端
      boolean transferTo = transferToAllowed && !verifyChecksum
          && baseStream instanceof SocketOutputStream
          && blockIn instanceof FileInputStream;
      if (transferTo) {
        FileChannel fileChannel = ((FileInputStream)blockIn).getChannel();
        blockInPosition = fileChannel.position();
        streamForSendChunks = baseStream;
        //获取一个包能存放的最大chunk个数，默认是64*1024
        maxChunksPerPacket = numberOfChunks(TRANSFERTO_BUFFER_SIZE);
        
        // Smaller packet size to only hold checksum when doing transferTo
        pktBufSize += checksumSize * maxChunksPerPacket;
      } else {//不是用零拷贝的方式传输
    	//获取一个包能存放的最大chunk个数，默认4*1024
        maxChunksPerPacket = Math.max(1,
            numberOfChunks(HdfsConstants.IO_FILE_BUFFER_SIZE));
        // Packet size includes both checksum and data
        pktBufSize += (chunkSize + checksumSize) * maxChunksPerPacket;
      }
      //构造能容纳一个数据包的缓存区
      ByteBuffer pktBuf = ByteBuffer.allocate(pktBufSize);
      
      while (endOffset > offset && !Thread.currentThread().isInterrupted()) {
    	//首先预读取
        manageOsCache();
        //发送数据包到客户端
        long len = sendPacket(pktBuf, maxChunksPerPacket, streamForSendChunks,
            transferTo, throttler);
        offset += len;
        totalRead += len + (numberOfChunks(len) * checksumSize);
        seqno++;
      }
      // If this thread was interrupted, then it did not send the full block.
      //如果当前线程被中断，则不再发送给完整的数据块
      if (!Thread.currentThread().isInterrupted()) {
        try {
          // send an empty packet to mark the end of the block
          //发送一个空的数据包，标志数据读取结束
          sendPacket(pktBuf, maxChunksPerPacket, streamForSendChunks, transferTo,
              throttler);
          out.flush();
        } catch (IOException e) { //socket error
          throw ioeToSocketException(e);
        }

        sentEntireByteRange = true;
      }
    } finally {
      if ((clientTraceFmt != null) && ClientTraceLog.isDebugEnabled()) {
        final long endTime = System.nanoTime();
        ClientTraceLog.debug(String.format(clientTraceFmt, totalRead,
            initialOffset, endTime - startTime));
      }
      //关闭数据块文件，校验文件以及回收操作系统缓存区
      close();
    }
    return totalRead;
  }

  /**
   * Manage the OS buffer cache by performing read-ahead
   * and drop-behind.
   */
  private void manageOsCache() throws IOException {
    // We can't manage the cache for this block if we don't have a file
    // descriptor to work with.
    if (blockInFd == null) return;

    // Perform readahead if necessary
    //按条件出发预读取操作
    if ((readaheadLength > 0) && (datanode.readaheadPool != null) &&
          (alwaysReadahead || isLongRead())) {
    	//满足预读取条件，触发预读取
      curReadahead = datanode.readaheadPool.readaheadStream(
          clientTraceFmt, blockInFd, offset, readaheadLength, Long.MAX_VALUE,
          curReadahead);
    }

    // Drop what we've just read from cache, since we aren't
    // likely to need it again
    //丢弃刚才从缓存读取的数据，因为不在需要使用这些数据了
    if (dropCacheBehindAllReads ||
        (dropCacheBehindLargeReads && isLongRead())) {
      //如果目前的读取位置大于下一次缓存的位置，则将lastCacheDropOffset到offset的数据全部从缓存中丢弃
      long nextCacheDropOffset = lastCacheDropOffset + CACHE_DROP_INTERVAL_BYTES;
      if (offset >= nextCacheDropOffset) {
        long dropLength = offset - lastCacheDropOffset;
        NativeIO.POSIX.getCacheManipulator().posixFadviseIfPossible(
            block.getBlockName(), blockInFd, lastCacheDropOffset,
            dropLength, NativeIO.POSIX.POSIX_FADV_DONTNEED);
        lastCacheDropOffset = offset;
      }
    }
  }

  /**
   * Returns true if we have done a long enough read for this block to qualify
   * for the DataNode-wide cache management defaults.  We avoid applying the
   * cache management defaults to smaller reads because the overhead would be
   * too high.
   *
   * Note that if the client explicitly asked for dropBehind, we will do it
   * even on short reads.
   * 
   * This is also used to determine when to invoke
   * posix_fadvise(POSIX_FADV_SEQUENTIAL).
   */
  private boolean isLongRead() {
    return (endOffset - initialOffset) > LONG_READ_THRESHOLD_BYTES;
  }

  /**
   * Write packet header into {@code pkt},
   * return the length of the header written.
   */
  private int writePacketHeader(ByteBuffer pkt, int dataLen, int packetLen) {
    pkt.clear();
    // both syncBlock and syncPacket are false
    PacketHeader header = new PacketHeader(packetLen, offset, seqno,
        (dataLen == 0), dataLen, false);
    
    int size = header.getSerializedSize();
    pkt.position(PacketHeader.PKT_MAX_HEADER_LEN - size);
    header.putInBuffer(pkt);
    return size;
  }
  
  boolean didSendEntireByteRange() {
    return sentEntireByteRange;
  }

  /**
   * @return the checksum type that will be used with this block transfer.
   */
  DataChecksum getChecksum() {
    return checksum;
  }

  /**
   * @return the offset into the block file where the sender is currently
   * reading.
   */
  long getOffset() {
    return offset;
  }
}
