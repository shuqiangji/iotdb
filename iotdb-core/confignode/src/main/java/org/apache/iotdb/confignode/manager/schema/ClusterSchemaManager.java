/*
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.confignode.manager.schema;

import org.apache.iotdb.common.rpc.thrift.TConsensusGroupType;
import org.apache.iotdb.common.rpc.thrift.TDataNodeLocation;
import org.apache.iotdb.common.rpc.thrift.TSStatus;
import org.apache.iotdb.commons.conf.CommonDescriptor;
import org.apache.iotdb.commons.exception.MetadataException;
import org.apache.iotdb.commons.path.PartialPath;
import org.apache.iotdb.commons.path.PathPatternTree;
import org.apache.iotdb.commons.schema.SchemaConstant;
import org.apache.iotdb.commons.schema.table.TableNodeStatus;
import org.apache.iotdb.commons.schema.table.TreeViewSchema;
import org.apache.iotdb.commons.schema.table.TsTable;
import org.apache.iotdb.commons.schema.table.TsTableInternalRPCUtil;
import org.apache.iotdb.commons.schema.table.column.TsTableColumnCategory;
import org.apache.iotdb.commons.schema.table.column.TsTableColumnSchema;
import org.apache.iotdb.commons.service.metric.MetricService;
import org.apache.iotdb.commons.utils.PathUtils;
import org.apache.iotdb.commons.utils.StatusUtils;
import org.apache.iotdb.confignode.client.async.CnToDnAsyncRequestType;
import org.apache.iotdb.confignode.client.async.CnToDnInternalServiceAsyncRequestManager;
import org.apache.iotdb.confignode.client.async.handlers.DataNodeAsyncRequestContext;
import org.apache.iotdb.confignode.conf.ConfigNodeConfig;
import org.apache.iotdb.confignode.conf.ConfigNodeDescriptor;
import org.apache.iotdb.confignode.consensus.request.ConfigPhysicalPlan;
import org.apache.iotdb.confignode.consensus.request.read.database.CountDatabasePlan;
import org.apache.iotdb.confignode.consensus.request.read.database.GetDatabasePlan;
import org.apache.iotdb.confignode.consensus.request.read.table.DescTable4InformationSchemaPlan;
import org.apache.iotdb.confignode.consensus.request.read.table.DescTablePlan;
import org.apache.iotdb.confignode.consensus.request.read.table.FetchTablePlan;
import org.apache.iotdb.confignode.consensus.request.read.table.ShowTable4InformationSchemaPlan;
import org.apache.iotdb.confignode.consensus.request.read.table.ShowTablePlan;
import org.apache.iotdb.confignode.consensus.request.read.template.GetAllSchemaTemplatePlan;
import org.apache.iotdb.confignode.consensus.request.read.template.GetAllTemplateSetInfoPlan;
import org.apache.iotdb.confignode.consensus.request.read.template.GetPathsSetTemplatePlan;
import org.apache.iotdb.confignode.consensus.request.read.template.GetSchemaTemplatePlan;
import org.apache.iotdb.confignode.consensus.request.read.template.GetTemplateSetInfoPlan;
import org.apache.iotdb.confignode.consensus.request.write.database.AdjustMaxRegionGroupNumPlan;
import org.apache.iotdb.confignode.consensus.request.write.database.DatabaseSchemaPlan;
import org.apache.iotdb.confignode.consensus.request.write.database.DeleteDatabasePlan;
import org.apache.iotdb.confignode.consensus.request.write.database.SetDataReplicationFactorPlan;
import org.apache.iotdb.confignode.consensus.request.write.database.SetSchemaReplicationFactorPlan;
import org.apache.iotdb.confignode.consensus.request.write.database.SetTimePartitionIntervalPlan;
import org.apache.iotdb.confignode.consensus.request.write.pipe.payload.PipeEnrichedPlan;
import org.apache.iotdb.confignode.consensus.request.write.table.SetTableColumnCommentPlan;
import org.apache.iotdb.confignode.consensus.request.write.table.SetTableCommentPlan;
import org.apache.iotdb.confignode.consensus.request.write.table.view.SetViewCommentPlan;
import org.apache.iotdb.confignode.consensus.request.write.template.CreateSchemaTemplatePlan;
import org.apache.iotdb.confignode.consensus.request.write.template.DropSchemaTemplatePlan;
import org.apache.iotdb.confignode.consensus.request.write.template.ExtendSchemaTemplatePlan;
import org.apache.iotdb.confignode.consensus.request.write.template.PreUnsetSchemaTemplatePlan;
import org.apache.iotdb.confignode.consensus.request.write.template.RollbackPreUnsetSchemaTemplatePlan;
import org.apache.iotdb.confignode.consensus.request.write.template.UnsetSchemaTemplatePlan;
import org.apache.iotdb.confignode.consensus.response.database.CountDatabaseResp;
import org.apache.iotdb.confignode.consensus.response.database.DatabaseSchemaResp;
import org.apache.iotdb.confignode.consensus.response.partition.PathInfoResp;
import org.apache.iotdb.confignode.consensus.response.table.DescTable4InformationSchemaResp;
import org.apache.iotdb.confignode.consensus.response.table.DescTableResp;
import org.apache.iotdb.confignode.consensus.response.table.FetchTableResp;
import org.apache.iotdb.confignode.consensus.response.table.ShowTable4InformationSchemaResp;
import org.apache.iotdb.confignode.consensus.response.table.ShowTableResp;
import org.apache.iotdb.confignode.consensus.response.template.AllTemplateSetInfoResp;
import org.apache.iotdb.confignode.consensus.response.template.TemplateInfoResp;
import org.apache.iotdb.confignode.consensus.response.template.TemplateSetInfoResp;
import org.apache.iotdb.confignode.exception.DatabaseNotExistsException;
import org.apache.iotdb.confignode.manager.IManager;
import org.apache.iotdb.confignode.manager.consensus.ConsensusManager;
import org.apache.iotdb.confignode.manager.node.NodeManager;
import org.apache.iotdb.confignode.manager.partition.PartitionManager;
import org.apache.iotdb.confignode.manager.partition.PartitionMetrics;
import org.apache.iotdb.confignode.persistence.schema.ClusterSchemaInfo;
import org.apache.iotdb.confignode.rpc.thrift.TDatabaseInfo;
import org.apache.iotdb.confignode.rpc.thrift.TDatabaseSchema;
import org.apache.iotdb.confignode.rpc.thrift.TDescTable4InformationSchemaResp;
import org.apache.iotdb.confignode.rpc.thrift.TDescTableResp;
import org.apache.iotdb.confignode.rpc.thrift.TFetchTableResp;
import org.apache.iotdb.confignode.rpc.thrift.TGetAllTemplatesResp;
import org.apache.iotdb.confignode.rpc.thrift.TGetPathsSetTemplatesResp;
import org.apache.iotdb.confignode.rpc.thrift.TGetTemplateResp;
import org.apache.iotdb.confignode.rpc.thrift.TShowDatabaseResp;
import org.apache.iotdb.confignode.rpc.thrift.TShowTable4InformationSchemaResp;
import org.apache.iotdb.confignode.rpc.thrift.TShowTableResp;
import org.apache.iotdb.consensus.exception.ConsensusException;
import org.apache.iotdb.db.schemaengine.template.Template;
import org.apache.iotdb.db.schemaengine.template.TemplateInternalRPCUpdateType;
import org.apache.iotdb.db.schemaengine.template.TemplateInternalRPCUtil;
import org.apache.iotdb.db.schemaengine.template.alter.TemplateExtendInfo;
import org.apache.iotdb.db.utils.SchemaUtils;
import org.apache.iotdb.mpp.rpc.thrift.TUpdateTemplateReq;
import org.apache.iotdb.rpc.RpcUtils;
import org.apache.iotdb.rpc.TSStatusCode;

import org.apache.tsfile.annotations.TableModel;
import org.apache.tsfile.file.metadata.IDeviceID;
import org.apache.tsfile.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/** The ClusterSchemaManager Manages cluster schemaengine read and write requests. */
public class ClusterSchemaManager {

  private static final Logger LOGGER = LoggerFactory.getLogger(ClusterSchemaManager.class);

