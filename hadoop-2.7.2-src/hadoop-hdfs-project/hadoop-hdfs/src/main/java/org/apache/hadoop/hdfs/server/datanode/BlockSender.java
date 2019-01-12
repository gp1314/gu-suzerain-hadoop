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
  //һ��chunk�Ĵ�С
  private final int chunkSize;
  /** Number bytes of checksum computed for a chunk */
  //һ��chunk��Ӧ��checksum��С
  private final int checksumSize;
  /** If true, failure to read checksum is ignored */
  private final boolean corruptChecksumOk;
  /** Sequence number of packet being sent */
  private long seqno;
  /** Set to true if transferTo is allowed for sending data to the client */
  private final boolean transferToAllowed;
  /** Set to true once entire requested byte range has been sent to the client */
  //������������ֽڷ�Χ���͵��ͻ��˺�����Ϊtrue
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
      //���������������˶�ȡ��ɾ�����棬��������ö�ȡ��ʼ��ɾ��/��ɾ�� ����
      //���û�����ö�ȡ��ɾ�����棬�����ö�ȡ�ļ���ɾ�����棬���ڶ�ȡ�ϴ��ļ��������������Ƿ�ɾ�����棬Ĭ�ϲ�ɾ��
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
      //���������Ԥ��ȡ�������cachingStrategy.readahead��������Ԥ��ȡ��С
      //���û������Ԥ��ȡ��С������������ļ����ô�С��Ĭ��Ϊ4M
      if (cachingStrategy.getReadahead() == null) {
        this.alwaysReadahead = false;
        this.readaheadLength = datanode.getDnConf().readaheadLength;
      } else {
        this.alwaysReadahead = true;
        this.readaheadLength = cachingStrategy.getReadahead().longValue();
      }
      this.datanode = datanode;
      //�����Ƿ���datanode��У�飬��Ҫ���ͼ�������
      if (verifyChecksum) {
        // To simplify implementation, callers may not specify verification
        // without sending.
        Preconditions.checkArgument(sendChecksum,
            "If verifying checksum, currently must also send it.");
      }
      //��ȡ�������Լ���������Ч����
      final Replica replica;
      final long replicaVisibleLength;
      synchronized(datanode.data) { 
        replica = getReplica(block, datanode);
        replicaVisibleLength = replica.getVisibleLength();
      }
      // if there is a write in progress
      //�����������д
      ChunkChecksum chunkChecksum = null;
      if (replica instanceof ReplicaBeingWritten) {
        final ReplicaBeingWritten rbw = (ReplicaBeingWritten)replica;
        //�ȴ����ݿ鳤�ȴﵽ startOffset + length�Ķ�ȡҪ�����ȴ�3��
        waitForMinLength(rbw, startOffset + length);
        chunkChecksum = rbw.getLastChecksumAndDataLen();
      }
      //��鸱����ʱ����������ȡ��ʱ����ϴ����׳��쳣�������ȡ��ʱ�����С�������ö�ȡʱ���
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
      //���������Ч����С��0�����׳��쳣
      if (replicaVisibleLength < 0) {
        throw new IOException("Replica is not readable, block="
            + block + ", replica=" + replica);
      }
      if (DataNode.LOG.isDebugEnabled()) {
        DataNode.LOG.debug("block=" + block + ", replica=" + replica);
      }

      // transferToFully() fails on 32 bit platforms for block sizes >= 2GB,
      // use normal transfer in those cases
      //��32λƽ̨�ϴ����С���ܳ���2G
      this.transferToAllowed = datanode.getDnConf().transferToAllowed &&
        (!is32Bit || length <= Integer.MAX_VALUE);

      // Obtain a reference before reading data
      //��ȡ�洢Ŀ¼������
      this.volumeRef = datanode.data.getVolume(block).obtainReference();

      /* 
       * (corruptChecksumOK, meta_file_exist): operation
       * True,   True: will verify checksum  
       * True,  False: No verify, e.g., need to read data from a corrupted file 
       * False,  True: will verify checksum
       * False, False: throws IOException file not found
       */
      //��ȡmeta�ļ�����ȡDataChecksum
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
    	//���ݿ�������Ҫ��У��Ϳ�߽翪ʼ�Ķ�����ʹУ������Ϊ��
    	//ѡ��ϴ��ȡֵʱû�б�Ҫ��
        csum = DataChecksum.newDataChecksum(DataChecksum.Type.NULL, 512);
      }

      /*
       * If chunkSize is very large, then the metadata file is mostly
       * corrupted. For now just truncate bytesPerchecksum to blockLength.
       */
      //����ÿ��У��Ͷ�Ӧ��chunk��С�����ܳ������ļ�����Ч���ݴ�С
      int size = csum.getBytesPerChecksum();
      if (size > 10*1024*1024 && size > replicaVisibleLength) {
        csum = DataChecksum.newDataChecksum(csum.getChecksumType(),
            Math.max((int)replicaVisibleLength, 10*1024*1024));
        size = csum.getBytesPerChecksum();        
      }
      chunkSize = size;//У����С
      checksum = csum;//У���㷨
      checksumSize = checksum.getChecksumSize();//У��ͳ���
      length = length < 0 ? replicaVisibleLength : length;

      // end is either last byte on disk or the length for which we have a 
      // checksum
      //endҪô�����ڶ�ȡ���������һ��chunk��λ�ã�Ҫô�����ļ������һ��byte��λ��
      long end = chunkChecksum != null ? chunkChecksum.getDataLength()
          : replica.getBytesOnDisk();
      //У���ȡ��ƫ���Ƿ񳬹���Χ
      if (startOffset < 0 || startOffset > end
          || (length + startOffset) > end) {
        String msg = " Offset " + startOffset + " and length " + length
        + " don't match block " + block + " ( blockLen " + end + " )";
        LOG.warn(datanode.getDNRegistrationForBP(block.getBlockPoolId()) +
            ":sendBlock() : " + msg);
        throw new IOException(msg);
      }
      
      // Ensure read offset is position at the beginning of chunk
      //ȷ����ƫ������chunk�Ŀ�ͷλ��
      offset = startOffset - (startOffset % chunkSize);
      if (length >= 0) {
        // Ensure endOffset points to end of chunk.
    	//ȷ������ƫ����chunk�Ľ���λ��
        long tmpLen = startOffset + length;
        if (tmpLen % chunkSize != 0) {
          tmpLen += (chunkSize - tmpLen % chunkSize);
        }
        if (tmpLen < end) {
          // will use on-disk checksum here since the end is a stable chunk
          //����λ�û������ݿ��ڣ������ʹ�ô����ϵ�У��ֵ
          end = tmpLen;
        } else if (chunkChecksum != null) {
          // last chunk is changing. flag that we need to use in-memory checksum 
          //Ŀǰ��д�߳����ڸ������У��飬��ʹ���ڴ��е�У��ֵ
          this.lastChunkChecksum = chunkChecksum;
        }
      }
      endOffset = end;

      // seek to the right offsets
      //��meta�ļ����������α����õ���ȷƫ����
      if (offset > 0 && checksumIn != null) {
        long checksumSkip = (offset / chunkSize) * checksumSize;
        // note blockInStream is seeked when created below
        if (checksumSkip > 0) {
          // Should we use seek() for checksum file as well?
          IOUtils.skipFully(checksumIn, checksumSkip);
        }
      }
      //packet���к�����Ϊ0
      seqno = 0;

      if (DataNode.LOG.isDebugEnabled()) {
        DataNode.LOG.debug("replica=" + replica);
      }
      //�������ļ����������α����õ���ȷ��ƫ��
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
	//�������㣬�Ƴ�Ԥ��ȡ�Ļ���
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
    //�ر�meta�ļ��Ķ�ȡ��
    IOException ioe = null;
    if(checksumIn!=null) {
      try {
        checksumIn.close(); // close checksum file
      } catch (IOException e) {
        ioe = e;
      }
      checksumIn = null;
    }   
    //�رո����ļ��Ķ�ȡ��
    if(blockIn!=null) {
      try {
        blockIn.close(); // close data file
      } catch (IOException e) {
        ioe = e;
      }
      blockIn = null;
      blockInFd = null;
    }
    //�ͷŴ洢Ŀ¼������
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
	//��ȡ�ܷ��͵����ݳ���
    int dataLen = (int) Math.min(endOffset - offset,
                             (chunkSize * (long) maxChunks));
    //����Ҫ���͵����ݵ�chunk����
    int numChunks = numberOfChunks(dataLen); // Number of chunks be sent in the packet
    //����У�����ݳ���
    int checksumDataLen = numChunks * checksumSize;
    //�������ݰ�����
    int packetLen = dataLen + checksumDataLen + 4;
    //�ж��Ƿ������һ�����ݰ�
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
    //�����ݰ�ͷ��д�뻺����
    int headerLen = writePacketHeader(pkt, dataLen, packetLen);
    
    // Per above, the header doesn't start at the beginning of the
    // buffer
    //���ݰ�ͷ���ڻ����е�λ��
    int headerOff = pkt.position() - headerLen;
    //У�������ڻ����еĿ�ʼλ��
    int checksumOff = pkt.position();
    byte[] buf = pkt.array();
    //��У������д�뻺��
    if (checksumSize > 0 && checksumIn != null) {
      readChecksum(buf, checksumOff, checksumDataLen);

      // write in progress that we need to use to get last checksum
      //��������һ�����ݰ��������һ��checksum������д�뻺����
      if (lastDataPacket && lastChunkChecksum != null) {
        int start = checksumOff + checksumDataLen - checksumSize;
        byte[] updatedChecksum = lastChunkChecksum.getChecksum();
        
        if (updatedChecksum != null) {
          System.arraycopy(updatedChecksum, 0, buf, start, checksumSize);
        }
      }
    }
    //���������ڻ����еĿ�ʼλ��
    int dataOff = checksumOff + checksumDataLen;
    if (!transferTo) { // normal transfer
      //�������transferToģʽ���򿽱����ݵ�������
      IOUtils.readFully(blockIn, buf, dataOff, dataLen);
      //У����Ƿ���ȷ
      if (verifyChecksum) {
        verifyChecksum(buf, dataOff, dataLen, numChunks, checksumOff);
      }
    }
    
    try {
      //transferToģʽ
      if (transferTo) {
        SocketOutputStream sockOut = (SocketOutputStream)out;
        // First write header and checksums
        //��ͷ���У���д���������
        sockOut.write(buf, headerOff, dataOff - headerOff);
        
        // no need to flush since we know out is not a buffered stream
        //�����ݴ����ݿ��ļ�ֱ���㿽����IO����
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
    	//����ģʽ�£����������������ݣ�����ͷ��У����Լ�ʵ�����ݣ�д���������
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
    //ʹ�ý���������д���ٶ�
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
    //����ǽϳ���ȡ�������ý��鰴˳����ʴ��ļ�������
    if (isLongRead() && blockInFd != null) {
      // Advise that this file descriptor will be accessed sequentially.
      NativeIO.POSIX.getCacheManipulator().posixFadviseIfPossible(
          block.getBlockName(), blockInFd, 0, 0,
          NativeIO.POSIX.POSIX_FADV_SEQUENTIAL);
    }
    
    // Trigger readahead of beginning of file if configured.
    //����һ��Ԥ��ȡ�������ݼ��ص�������
    manageOsCache();

    final long startTime = ClientTraceLog.isDebugEnabled() ? System.nanoTime() : 0;
    try {
      int maxChunksPerPacket;
      int pktBufSize = PacketHeader.PKT_MAX_HEADER_LEN;
      //�ж��ǲ���transferTo�����㿽����ʽ�����ͻ���
      boolean transferTo = transferToAllowed && !verifyChecksum
          && baseStream instanceof SocketOutputStream
          && blockIn instanceof FileInputStream;
      if (transferTo) {
        FileChannel fileChannel = ((FileInputStream)blockIn).getChannel();
        blockInPosition = fileChannel.position();
        streamForSendChunks = baseStream;
        //��ȡһ�����ܴ�ŵ����chunk������Ĭ����64*1024
        maxChunksPerPacket = numberOfChunks(TRANSFERTO_BUFFER_SIZE);
        
        // Smaller packet size to only hold checksum when doing transferTo
        pktBufSize += checksumSize * maxChunksPerPacket;
      } else {//�������㿽���ķ�ʽ����
    	//��ȡһ�����ܴ�ŵ����chunk������Ĭ��4*1024
        maxChunksPerPacket = Math.max(1,
            numberOfChunks(HdfsConstants.IO_FILE_BUFFER_SIZE));
        // Packet size includes both checksum and data
        pktBufSize += (chunkSize + checksumSize) * maxChunksPerPacket;
      }
      //����������һ�����ݰ��Ļ�����
      ByteBuffer pktBuf = ByteBuffer.allocate(pktBufSize);
      
      while (endOffset > offset && !Thread.currentThread().isInterrupted()) {
    	//����Ԥ��ȡ
        manageOsCache();
        //�������ݰ����ͻ���
        long len = sendPacket(pktBuf, maxChunksPerPacket, streamForSendChunks,
            transferTo, throttler);
        offset += len;
        totalRead += len + (numberOfChunks(len) * checksumSize);
        seqno++;
      }
      // If this thread was interrupted, then it did not send the full block.
      //�����ǰ�̱߳��жϣ����ٷ��͸����������ݿ�
      if (!Thread.currentThread().isInterrupted()) {
        try {
          // send an empty packet to mark the end of the block
          //����һ���յ����ݰ�����־���ݶ�ȡ����
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
      //�ر����ݿ��ļ���У���ļ��Լ����ղ���ϵͳ������
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
    //����������Ԥ��ȡ����
    if ((readaheadLength > 0) && (datanode.readaheadPool != null) &&
          (alwaysReadahead || isLongRead())) {
    	//����Ԥ��ȡ����������Ԥ��ȡ
      curReadahead = datanode.readaheadPool.readaheadStream(
          clientTraceFmt, blockInFd, offset, readaheadLength, Long.MAX_VALUE,
          curReadahead);
    }

    // Drop what we've just read from cache, since we aren't
    // likely to need it again
    //�����ղŴӻ����ȡ�����ݣ���Ϊ������Ҫʹ����Щ������
    if (dropCacheBehindAllReads ||
        (dropCacheBehindLargeReads && isLongRead())) {
      //���Ŀǰ�Ķ�ȡλ�ô�����һ�λ����λ�ã���lastCacheDropOffset��offset������ȫ���ӻ����ж���
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
