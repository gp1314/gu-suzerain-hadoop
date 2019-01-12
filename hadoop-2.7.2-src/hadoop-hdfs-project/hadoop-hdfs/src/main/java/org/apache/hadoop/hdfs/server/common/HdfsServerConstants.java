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
package org.apache.hadoop.hdfs.server.common;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hdfs.server.namenode.MetaRecoveryContext;

import com.google.common.base.Preconditions;
import org.apache.hadoop.util.StringUtils;

/************************************
 * Some handy internal HDFS constants
 *
 ************************************/

@InterfaceAudience.Private
public final class HdfsServerConstants {
  /* Hidden constructor */
  private HdfsServerConstants() { }
  
  /**
   * Type of the node
   */
  static public enum NodeType {
    NAME_NODE,
    DATA_NODE,
    JOURNAL_NODE;
  }

  /** Startup options for rolling upgrade. */
  public static enum RollingUpgradeStartupOption{
    ROLLBACK, DOWNGRADE, STARTED;

    public String getOptionString() {
      return StartupOption.ROLLINGUPGRADE.getName() + " "
          + StringUtils.toLowerCase(name());
    }

    public boolean matches(StartupOption option) {
      return option == StartupOption.ROLLINGUPGRADE
          && option.getRollingUpgradeStartupOption() == this;
    }

    private static final RollingUpgradeStartupOption[] VALUES = values();

    static RollingUpgradeStartupOption fromString(String s) {
      for(RollingUpgradeStartupOption opt : VALUES) {
        if (opt.name().equalsIgnoreCase(s)) {
          return opt;
        }
      }
      throw new IllegalArgumentException("Failed to convert \"" + s
          + "\" to " + RollingUpgradeStartupOption.class.getSimpleName());
    }

    public static String getAllOptionString() {
      final StringBuilder b = new StringBuilder("<");
      for(RollingUpgradeStartupOption opt : VALUES) {
        b.append(StringUtils.toLowerCase(opt.name())).append("|");
      }
      b.setCharAt(b.length() - 1, '>');
      return b.toString();
    }
  }

  /** Startup options */
  static public enum StartupOption{
    FORMAT  ("-format"),
    CLUSTERID ("-clusterid"),
    GENCLUSTERID ("-genclusterid"),
    REGULAR ("-regular"),
    BACKUP  ("-backup"),
    CHECKPOINT("-checkpoint"),
    UPGRADE ("-upgrade"),
    ROLLBACK("-rollback"),
    FINALIZE("-finalize"),
    ROLLINGUPGRADE("-rollingUpgrade"),
    IMPORT  ("-importCheckpoint"),
    BOOTSTRAPSTANDBY("-bootstrapStandby"),
    INITIALIZESHAREDEDITS("-initializeSharedEdits"),
    RECOVER  ("-recover"),
    FORCE("-force"),
    NONINTERACTIVE("-nonInteractive"),
    RENAMERESERVED("-renameReserved"),
    METADATAVERSION("-metadataVersion"),
    UPGRADEONLY("-upgradeOnly"),
    // The -hotswap constant should not be used as a startup option, it is
    // only used for StorageDirectory.analyzeStorage() in hot swap drive scenario.
    // TODO refactor StorageDirectory.analyzeStorage() so that we can do away with
    // this in StartupOption.
    HOTSWAP("-hotswap");

    private static final Pattern ENUM_WITH_ROLLING_UPGRADE_OPTION = Pattern.compile(
        "(\\w+)\\((\\w+)\\)");

    private final String name;
    
    // Used only with format and upgrade options
    private String clusterId = null;
    
    // Used only by rolling upgrade
    private RollingUpgradeStartupOption rollingUpgradeStartupOption;

    // Used only with format option
    private boolean isForceFormat = false;
    private boolean isInteractiveFormat = true;
    
    // Used only with recovery option
    private int force = 0;

    private StartupOption(String arg) {this.name = arg;}
    public String getName() {return name;}
    public NamenodeRole toNodeRole() {
      switch(this) {
      case BACKUP: 
        return NamenodeRole.BACKUP;
      case CHECKPOINT: 
        return NamenodeRole.CHECKPOINT;
      default:
        return NamenodeRole.NAMENODE;
      }
    }
    