  private static final ConfigNodeConfig CONF = ConfigNodeDescriptor.getInstance().getConf();
  private static final int SCHEMA_REGION_PER_DATA_NODE = CONF.getSchemaRegionPerDataNode();
  private static final int DATA_REGION_PER_DATA_NODE = CONF.getDataRegionPerDataNode();

  private final IManager configManager;
  private final ClusterSchemaInfo clusterSchemaInfo;
  private final ClusterSchemaQuotaStatistics schemaQuotaStatistics;
  private final ReentrantLock createDatabaseLock = new ReentrantLock();

  private static final String CONSENSUS_READ_ERROR =
      "Failed in the read API executing the consensus layer due to: ";

  private static final String CONSENSUS_WRITE_ERROR =
      "Failed in the write API executing the consensus layer due to: ";

  public ClusterSchemaManager(
      final IManager configManager,
      final ClusterSchemaInfo clusterSchemaInfo,
      final ClusterSchemaQuotaStatistics schemaQuotaStatistics) {
    this.configManager = configManager;
    this.clusterSchemaInfo = clusterSchemaInfo;
    this.schemaQuotaStatistics = schemaQuotaStatistics;
  }

  // ======================================================
  // Consensus read/write interfaces
  // ======================================================

  /** Set Database */
  public TSStatus setDatabase(
      final DatabaseSchemaPlan databaseSchemaPlan, final boolean isGeneratedByPipe) {
    TSStatus result;

    final TDatabaseSchema schema = databaseSchemaPlan.getSchema();
    if (getPartitionManager().isDatabasePreDeleted(schema.getName())) {
      return RpcUtils.getStatus(
          TSStatusCode.METADATA_ERROR,
          String.format("Some other task is deleting database %s", schema.getName()));
    }

    createDatabaseLock.lock();
    try {
      clusterSchemaInfo.isDatabaseNameValid(
          schema.getName(), schema.isSetIsTableModel() && schema.isIsTableModel());
      if (!schema.getName().equals(SchemaConstant.SYSTEM_DATABASE)) {
        clusterSchemaInfo.checkDatabaseLimit();
      }
      // Cache DatabaseSchema
      result =
          getConsensusManager()
              .write(
                  isGeneratedByPipe
                      ? new PipeEnrichedPlan(databaseSchemaPlan)
                      : databaseSchemaPlan);
      // set ttl
      if (schema.isSetTTL()) {
        result = configManager.getTTLManager().setTTL(databaseSchemaPlan, isGeneratedByPipe);
      }
      // Bind Database metrics
      PartitionMetrics.bindDatabaseRelatedMetricsWhenUpdate(
          MetricService.getInstance(),
          configManager,
          schema.getName(),
          schema.getDataReplicationFactor(),
          schema.getSchemaReplicationFactor());
      // Adjust the maximum RegionGroup number of each Database
      adjustMaxRegionGroupNum();
    } catch (final ConsensusException e) {
      LOGGER.warn(CONSENSUS_WRITE_ERROR, e);
      result = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      result.setMessage(e.getMessage());
    } catch (final MetadataException metadataException) {
      // Reject if Database already set
      result = new TSStatus(metadataException.getErrorCode());
      result.setMessage(metadataException.getMessage());
    } finally {
      createDatabaseLock.unlock();
    }

    return result;
  }

  /** Alter Database */
  public TSStatus alterDatabase(
      final DatabaseSchemaPlan databaseSchemaPlan, final boolean isGeneratedByPipe) {
    TSStatus result;
    final TDatabaseSchema databaseSchema = databaseSchemaPlan.getSchema();

    if (!isDatabaseExist(databaseSchema.getName())) {
      // Reject if Database doesn't exist
      result = new TSStatus(TSStatusCode.DATABASE_NOT_EXIST.getStatusCode());
      result.setMessage(
          "Failed to alter database. The Database " + databaseSchema.getName() + " doesn't exist.");
      return result;
    }

    if (databaseSchema.isSetMinSchemaRegionGroupNum()) {
      // Validate alter SchemaRegionGroupNum
      final int minSchemaRegionGroupNum =
          getMinRegionGroupNum(databaseSchema.getName(), TConsensusGroupType.SchemaRegion);
      if (databaseSchema.getMinSchemaRegionGroupNum() <= minSchemaRegionGroupNum) {
        result = new TSStatus(TSStatusCode.DATABASE_CONFIG_ERROR.getStatusCode());
        result.setMessage(
            String.format(
                "Failed to alter database. The SchemaRegionGroupNum could only be increased. "
                    + "Current SchemaRegionGroupNum: %d, Alter SchemaRegionGroupNum: %d",
                minSchemaRegionGroupNum, databaseSchema.getMinSchemaRegionGroupNum()));
        return result;
      }
    }
    if (databaseSchema.isSetMinDataRegionGroupNum()) {
      // Validate alter DataRegionGroupNum
      final int minDataRegionGroupNum =
          getMinRegionGroupNum(databaseSchema.getName(), TConsensusGroupType.DataRegion);
      if (databaseSchema.getMinDataRegionGroupNum() <= minDataRegionGroupNum) {
        result = new TSStatus(TSStatusCode.DATABASE_CONFIG_ERROR.getStatusCode());
        result.setMessage(
            String.format(
                "Failed to alter database. The DataRegionGroupNum could only be increased. "
                    + "Current DataRegionGroupNum: %d, Alter DataRegionGroupNum: %d",
                minDataRegionGroupNum, databaseSchema.getMinDataRegionGroupNum()));
        return result;
      }
    }

    // Alter DatabaseSchema
    try {
      result =
          getConsensusManager()
              .write(
                  isGeneratedByPipe
                      ? new PipeEnrichedPlan(databaseSchemaPlan)
                      : databaseSchemaPlan);
      PartitionMetrics.bindDatabaseReplicationFactorMetricsWhenUpdate(
          MetricService.getInstance(),
          databaseSchemaPlan.getSchema().getName(),
          databaseSchemaPlan.getSchema().getDataReplicationFactor(),
          databaseSchemaPlan.getSchema().getSchemaReplicationFactor());
      return result;
    } catch (final ConsensusException e) {
      LOGGER.warn(CONSENSUS_WRITE_ERROR, e);
      result = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      result.setMessage(e.getMessage());
      return result;
    }
  }

  /** Delete DatabaseSchema. */
  public TSStatus deleteDatabase(
      final DeleteDatabasePlan deleteDatabasePlan, final boolean isGeneratedByPipe) {
    TSStatus result;
    try {
      result =
          getConsensusManager()
              .write(
                  isGeneratedByPipe
                      ? new PipeEnrichedPlan(deleteDatabasePlan)
                      : deleteDatabasePlan);
    } catch (final ConsensusException e) {
      LOGGER.warn(CONSENSUS_WRITE_ERROR, e);
      result = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      result.setMessage(e.getMessage());
    }
    if (result.getCode() == TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
      adjustMaxRegionGroupNum();
    }
    return result;
  }

  /**
   * Count Databases by specified path pattern. Notice: including pre-deleted Database.
   *
   * <p>Notice: Only invoke this interface in ConfigManager
   *
   * @return CountDatabaseResp
   */
  public CountDatabaseResp countMatchedDatabases(final CountDatabasePlan countDatabasePlan) {
    try {
      return (CountDatabaseResp) getConsensusManager().read(countDatabasePlan);
    } catch (final ConsensusException e) {
      LOGGER.warn(CONSENSUS_READ_ERROR, e);
      final TSStatus res = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      res.setMessage(e.getMessage());
      final CountDatabaseResp response = new CountDatabaseResp();
      response.setStatus(res);
      return response;
    }
  }

  /**
   * Get DatabaseSchemas by specified path pattern. Notice: including pre-deleted Database
   *
   * <p>Notice: Only invoke this interface in ConfigManager
   *
   * @return DatabaseSchemaResp
   */
  public DatabaseSchemaResp getMatchedDatabaseSchema(GetDatabasePlan getDatabasePlan) {
    DatabaseSchemaResp resp;
    try {
      resp = (DatabaseSchemaResp) getConsensusManager().read(getDatabasePlan);
    } catch (ConsensusException e) {
      LOGGER.warn(CONSENSUS_READ_ERROR, e);
      TSStatus res = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      res.setMessage(e.getMessage());
      resp = new DatabaseSchemaResp();
      resp.setStatus(res);
    }
    List<String> preDeletedDatabaseList = new ArrayList<>();
    for (String database : resp.getSchemaMap().keySet()) {
      if (getPartitionManager().isDatabasePreDeleted(database)) {
        preDeletedDatabaseList.add(database);
      }
    }
    for (String preDeletedDatabase : preDeletedDatabaseList) {
      resp.getSchemaMap().remove(preDeletedDatabase);
    }
    return resp;
  }

  /** Only used in cluster tool show Databases. */
  public TShowDatabaseResp showDatabase(final GetDatabasePlan getDatabasePlan) {
    DatabaseSchemaResp databaseSchemaResp;
    try {
      databaseSchemaResp = (DatabaseSchemaResp) getConsensusManager().read(getDatabasePlan);
    } catch (final ConsensusException e) {
      LOGGER.warn(CONSENSUS_READ_ERROR, e);
      TSStatus res = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      res.setMessage(e.getMessage());
      databaseSchemaResp = new DatabaseSchemaResp();
      databaseSchemaResp.setStatus(res);
    }
    if (databaseSchemaResp.getStatus().getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
      // Return immediately if some Database doesn't exist
      return new TShowDatabaseResp().setStatus(databaseSchemaResp.getStatus());
    }

    final Map<String, TDatabaseInfo> infoMap = new ConcurrentHashMap<>();
    for (final TDatabaseSchema databaseSchema : databaseSchemaResp.getSchemaMap().values()) {
      final String database = databaseSchema.getName();
      final TDatabaseInfo databaseInfo = new TDatabaseInfo();
      databaseInfo.setName(database);

      databaseInfo.setTTL(databaseSchema.isSetTTL() ? databaseSchema.getTTL() : Long.MAX_VALUE);
      databaseInfo.setSchemaReplicationFactor(databaseSchema.getSchemaReplicationFactor());
      databaseInfo.setDataReplicationFactor(databaseSchema.getDataReplicationFactor());
      databaseInfo.setTimePartitionOrigin(databaseSchema.getTimePartitionOrigin());
      databaseInfo.setTimePartitionInterval(databaseSchema.getTimePartitionInterval());
      databaseInfo.setMinSchemaRegionNum(
          getMinRegionGroupNum(database, TConsensusGroupType.SchemaRegion));
      databaseInfo.setMaxSchemaRegionNum(
          getMaxRegionGroupNum(database, TConsensusGroupType.SchemaRegion));
      databaseInfo.setMinDataRegionNum(
          getMinRegionGroupNum(database, TConsensusGroupType.DataRegion));
      databaseInfo.setMaxDataRegionNum(
          getMaxRegionGroupNum(database, TConsensusGroupType.DataRegion));

      try {
        databaseInfo.setSchemaRegionNum(
            getPartitionManager().getRegionGroupCount(database, TConsensusGroupType.SchemaRegion));
        databaseInfo.setDataRegionNum(
            getPartitionManager().getRegionGroupCount(database, TConsensusGroupType.DataRegion));
      } catch (final DatabaseNotExistsException e) {
        // Skip pre-deleted Database
        LOGGER.warn(
            "The Database: {} doesn't exist. Maybe it has been pre-deleted.",
            databaseSchema.getName());
        continue;
      }

      infoMap.put(database, databaseInfo);
    }

    return new TShowDatabaseResp().setDatabaseInfoMap(infoMap).setStatus(StatusUtils.OK);
  }

  public Map<String, Long> getTTLInfoForUpgrading() {
    final List<String> databases = getDatabaseNames(false);
    final Map<String, Long> infoMap = new ConcurrentHashMap<>();
    for (final String database : databases) {
      try {
        final TDatabaseSchema databaseSchema = getDatabaseSchemaByName(database);
        final long ttl = databaseSchema.isSetTTL() ? databaseSchema.getTTL() : -1;
        if (ttl < 0 || ttl == Long.MAX_VALUE) {
          continue;
        }
        infoMap.put(database, ttl);
      } catch (final DatabaseNotExistsException e) {
        LOGGER.warn("Database: {} doesn't exist", databases, e);
      }
    }
    return infoMap;
  }

  public TSStatus setSchemaReplicationFactor(
      SetSchemaReplicationFactorPlan setSchemaReplicationFactorPlan) {
    // TODO: Inform DataNodes
    try {
      return getConsensusManager().write(setSchemaReplicationFactorPlan);
    } catch (ConsensusException e) {
      LOGGER.warn(CONSENSUS_WRITE_ERROR, e);
      TSStatus result = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      result.setMessage(e.getMessage());
      return result;
    }
  }

  public TSStatus setDataReplicationFactor(
      SetDataReplicationFactorPlan setDataReplicationFactorPlan) {
    // TODO: Inform DataNodes
    try {
      return getConsensusManager().write(setDataReplicationFactorPlan);
    } catch (ConsensusException e) {
      LOGGER.warn(CONSENSUS_WRITE_ERROR, e);
      TSStatus result = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      result.setMessage(e.getMessage());
      return result;
    }
  }

  public TSStatus setTimePartitionInterval(
      SetTimePartitionIntervalPlan setTimePartitionIntervalPlan) {
    // TODO: Inform DataNodes
    try {
      return getConsensusManager().write(setTimePartitionIntervalPlan);
    } catch (ConsensusException e) {
      LOGGER.warn(CONSENSUS_WRITE_ERROR, e);
      TSStatus result = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      result.setMessage(e.getMessage());
      return result;
    }
  }

  /**
   * Only leader use this interface. Adjust the maxSchemaRegionGroupNum and maxDataRegionGroupNum of
   * each Database based on existing cluster resources
   */
  public synchronized void adjustMaxRegionGroupNum() {
    // Get all DatabaseSchemas
    final Map<String, TDatabaseSchema> databaseSchemaMap =
        getMatchedDatabaseSchemasByName(getDatabaseNames(null), null);
    if (databaseSchemaMap.isEmpty()) {
      // Skip when there are no Databases
      return;
    }

    final int dataNodeNum = getNodeManager().getRegisteredDataNodeCount();
    final int totalCpuCoreNum = getNodeManager().getDataNodeCpuCoreCount();
    int databaseNum = databaseSchemaMap.size();

    for (final TDatabaseSchema databaseSchema : databaseSchemaMap.values()) {
      if (!isDatabaseExist(databaseSchema.getName())
          || databaseSchema.getName().equals(SchemaConstant.SYSTEM_DATABASE)) {
        // filter the pre deleted database and the system database
        databaseNum--;
      }
    }

    final AdjustMaxRegionGroupNumPlan adjustMaxRegionGroupNumPlan =
        new AdjustMaxRegionGroupNumPlan();
    for (final TDatabaseSchema databaseSchema : databaseSchemaMap.values()) {
      if (databaseSchema.getName().equals(SchemaConstant.SYSTEM_DATABASE)) {
        // filter the system database
        continue;
      }

      // Adjust maxSchemaRegionGroupNum for each Database.
      // All Databases share the DataNodes equally.
      // The allocated SchemaRegionGroups will not be shrunk.
      final int allocatedSchemaRegionGroupCount;
      try {
        allocatedSchemaRegionGroupCount =
            getPartitionManager()
                .getRegionGroupCount(databaseSchema.getName(), TConsensusGroupType.SchemaRegion);
      } catch (final DatabaseNotExistsException e) {
        // ignore the pre deleted database
        continue;
      }

      final int maxSchemaRegionGroupNum =
          calcMaxRegionGroupNum(
              databaseSchema.getMinSchemaRegionGroupNum(),
              SCHEMA_REGION_PER_DATA_NODE,
              dataNodeNum,
              databaseNum,
              databaseSchema.getSchemaReplicationFactor(),
              allocatedSchemaRegionGroupCount);
      LOGGER.info(
          "[AdjustRegionGroupNum] The maximum number of SchemaRegionGroups for Database: {} is adjusted to: {}",
          databaseSchema.getName(),
          maxSchemaRegionGroupNum);

      // Adjust maxDataRegionGroupNum for each Database.
      // All Databases share the DataNodes equally.
      // The allocated DataRegionGroups will not be shrunk.
      final int allocatedDataRegionGroupCount;
      try {
        allocatedDataRegionGroupCount =
            getPartitionManager()
                .getRegionGroupCount(databaseSchema.getName(), TConsensusGroupType.DataRegion);
      } catch (final DatabaseNotExistsException e) {
        // ignore the pre deleted database
        continue;
      }

      final int maxDataRegionGroupNum =
          calcMaxRegionGroupNum(
              databaseSchema.getMinDataRegionGroupNum(),
              DATA_REGION_PER_DATA_NODE == 0
                  ? CONF.getDataRegionPerDataNodeProportion()
                  : DATA_REGION_PER_DATA_NODE,
              DATA_REGION_PER_DATA_NODE == 0 ? totalCpuCoreNum : dataNodeNum,
              databaseNum,
              databaseSchema.getDataReplicationFactor(),
              allocatedDataRegionGroupCount);
      LOGGER.info(
          "[AdjustRegionGroupNum] The maximum number of DataRegionGroups for Database: {} is adjusted to: {}",
          databaseSchema.getName(),
          maxDataRegionGroupNum);

      adjustMaxRegionGroupNumPlan.putEntry(
          databaseSchema.getName(), new Pair<>(maxSchemaRegionGroupNum, maxDataRegionGroupNum));
    }
    try {
      getConsensusManager().write(adjustMaxRegionGroupNumPlan);
    } catch (final ConsensusException e) {
      LOGGER.warn(CONSENSUS_WRITE_ERROR, e);
    }
  }