    public void setClusterId(String cid) {
      clusterId = cid;
    }

    public String getClusterId() {
      return clusterId;
    }
    
    public void setRollingUpgradeStartupOption(String opt) {
      Preconditions.checkState(this == ROLLINGUPGRADE);
      rollingUpgradeStartupOption = RollingUpgradeStartupOption.fromString(opt);
    }
    
    public RollingUpgradeStartupOption getRollingUpgradeStartupOption() {
      Preconditions.checkState(this == ROLLINGUPGRADE);
      return rollingUpgradeStartupOption;
    }

    public MetaRecoveryContext createRecoveryContext() {
      if (!name.equals(RECOVER.name))
        return null;
      return new MetaRecoveryContext(force);
    }

    public void setForce(int force) {
      this.force = force;
    }
    
    public int getForce() {
      return this.force;
    }
    
    public boolean getForceFormat() {
      return isForceFormat;
    }
    
    public void setForceFormat(boolean force) {
      isForceFormat = force;
    }
    
    public boolean getInteractiveFormat() {
      return isInteractiveFormat;
    }
    
    public void setInteractiveFormat(boolean interactive) {
      isInteractiveFormat = interactive;
    }
    
    @Override
    public String toString() {
      if (this == ROLLINGUPGRADE) {
        return new StringBuilder(super.toString())
            .append("(").append(getRollingUpgradeStartupOption()).append(")")
            .toString();
      }
      return super.toString();
    }

    static public StartupOption getEnum(String value) {
      Matcher matcher = ENUM_WITH_ROLLING_UPGRADE_OPTION.matcher(value);
      if (matcher.matches()) {
        StartupOption option = StartupOption.valueOf(matcher.group(1));
        option.setRollingUpgradeStartupOption(matcher.group(2));
        return option;
      } else {
        return StartupOption.valueOf(value);
      }
    }
  }

  // Timeouts for communicating with DataNode for streaming writes/reads
  public static final int READ_TIMEOUT = 60 * 1000;
  public static final int READ_TIMEOUT_EXTENSION = 5 * 1000;
  public static final int WRITE_TIMEOUT = 8 * 60 * 1000;
  public static final int WRITE_TIMEOUT_EXTENSION = 5 * 1000; //for write pipeline

  /**
   * Defines the NameNode role.
   */
  static public enum NamenodeRole {
    NAMENODE  ("NameNode"),
    BACKUP    ("Backup Node"),
    CHECKPOINT("Checkpoint Node");

    private String description = null;
    private NamenodeRole(String arg) {this.description = arg;}
  
    @Override
    public String toString() {
      return description;
    }
  }

  /**
   * Block replica states, which it can go through while being constructed.
   */
  static public enum ReplicaState {
    /** Replica is finalized. The state when replica is not modified. */
	 /*
	  * datanode上的副本已经完成写操作，不再修改。该状态的副本使用FinalizedRplica类描述
	  * */
    FINALIZED(0),
    /** Replica is being written to. */
    /*
     * 刚刚被创建或者追加写的副本，处于写操作的数据流管道中，正在被写入，
     * 并且已写入的内容还是可以被读取的。RBW状态的副本使用ReplicaBeingWritten类描述
     * */
    RBW(1),
    /** Replica is waiting to be recovered. */
    /*
     * 如果一个datanode挂掉并且重启后，所有RBW状态的副本都将转换为RWR状态，RWR状态的副本不会出现
     * 在数据流管道中，结果就是等着进行租约恢复操作。RWR状态的副本使用ReplicaWaitingToBeRecovered
     * 类描述
     * */
    RWR(2),
    /** Replica is under recovery. */
    /*
     * 租约过期之后发生租约恢复和数据块恢复（Block recovery）时副本所处的状态。RUR状态使用
     * ReplicaUnderRecovery类描述。
     * */
    RUR(3),
    /** Temporary replica: created for replication and relocation only. */
    /*
     * datanode之间传输副本（cluster rebalance）时，正在传输的副本处于TEMPORARY状态。
     * 和RBW状态的副本不同的是，TEMPORARY状态的副本内容是不可读的，如果datanode重启，
     * 会直接删除处于TEMPORARY状态的副本。TEMPORARY状态的副本使用ReplicaInPipeline描述
     * */
    TEMPORARY(4);