  public static int calcMaxRegionGroupNum(
      int minRegionGroupNum,
      double resourceWeight,
      int resource,
      int databaseNum,
      int replicationFactor,
      int allocatedRegionGroupCount) {
    return Math.max(
        // The maxRegionGroupNum should be great or equal to the minRegionGroupNum
        minRegionGroupNum,
        Math.max(
            (int)
                // Use Math.ceil here to ensure that the maxRegionGroupNum
                // will be increased as long as the number of cluster DataNodes is increased
                Math.ceil(
                    // The maxRegionGroupNum of the current Database is expected to be:
                    // (resourceWeight * resource) / (createdDatabaseNum * replicationFactor)
                    resourceWeight * resource / (databaseNum * replicationFactor)),
            // The maxRegionGroupNum should be great or equal to the allocatedRegionGroupCount
            allocatedRegionGroupCount));
  }

  // ======================================================
  // Leader scheduling interfaces
  // ======================================================

  /**
   * Check if the specified Database exists
   *
   * @param database The specified Database
   * @return True if the DatabaseSchema exists and the Database is not pre-deleted
   */
  public boolean isDatabaseExist(final String database) {
    return getPartitionManager().isDatabaseExist(database);
  }

  /**
   * Only leader use this interface. Get all the databases' names under the specific model. For some
   * common services on configNode, the input shall be {@code null} to extract the databases from
   * both models.
   *
   * @param isTableModel {@link Boolean#TRUE} is only extract table model database, {@link
   *     Boolean#FALSE} is only extract tree model database, {@code null} is extract both.
   * @return List{@literal <}DatabaseName{@literal >}, all Databases' name
   */
  public List<String> getDatabaseNames(final Boolean isTableModel) {
    return clusterSchemaInfo.getDatabaseNames(isTableModel).stream()
        .filter(this::isDatabaseExist)
        .collect(Collectors.toList());
  }

  /**
   * Only leader use this interface. Get the specified Database's schemaengine
   *
   * @param database DatabaseName
   * @return The specific DatabaseSchema
   * @throws DatabaseNotExistsException When the specific Database doesn't exist
   */
  public TDatabaseSchema getDatabaseSchemaByName(final String database)
      throws DatabaseNotExistsException {
    if (!isDatabaseExist(database)) {
      throw new DatabaseNotExistsException(database);
    }
    return clusterSchemaInfo.getMatchedDatabaseSchemaByName(database);
  }

  /**
   * Only leader use this interface.
   *
   * @return The DatabaseName of the specified Device. Empty String if not exists.
   */
  public String getDatabaseNameByDevice(final IDeviceID deviceID) {
    final List<String> databases = getDatabaseNames(null);
    for (final String database : databases) {
      if (PathUtils.isStartWith(deviceID, database)) {
        return database;
      }
    }
    return "";
  }

  /**
   * Only leader use this interface. Get the specified Databases' schemaengine
   *
   * @param rawPathList List<DatabaseName>
   * @return the matched DatabaseSchemas
   */
  public Map<String, TDatabaseSchema> getMatchedDatabaseSchemasByName(
      final List<String> rawPathList, final Boolean isTableModel) {
    final Map<String, TDatabaseSchema> result = new ConcurrentHashMap<>();
    clusterSchemaInfo
        .getMatchedDatabaseSchemasByName(rawPathList, isTableModel)
        .forEach(
            (database, databaseSchema) -> {
              if (isDatabaseExist(databaseSchema.getName())) {
                result.put(database, databaseSchema);
              }
            });
    return result;
  }

  /**
   * Only leader use this interface. Get the specified Databases' schemaengine
   *
   * @param prefix prefix full path
   * @return the matched DatabaseSchemas
   */
  public Map<String, TDatabaseSchema> getMatchedDatabaseSchemasByPrefix(PartialPath prefix) {
    Map<String, TDatabaseSchema> result = new ConcurrentHashMap<>();
    clusterSchemaInfo
        .getMatchedDatabaseSchemasByPrefix(prefix)
        .forEach(
            (database, databaseSchema) -> {
              if (isDatabaseExist(database)) {
                result.put(database, databaseSchema);
              }
            });
    return result;
  }

  /**
   * Only leader use this interface. Get the replication factor of specified Database
   *
   * @param database DatabaseName
   * @param consensusGroupType SchemaRegion or DataRegion
   * @return SchemaReplicationFactor or DataReplicationFactor
   * @throws DatabaseNotExistsException When the specific Database doesn't exist
   */
  public int getReplicationFactor(String database, TConsensusGroupType consensusGroupType)
      throws DatabaseNotExistsException {
    TDatabaseSchema databaseSchema = getDatabaseSchemaByName(database);
    return TConsensusGroupType.SchemaRegion.equals(consensusGroupType)
        ? databaseSchema.getSchemaReplicationFactor()
        : databaseSchema.getDataReplicationFactor();
  }

  /**
   * Only leader use this interface. Get the maxRegionGroupNum of specified Database.
   *
   * @param database DatabaseName
   * @param consensusGroupType SchemaRegion or DataRegion
   * @return minSchemaRegionGroupNum or minDataRegionGroupNum
   */
  public int getMinRegionGroupNum(String database, TConsensusGroupType consensusGroupType) {
    return clusterSchemaInfo.getMinRegionGroupNum(database, consensusGroupType);
  }

  /**
   * Only leader use this interface. Get the maxRegionGroupNum of specified Database.
   *
   * @param database DatabaseName
   * @param consensusGroupType SchemaRegion or DataRegion
   * @return maxSchemaRegionGroupNum or maxDataRegionGroupNum
   */
  public int getMaxRegionGroupNum(String database, TConsensusGroupType consensusGroupType) {
    return clusterSchemaInfo.getMaxRegionGroupNum(database, consensusGroupType);
  }

  /**
   * create schemaengine template
   *
   * @param createSchemaTemplatePlan CreateSchemaTemplatePlan
   * @return TSStatus
   */
  public TSStatus createTemplate(CreateSchemaTemplatePlan createSchemaTemplatePlan) {
    try {
      return getConsensusManager().write(createSchemaTemplatePlan);
    } catch (ConsensusException e) {
      LOGGER.warn(CONSENSUS_WRITE_ERROR, e);
      TSStatus res = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      res.setMessage(e.getMessage());
      return res;
    }
  }

  /**
   * show schemaengine template
   *
   * @return TGetAllTemplatesResp
   */
  public TGetAllTemplatesResp getAllTemplates() {
    GetAllSchemaTemplatePlan getAllSchemaTemplatePlan = new GetAllSchemaTemplatePlan();
    TemplateInfoResp templateResp;
    try {
      templateResp = (TemplateInfoResp) getConsensusManager().read(getAllSchemaTemplatePlan);
    } catch (ConsensusException e) {
      LOGGER.warn(CONSENSUS_READ_ERROR, e);
      TSStatus res = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      res.setMessage(e.getMessage());
      templateResp = new TemplateInfoResp();
      templateResp.setStatus(res);
    }
    TGetAllTemplatesResp resp = new TGetAllTemplatesResp();
    resp.setStatus(templateResp.getStatus());
    if (resp.getStatus().getCode() == TSStatusCode.SUCCESS_STATUS.getStatusCode()
        && templateResp.getTemplateList() != null) {
      List<ByteBuffer> list = new ArrayList<>();
      templateResp.getTemplateList().forEach(template -> list.add(template.serialize()));
      resp.setTemplateList(list);
    }
    return resp;
  }

  /** show nodes in schemaengine template */
  public TGetTemplateResp getTemplate(String req) {
    GetSchemaTemplatePlan getSchemaTemplatePlan = new GetSchemaTemplatePlan(req);
    TemplateInfoResp templateResp;
    try {
      templateResp = (TemplateInfoResp) getConsensusManager().read(getSchemaTemplatePlan);
    } catch (ConsensusException e) {
      LOGGER.warn(CONSENSUS_READ_ERROR, e);
      TSStatus res = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      res.setMessage(e.getMessage());
      templateResp = new TemplateInfoResp();
      templateResp.setStatus(res);
    }
    TGetTemplateResp resp = new TGetTemplateResp();
    if (templateResp.getStatus().getCode() == TSStatusCode.SUCCESS_STATUS.getStatusCode()
        && templateResp.getTemplateList() != null
        && !templateResp.getTemplateList().isEmpty()) {
      ByteBuffer byteBuffer = templateResp.getTemplateList().get(0).serialize();
      resp.setTemplate(byteBuffer);
    }
    resp.setStatus(templateResp.getStatus());
    return resp;
  }

  /** Get template by id. Only leader uses this interface. */
  public Template getTemplate(int id) throws MetadataException {
    return clusterSchemaInfo.getTemplate(id);
  }