    private static final ReplicaState[] cachedValues = ReplicaState.values();

    private final int value;

    private ReplicaState(int v) {
      value = v;
    }

    public int getValue() {
      return value;
    }

    public static ReplicaState getState(int v) {
      return cachedValues[v];
    }

    /** Read from in */
    public static ReplicaState read(DataInput in) throws IOException {
      return cachedValues[in.readByte()];
    }

    /** Write to out */
    public void write(DataOutput out) throws IOException {
      out.writeByte(ordinal());
    }
  }

  /**
   * States, which a block can go through while it is under construction.
   */
  static public enum BlockUCState {
    /**
     * Block construction completed.<br>
     * The block has at least the configured minimal replication number
     * of {@link ReplicaState#FINALIZED} replica(s), and is not going to be
     * modified.
     * NOTE, in some special cases, a block may be forced to COMPLETE state,
     * even if it doesn't have required minimal replications.
     */
	  /*
	   * 数据块的length和gs（时间戳）不再变化，并且Namenode已经收到至少一个datanode报告
	   * 有FINALIZED状态的副本（datanode上的副本状态发生变换时会通过blockReceivedAndDeleted
	   * 方法向namenode报告）。一个COMPLETE状态的数据块会在namenode的内存中保存所有FINALIZED
	   * 副本的位置。只有当HDFS文件的所有数据块都处于COMPLETE状态时，该HDFS文件才能被关闭。
	   * */
    COMPLETE,
    /**
     * The block is under construction.<br>
     * It has been recently allocated for write or append.
     */
    /*
     * 文件被创建或者进行追加写入操作是，正在被写入的数据块处于UNDER_CONSTRUCTION状态
     * 处于该状态的数据块长度和时间戳是可变的，但是处于该状态的数据块对于读取操作是可见的
     * */
    UNDER_CONSTRUCTION,
    /**
     * The block is under recovery.<br>
     * When a file lease expires its last block may not be {@link #COMPLETE}
     * and needs to go through a recovery procedure, 
     * which synchronizes the existing replicas contents.
     */
    /*
     * 如果一个文件的最后一个数据块处于UNDER_CONSTRUCTION状态时，客户端异常退出，该文件的租约超过
     * softLimit过期，该数据块就需要进行租约恢复（lease recovery）和数据块恢复（block recovery）
     * 流程释放租约并关闭文件，那么正在进行租约恢复和数据块恢复流程的数据块就处于UNDER_RECOVERY状态
     * */
    UNDER_RECOVERY,
    /**
     * The block is committed.<br>
     * The client reported that all bytes are written to data-nodes
     * with the given generation stamp and block length, but no 
     * {@link ReplicaState#FINALIZED} 
     * replicas has yet been reported by data-nodes themselves.
     */
    /*
     * 客户端在写文件时，每次请求新的数据块（addBlock RPC请求）或者关闭文件时，都会顺带对一个数据块
     * 进行提交（commmit）操作（上一个数据块从UNDER_CONSTRUCTION状态转换为COMMITTED状态）。
     * COMMITTED状态的数据块表明客户端已经把该数据块的所有数据都发送到了datanode组成的数据流
     * 管道（pipeline）中，并且已经收到了下游的ACK响应，但是namenode还没收到任何一个
     * datanode汇报有FINALIZED副本
     * */
    COMMITTED;
  }
  
  public static final String NAMENODE_LEASE_HOLDER = "HDFS_NameNode";
  public static final long NAMENODE_LEASE_RECHECK_INTERVAL = 2000;

  public static final String CRYPTO_XATTR_ENCRYPTION_ZONE =
      "raw.hdfs.crypto.encryption.zone";
  public static final String CRYPTO_XATTR_FILE_ENCRYPTION_INFO =
      "raw.hdfs.crypto.file.encryption.info";
  public static final String SECURITY_XATTR_UNREADABLE_BY_SUPERUSER =
      "security.hdfs.unreadable.by.superuser";
}