  /** show path set template xx */
  public TGetPathsSetTemplatesResp getPathsSetTemplate(String templateName, PathPatternTree scope) {
    GetPathsSetTemplatePlan getPathsSetTemplatePlan =
        new GetPathsSetTemplatePlan(templateName, scope);
    PathInfoResp pathInfoResp;
    try {
      pathInfoResp = (PathInfoResp) getConsensusManager().read(getPathsSetTemplatePlan);
    } catch (ConsensusException e) {
      LOGGER.warn(CONSENSUS_READ_ERROR, e);
      TSStatus res = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      res.setMessage(e.getMessage());
      pathInfoResp = new PathInfoResp();
      pathInfoResp.setStatus(res);
    }
    if (pathInfoResp.getStatus().getCode() == TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
      TGetPathsSetTemplatesResp resp = new TGetPathsSetTemplatesResp();
      resp.setStatus(RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS));
      resp.setPathList(pathInfoResp.getPathList());
      return resp;
    } else {
      return new TGetPathsSetTemplatesResp(pathInfoResp.getStatus());
    }
  }

  public static TSStatus enrichDatabaseSchemaWithDefaultProperties(
      final TDatabaseSchema databaseSchema) {
    TSStatus errorResp = null;
    final boolean isSystemDatabase =
        databaseSchema.getName().equals(SchemaConstant.SYSTEM_DATABASE);

    if (databaseSchema.getTTL() < 0) {
      errorResp =
          new TSStatus(TSStatusCode.DATABASE_CONFIG_ERROR.getStatusCode())
              .setMessage("Failed to create database. The TTL should be non-negative.");
    }

    if (!databaseSchema.isSetSchemaReplicationFactor()) {
      databaseSchema.setSchemaReplicationFactor(
          ConfigNodeDescriptor.getInstance().getConf().getSchemaReplicationFactor());
    } else if (databaseSchema.getSchemaReplicationFactor() <= 0) {
      errorResp =
          new TSStatus(TSStatusCode.DATABASE_CONFIG_ERROR.getStatusCode())
              .setMessage(
                  "Failed to create database. The schemaReplicationFactor should be positive.");
    }

    if (!databaseSchema.isSetDataReplicationFactor()) {
      databaseSchema.setDataReplicationFactor(
          ConfigNodeDescriptor.getInstance().getConf().getDataReplicationFactor());
    } else if (databaseSchema.getDataReplicationFactor() <= 0) {
      errorResp =
          new TSStatus(TSStatusCode.DATABASE_CONFIG_ERROR.getStatusCode())
              .setMessage(
                  "Failed to create database. The dataReplicationFactor should be positive.");
    }

    if (!databaseSchema.isSetTimePartitionOrigin()) {
      databaseSchema.setTimePartitionOrigin(
          CommonDescriptor.getInstance().getConfig().getTimePartitionOrigin());
    } else if (databaseSchema.getTimePartitionOrigin() < 0) {
      errorResp =
          new TSStatus(TSStatusCode.DATABASE_CONFIG_ERROR.getStatusCode())
              .setMessage(
                  "Failed to create database. The timePartitionOrigin should be non-negative.");
    }

    if (!databaseSchema.isSetTimePartitionInterval()) {
      databaseSchema.setTimePartitionInterval(
          CommonDescriptor.getInstance().getConfig().getTimePartitionInterval());
    } else if (databaseSchema.getTimePartitionInterval() <= 0) {
      errorResp =
          new TSStatus(TSStatusCode.DATABASE_CONFIG_ERROR.getStatusCode())
              .setMessage(
                  "Failed to create database. The timePartitionInterval should be positive.");
    }

    if (isSystemDatabase) {
      databaseSchema.setMinSchemaRegionGroupNum(1);
    } else if (!databaseSchema.isSetMinSchemaRegionGroupNum()) {
      databaseSchema.setMinSchemaRegionGroupNum(
          ConfigNodeDescriptor.getInstance().getConf().getDefaultSchemaRegionGroupNumPerDatabase());
    } else if (databaseSchema.getMinSchemaRegionGroupNum() <= 0) {
      errorResp =
          new TSStatus(TSStatusCode.DATABASE_CONFIG_ERROR.getStatusCode())
              .setMessage(
                  "Failed to create database. The schemaRegionGroupNum should be positive.");
    }

    if (isSystemDatabase) {
      databaseSchema.setMinDataRegionGroupNum(1);
    } else if (!databaseSchema.isSetMinDataRegionGroupNum()) {
      databaseSchema.setMinDataRegionGroupNum(
          ConfigNodeDescriptor.getInstance().getConf().getDefaultDataRegionGroupNumPerDatabase());
    } else if (databaseSchema.getMinDataRegionGroupNum() <= 0) {
      errorResp =
          new TSStatus(TSStatusCode.DATABASE_CONFIG_ERROR.getStatusCode())
              .setMessage("Failed to create database. The dataRegionGroupNum should be positive.");
    }

    if (errorResp != null) {
      LOGGER.warn("Execute SetDatabase: {} with result: {}", databaseSchema, errorResp);
      return errorResp;
    }

    // The maxRegionGroupNum is equal to the minRegionGroupNum when initialize
    databaseSchema.setMaxSchemaRegionGroupNum(databaseSchema.getMinSchemaRegionGroupNum());
    databaseSchema.setMaxDataRegionGroupNum(databaseSchema.getMinDataRegionGroupNum());

    return StatusUtils.OK;
  }

  /**
   * get all template set and pre-set info to sync to a registering dataNodes, the pre unset
   * template info won't be taken
   */
  public byte[] getAllTemplateSetInfo() {
    try {
      AllTemplateSetInfoResp resp =
          (AllTemplateSetInfoResp) getConsensusManager().read(new GetAllTemplateSetInfoPlan());
      return resp.getTemplateInfo();
    } catch (ConsensusException e) {
      LOGGER.warn(CONSENSUS_READ_ERROR, e);
      return new byte[0];
    }
  }

  public TemplateSetInfoResp getTemplateSetInfo(List<PartialPath> patternList) {
    try {
      return (TemplateSetInfoResp)
          getConsensusManager().read(new GetTemplateSetInfoPlan(patternList));
    } catch (ConsensusException e) {
      LOGGER.warn(CONSENSUS_READ_ERROR, e);
      TSStatus res = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      res.setMessage(e.getMessage());
      TemplateSetInfoResp response = new TemplateSetInfoResp();
      response.setStatus(res);
      return response;
    }
  }

  public Pair<TSStatus, Template> checkIsTemplateSetOnPath(String templateName, String path) {
    GetSchemaTemplatePlan getSchemaTemplatePlan = new GetSchemaTemplatePlan(templateName);
    TemplateInfoResp templateResp;
    try {
      templateResp = (TemplateInfoResp) getConsensusManager().read(getSchemaTemplatePlan);
    } catch (ConsensusException e) {
      LOGGER.warn(CONSENSUS_READ_ERROR, e);
      TSStatus res = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      res.setMessage(e.getMessage());
      templateResp = new TemplateInfoResp();
      templateResp.setStatus(res);
    }
    if (templateResp.getStatus().getCode() == TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
      if (templateResp.getTemplateList() == null || templateResp.getTemplateList().isEmpty()) {
        return new Pair<>(
            RpcUtils.getStatus(
                TSStatusCode.UNDEFINED_TEMPLATE.getStatusCode(),
                String.format("Undefined template name: %s", templateName)),
            null);
      }
    } else {
      return new Pair<>(templateResp.getStatus(), null);
    }

    GetPathsSetTemplatePlan getPathsSetTemplatePlan =
        new GetPathsSetTemplatePlan(templateName, SchemaConstant.ALL_MATCH_SCOPE);
    PathInfoResp pathInfoResp;
    try {
      pathInfoResp = (PathInfoResp) getConsensusManager().read(getPathsSetTemplatePlan);
    } catch (ConsensusException e) {
      LOGGER.warn(CONSENSUS_READ_ERROR, e);
      TSStatus res = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      res.setMessage(e.getMessage());
      pathInfoResp = new PathInfoResp();
      pathInfoResp.setStatus(res);
    }
    if (pathInfoResp.getStatus().getCode() == TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
      List<String> templateSetPathList = pathInfoResp.getPathList();
      if (templateSetPathList == null
          || templateSetPathList.isEmpty()
          || !pathInfoResp.getPathList().contains(path)) {
        return new Pair<>(
            RpcUtils.getStatus(
                TSStatusCode.TEMPLATE_NOT_SET.getStatusCode(),
                String.format("No template on %s", path)),
            null);
      } else {
        return new Pair<>(templateResp.getStatus(), templateResp.getTemplateList().get(0));
      }
    } else {
      return new Pair<>(pathInfoResp.getStatus(), null);
    }
  }

  public TSStatus preUnsetSchemaTemplate(int templateId, PartialPath path) {
    try {
      return getConsensusManager().write(new PreUnsetSchemaTemplatePlan(templateId, path));
    } catch (ConsensusException e) {
      LOGGER.warn(CONSENSUS_WRITE_ERROR, e);
      TSStatus result = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      result.setMessage(e.getMessage());
      return result;
    }
  }

  public TSStatus rollbackPreUnsetSchemaTemplate(int templateId, PartialPath path) {
    try {
      return getConsensusManager().write(new RollbackPreUnsetSchemaTemplatePlan(templateId, path));
    } catch (ConsensusException e) {
      LOGGER.warn(CONSENSUS_WRITE_ERROR, e);
      TSStatus result = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      result.setMessage(e.getMessage());
      return result;
    }
  }

  public TSStatus unsetSchemaTemplateInBlackList(
      int templateId, PartialPath path, boolean isGeneratedByPipe) {
    try {
      return getConsensusManager()
          .write(
              isGeneratedByPipe
                  ? new PipeEnrichedPlan(new UnsetSchemaTemplatePlan(templateId, path))
                  : new UnsetSchemaTemplatePlan(templateId, path));
    } catch (ConsensusException e) {
      LOGGER.warn(CONSENSUS_WRITE_ERROR, e);
      TSStatus result = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      result.setMessage(e.getMessage());
      return result;
    }
  }

  public synchronized TSStatus dropSchemaTemplate(String templateName) {
    // check template existence
    GetSchemaTemplatePlan getSchemaTemplatePlan = new GetSchemaTemplatePlan(templateName);
    TemplateInfoResp templateInfoResp;
    try {
      templateInfoResp = (TemplateInfoResp) getConsensusManager().read(getSchemaTemplatePlan);
    } catch (ConsensusException e) {
      LOGGER.warn(CONSENSUS_READ_ERROR, e);
      TSStatus res = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      res.setMessage(e.getMessage());
      templateInfoResp = new TemplateInfoResp();
      templateInfoResp.setStatus(res);
    }
    if (templateInfoResp.getStatus().getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
      return templateInfoResp.getStatus();
    } else if (templateInfoResp.getTemplateList() == null
        || templateInfoResp.getTemplateList().isEmpty()) {
      return RpcUtils.getStatus(
          TSStatusCode.UNDEFINED_TEMPLATE.getStatusCode(),
          String.format("Undefined template name: %s", templateName));
    }

    // check is template set on some path, block all template set operation
    GetPathsSetTemplatePlan getPathsSetTemplatePlan =
        new GetPathsSetTemplatePlan(templateName, SchemaConstant.ALL_MATCH_SCOPE);
    PathInfoResp pathInfoResp;
    try {
      pathInfoResp = (PathInfoResp) getConsensusManager().read(getPathsSetTemplatePlan);
    } catch (ConsensusException e) {
      LOGGER.warn(CONSENSUS_READ_ERROR, e);
      TSStatus res = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      res.setMessage(e.getMessage());
      pathInfoResp = new PathInfoResp();
      pathInfoResp.setStatus(res);
    }
    if (pathInfoResp.getStatus().getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
      return pathInfoResp.getStatus();
    } else if (pathInfoResp.getPathList() != null && !pathInfoResp.getPathList().isEmpty()) {
      return RpcUtils.getStatus(
          TSStatusCode.METADATA_ERROR.getStatusCode(),
          String.format(
              "Template [%s] has been set on MTree, cannot be dropped now.", templateName));
    }

    // execute drop template
    try {
      return getConsensusManager().write(new DropSchemaTemplatePlan(templateName));
    } catch (ConsensusException e) {
      LOGGER.warn(CONSENSUS_WRITE_ERROR, e);
      TSStatus result = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      result.setMessage(e.getMessage());
      return result;
    }
  }

  public synchronized TSStatus extendSchemaTemplate(
      final TemplateExtendInfo templateExtendInfo, final boolean isGeneratedByPipe) {
    if (templateExtendInfo.getEncodings() != null) {
      for (int i = 0; i < templateExtendInfo.getDataTypes().size(); i++) {
        try {
          SchemaUtils.checkDataTypeWithEncoding(
              templateExtendInfo.getDataTypes().get(i), templateExtendInfo.getEncodings().get(i));
        } catch (final MetadataException e) {
          return RpcUtils.getStatus(e.getErrorCode(), e.getMessage());
        }
      }
    }

    final TemplateInfoResp resp =
        clusterSchemaInfo.getTemplate(
            new GetSchemaTemplatePlan(templateExtendInfo.getTemplateName()));
    if (resp.getStatus().getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
      return resp.getStatus();
    }

    Template template = resp.getTemplateList().get(0);
    List<String> intersectionMeasurements =
        templateExtendInfo.updateAsDifferenceAndGetIntersection(template.getSchemaMap().keySet());

    if (templateExtendInfo.isEmpty()) {
      if (intersectionMeasurements.isEmpty()) {
        return RpcUtils.SUCCESS_STATUS;
      } else {
        return RpcUtils.getStatus(
            TSStatusCode.MEASUREMENT_ALREADY_EXISTS_IN_TEMPLATE,
            String.format(
                "Measurement %s already exist in schemaengine template %s",
                intersectionMeasurements, template.getName()));
      }
    }

    ExtendSchemaTemplatePlan extendSchemaTemplatePlan =
        new ExtendSchemaTemplatePlan(templateExtendInfo);
    TSStatus status;
    try {
      status =
          getConsensusManager()
              .write(
                  isGeneratedByPipe
                      ? new PipeEnrichedPlan(extendSchemaTemplatePlan)
                      : extendSchemaTemplatePlan);
    } catch (ConsensusException e) {
      LOGGER.warn(CONSENSUS_WRITE_ERROR, e);
      status = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      status.setMessage(e.getMessage());
    }
    if (status.getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
      return status;
    }

    template =
        clusterSchemaInfo
            .getTemplate(new GetSchemaTemplatePlan(templateExtendInfo.getTemplateName()))
            .getTemplateList()
            .get(0);

    TUpdateTemplateReq updateTemplateReq = new TUpdateTemplateReq();
    updateTemplateReq.setType(TemplateInternalRPCUpdateType.UPDATE_TEMPLATE_INFO.toByte());
    updateTemplateReq.setTemplateInfo(
        TemplateInternalRPCUtil.generateUpdateTemplateInfoBytes(template));

    Map<Integer, TDataNodeLocation> dataNodeLocationMap =
        configManager.getNodeManager().getRegisteredDataNodeLocations();

    DataNodeAsyncRequestContext<TUpdateTemplateReq, TSStatus> clientHandler =
        new DataNodeAsyncRequestContext<>(
            CnToDnAsyncRequestType.UPDATE_TEMPLATE, updateTemplateReq, dataNodeLocationMap);
    CnToDnInternalServiceAsyncRequestManager.getInstance().sendAsyncRequestWithRetry(clientHandler);
    Map<Integer, TSStatus> statusMap = clientHandler.getResponseMap();
    for (Map.Entry<Integer, TSStatus> entry : statusMap.entrySet()) {
      if (entry.getValue().getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
        LOGGER.warn(
            "Failed to sync template {} extension info to DataNode {}",
            template.getName(),
            dataNodeLocationMap.get(entry.getKey()));
        return RpcUtils.getStatus(
            TSStatusCode.EXECUTE_STATEMENT_ERROR,
            String.format(
                "Failed to sync template %s extension info to DataNode %s",
                template.getName(), dataNodeLocationMap.get(entry.getKey())));
      }
    }

    if (intersectionMeasurements.isEmpty()) {
      return RpcUtils.SUCCESS_STATUS;
    } else {
      return RpcUtils.getStatus(
          TSStatusCode.MEASUREMENT_ALREADY_EXISTS_IN_TEMPLATE,
          String.format(
              "Measurement %s already exist in schemaengine template %s",
              intersectionMeasurements, template.getName()));
    }
  }

  // region table management

  public TShowTableResp showTables(final String database, final boolean isDetails) {
    try {
      return ((ShowTableResp)
              configManager.getConsensusManager().read(new ShowTablePlan(database, isDetails)))
          .convertToTShowTableResp();
    } catch (final ConsensusException e) {
      LOGGER.warn("Failed in the read API executing the consensus layer due to: ", e);
      final TSStatus res = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      res.setMessage(e.getMessage());
      return new TShowTableResp(res);
    }
  }

  public TShowTable4InformationSchemaResp showTables4InformationSchema() {
    try {
      return ((ShowTable4InformationSchemaResp)
              configManager.getConsensusManager().read(new ShowTable4InformationSchemaPlan()))
          .convertToTShowTable4InformationSchemaResp();
    } catch (final ConsensusException e) {
      LOGGER.warn("Failed in the read API executing the consensus layer due to: ", e);
      final TSStatus res = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      res.setMessage(e.getMessage());
      return new TShowTable4InformationSchemaResp(res);
    }
  }

  public TDescTableResp describeTable(
      final String database, final String tableName, final boolean isDetails) {
    try {
      return ((DescTableResp)
              configManager
                  .getConsensusManager()
                  .read(new DescTablePlan(database, tableName, isDetails)))
          .convertToTDescTableResp();
    } catch (final ConsensusException e) {
      LOGGER.warn("Failed in the read API executing the consensus layer due to: ", e);
      final TSStatus res = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      res.setMessage(e.getMessage());
      return new TDescTableResp(res);
    }
  }

  public TDescTable4InformationSchemaResp describeTables4InformationSchema() {
    try {
      return ((DescTable4InformationSchemaResp)
              configManager.getConsensusManager().read(new DescTable4InformationSchemaPlan()))
          .convertToTDescTable4InformationSchemaResp();
    } catch (final ConsensusException e) {
      LOGGER.warn("Failed in the read API executing the consensus layer due to: ", e);
      final TSStatus res = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      res.setMessage(e.getMessage());
      return new TDescTable4InformationSchemaResp(res);
    }
  }

  public TFetchTableResp fetchTables(final Map<String, Set<String>> fetchTableMap) {
    try {
      return ((FetchTableResp)
              configManager.getConsensusManager().read(new FetchTablePlan(fetchTableMap)))
          .convertToTFetchTableResp();
    } catch (final ConsensusException e) {
      LOGGER.warn("Failed in the read API executing the consensus layer due to: ", e);
      final TSStatus res = new TSStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR.getStatusCode());
      res.setMessage(e.getMessage());
      return new TFetchTableResp(res);
    }
  }

  public byte[] getAllTableInfoForDataNodeActivation() {
    return TsTableInternalRPCUtil.serializeTableInitializationInfo(
        clusterSchemaInfo.getAllUsingTables(), clusterSchemaInfo.getAllPreCreateTables());
  }

  // endregion

  /**
   * Only leader use this interface. Get the remain schema quota of specified schema region.
   *
   * @return pair of <series quota, device quota>, -1 means no limit
   */
  public Pair<Long, Long> getSchemaQuotaRemain() {
    boolean isDeviceLimit = schemaQuotaStatistics.getDeviceThreshold() != -1;
    boolean isSeriesLimit = schemaQuotaStatistics.getSeriesThreshold() != -1;
    if (isSeriesLimit || isDeviceLimit) {
      Set<Integer> schemaPartitionSet = getPartitionManager().getAllSchemaPartition();
      return new Pair<>(
          isSeriesLimit ? schemaQuotaStatistics.getSeriesQuotaRemain(schemaPartitionSet) : -1L,
          isDeviceLimit ? schemaQuotaStatistics.getDeviceQuotaRemain(schemaPartitionSet) : -1L);
    } else {
      return new Pair<>(-1L, -1L);
    }
  }

  public void updateTimeSeriesUsage(final Map<Integer, Long> seriesUsage) {
    schemaQuotaStatistics.updateTimeSeriesUsage(seriesUsage);
  }

  public void updateDeviceUsage(final Map<Integer, Long> deviceUsage) {
    schemaQuotaStatistics.updateDeviceUsage(deviceUsage);
  }

  public void updateSchemaQuotaConfiguration(
      final long seriesThreshold, final long deviceThreshold) {
    schemaQuotaStatistics.setDeviceThreshold(deviceThreshold);
    schemaQuotaStatistics.setSeriesThreshold(seriesThreshold);
  }

  public Optional<TsTable> getTableIfExists(final String database, final String tableName)
      throws MetadataException {
    return clusterSchemaInfo.getTsTableIfExists(database, tableName).map(Pair::getLeft);
  }

  public Optional<Pair<TsTable, TableNodeStatus>> getTableAndStatusIfExists(
      final String database, final String tableName) throws MetadataException {
    return clusterSchemaInfo.getTsTableIfExists(database, tableName);
  }

  public synchronized Pair<TSStatus, TsTable> tableColumnCheckForColumnExtension(
      final String database,
      final String tableName,
      final List<TsTableColumnSchema> columnSchemaList,
      final boolean isTableView)
      throws MetadataException {
    final TsTable originalTable = getTableIfExists(database, tableName).orElse(null);

    if (Objects.isNull(originalTable)) {
      return new Pair<>(
          RpcUtils.getStatus(
              TSStatusCode.TABLE_NOT_EXISTS,
              String.format("Table '%s.%s' does not exist", database, tableName)),
          null);
    }

    final Optional<Pair<TSStatus, TsTable>> result =
        checkTable4View(database, originalTable, isTableView);
    if (result.isPresent()) {
      return result.get();
    }

    final TsTable expandedTable = TsTable.deserialize(ByteBuffer.wrap(originalTable.serialize()));

    final String errorMsg =
        String.format(
            "Column '%s' already exist",
            columnSchemaList.stream()
                .map(TsTableColumnSchema::getColumnName)
                .collect(Collectors.joining(", ")));
    columnSchemaList.removeIf(
        columnSchema -> {
          if (Objects.isNull(originalTable.getColumnSchema(columnSchema.getColumnName()))) {
            expandedTable.addColumnSchema(columnSchema);
            return false;
          }
          return true;
        });

    if (columnSchemaList.isEmpty()) {
      return new Pair<>(RpcUtils.getStatus(TSStatusCode.COLUMN_ALREADY_EXISTS, errorMsg), null);
    }
    return new Pair<>(RpcUtils.SUCCESS_STATUS, expandedTable);
  }

  public synchronized Pair<TSStatus, TsTable> tableColumnCheckForColumnRenaming(
      final String database,
      final String tableName,
      final String oldName,
      final String newName,
      final boolean isTableView)
      throws MetadataException {
    final TsTable originalTable = getTableIfExists(database, tableName).orElse(null);

    if (Objects.isNull(originalTable)) {
      return new Pair<>(
          RpcUtils.getStatus(
              TSStatusCode.TABLE_NOT_EXISTS,
              String.format("Table '%s.%s' does not exist", database, tableName)),
          null);
    }

    final Optional<Pair<TSStatus, TsTable>> result =
        checkTable4View(database, originalTable, isTableView);
    if (result.isPresent()) {
      return result.get();
    }

    final TsTableColumnSchema schema = originalTable.getColumnSchema(oldName);
    if (Objects.isNull(schema)) {
      return new Pair<>(
          RpcUtils.getStatus(
              TSStatusCode.COLUMN_NOT_EXISTS, String.format("Column '%s' does not exist", oldName)),
          null);
    }

    if (schema.getColumnCategory() == TsTableColumnCategory.TIME) {
      return new Pair<>(
          RpcUtils.getStatus(
              TSStatusCode.COLUMN_CATEGORY_MISMATCH,
              "The renaming for time column is not supported."),
          null);
    }

    if (Objects.nonNull(originalTable.getColumnSchema(newName))) {
      return new Pair<>(
          RpcUtils.getStatus(
              TSStatusCode.COLUMN_ALREADY_EXISTS,
              "The new column name " + newName + " already exists"),
          null);
    }

    final TsTable expandedTable = TsTable.deserialize(ByteBuffer.wrap(originalTable.serialize()));

    expandedTable.renameColumnSchema(oldName, newName);

    return new Pair<>(RpcUtils.SUCCESS_STATUS, expandedTable);
  }

  public synchronized Pair<TSStatus, TsTable> tableCheckForRenaming(
      final String database,
      final String tableName,
      final String newName,
      final boolean isTableView)
      throws MetadataException {
    final TsTable originalTable = getTableIfExists(database, tableName).orElse(null);

    if (Objects.isNull(originalTable)) {
      return new Pair<>(
          RpcUtils.getStatus(
              TSStatusCode.TABLE_NOT_EXISTS,
              String.format("Table '%s.%s' does not exist", database, tableName)),
          null);
    }

    final Optional<Pair<TSStatus, TsTable>> result =
        checkTable4View(database, originalTable, isTableView);
    if (result.isPresent()) {
      return result.get();
    }

    if (getTableIfExists(database, newName).isPresent()) {
      return new Pair<>(
          RpcUtils.getStatus(
              TSStatusCode.TABLE_ALREADY_EXISTS,
              String.format("Table '%s.%s' already exists.", database, newName)),
          null);
    }

    final TsTable expandedTable = TsTable.deserialize(ByteBuffer.wrap(originalTable.serialize()));
    expandedTable.renameTable(newName);
    return new Pair<>(RpcUtils.SUCCESS_STATUS, expandedTable);
  }

  public TSStatus executePlan(final ConfigPhysicalPlan plan, final boolean isGeneratedByPipe) {
    try {
      return getConsensusManager().write(isGeneratedByPipe ? new PipeEnrichedPlan(plan) : plan);
    } catch (final ConsensusException e) {
      LOGGER.warn(e.getMessage(), e);
      return RpcUtils.getStatus(TSStatusCode.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  public synchronized TSStatus setTableComment(
      final String database,
      final String tableName,
      final String comment,
      final boolean isView,
      final boolean isGeneratedByPipe) {
    final SetTableCommentPlan setTableCommentPlan =
        isView
            ? new SetViewCommentPlan(database, tableName, comment)
            : new SetTableCommentPlan(database, tableName, comment);
    try {
      return getConsensusManager()
          .write(
              isGeneratedByPipe ? new PipeEnrichedPlan(setTableCommentPlan) : setTableCommentPlan);
    } catch (final ConsensusException e) {
      LOGGER.warn(e.getMessage(), e);
      return RpcUtils.getStatus(TSStatusCode.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  public synchronized TSStatus setTableColumnComment(
      final String database,
      final String tableName,
      final String columnName,
      final String comment,
      final boolean isGeneratedByPipe) {
    final SetTableColumnCommentPlan setTableColumnCommentPlan =
        new SetTableColumnCommentPlan(database, tableName, columnName, comment);
    try {
      return getConsensusManager()
          .write(
              isGeneratedByPipe
                  ? new PipeEnrichedPlan(setTableColumnCommentPlan)
                  : setTableColumnCommentPlan);
    } catch (final ConsensusException e) {
      LOGGER.warn(e.getMessage(), e);
      return RpcUtils.getStatus(TSStatusCode.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  public synchronized Pair<TSStatus, TsTable> updateTableProperties(
      final String database,
      final String tableName,
      final Map<String, String> originalProperties,
      final Map<String, String> updatedProperties,
      final boolean isTableView)
      throws MetadataException {
    final TsTable originalTable = getTableIfExists(database, tableName).orElse(null);

    if (Objects.isNull(originalTable)) {
      return new Pair<>(
          RpcUtils.getStatus(
              TSStatusCode.TABLE_NOT_EXISTS,
              String.format("Table '%s.%s' does not exist", database, tableName)),
          null);
    }

    final Optional<Pair<TSStatus, TsTable>> result =
        checkTable4View(database, originalTable, isTableView);
    if (result.isPresent()) {
      return result.get();
    }

    updatedProperties
        .keySet()
        .removeIf(
            key ->
                Objects.equals(
                    updatedProperties.get(key), originalTable.getPropValue(key).orElse(null)));
    if (updatedProperties.isEmpty()) {
      return new Pair<>(RpcUtils.SUCCESS_STATUS, null);
    }

    final TsTable updatedTable = TsTable.deserialize(ByteBuffer.wrap(originalTable.serialize()));
    updatedProperties.forEach(
        (k, v) -> {
          originalProperties.put(k, originalTable.getPropValue(k).orElse(null));
          if (Objects.nonNull(v)) {
            updatedTable.addProp(k, v);
          } else {
            updatedTable.removeProp(k);
          }
        });

    return new Pair<>(RpcUtils.SUCCESS_STATUS, updatedTable);
  }

  public static Optional<Pair<TSStatus, TsTable>> checkTable4View(
      final String database, final TsTable table, final boolean isView) {
    if (!isView && TreeViewSchema.isTreeViewTable(table)) {
      return Optional.of(
          new Pair<>(
              RpcUtils.getStatus(
                  TSStatusCode.SEMANTIC_ERROR,
                  String.format(
                      "Table '%s.%s' is a tree view table, does not support alter table",
                      database, table.getTableName())),
              null));
    }

    if (isView && !TreeViewSchema.isTreeViewTable(table)) {
      return Optional.of(
          new Pair<>(
              RpcUtils.getStatus(
                  TSStatusCode.SEMANTIC_ERROR,
                  String.format(
                      "Table '%s.%s' is a base table, does not support alter view",
                      database, table.getTableName())),
              null));
    }

    return Optional.empty();
  }

  @TableModel
  public long getDatabaseMaxTTL(final String database) {
    return clusterSchemaInfo.getDatabaseMaxTTL(database);
  }

  public void clearSchemaQuotaCache() {
    schemaQuotaStatistics.clear();
  }

  private NodeManager getNodeManager() {
    return configManager.getNodeManager();
  }

  private PartitionManager getPartitionManager() {
    return configManager.getPartitionManager();
  }

  private ConsensusManager getConsensusManager() {
    return configManager.getConsensusManager();
  }
}
