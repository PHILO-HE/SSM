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
package org.smartdata.server.metastore;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.collections.CollectionUtils;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.smartdata.common.CmdletState;
import org.smartdata.common.actions.ActionInfo;
import org.smartdata.common.cmdlet.CmdletInfo;
import org.smartdata.common.metastore.CachedFileStatus;
import org.smartdata.common.rule.RuleInfo;
import org.smartdata.common.rule.RuleState;
import org.smartdata.metrics.FileAccessEvent;
import org.smartdata.server.metastore.tables.AccessCountTable;
import org.smartdata.server.metastore.tables.ActionDao;
import org.smartdata.server.metastore.tables.CacheFileDao;
import org.smartdata.server.metastore.tables.CmdletDao;
import org.smartdata.server.metastore.tables.FileDao;
import org.smartdata.server.metastore.tables.GroupsDao;
import org.smartdata.server.metastore.tables.RuleDao;
import org.smartdata.server.metastore.tables.StorageDao;
import org.smartdata.server.metastore.tables.UserDao;
import org.smartdata.server.metastore.tables.XattrDao;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Operations supported for upper functions.
 */
public class MetaStoreHandle {

  private Connection connProvided = null;
  private DBPool pool = null;

  private Map<Integer, String> mapOwnerIdName = null;
  private Map<Integer, String> mapGroupIdName = null;
  private Map<Integer, String> mapStoragePolicyIdName = null;
  private Map<String, Integer> mapStoragePolicyNameId = null;
  private Map<String, StorageCapacity> mapStorageCapacity = null;
  private RuleDao ruleDao;
  private CmdletDao cmdletDao;
  private ActionDao actionDao;
  private FileDao fileDao;
  private CacheFileDao cacheFileDao;
  private StorageDao storageDao;
  private UserDao userDao;
  private GroupsDao groupsDao;
  private XattrDao xattrDao;
  

  public MetaStoreHandle(DBPool pool) throws IOException {
    this.pool = pool;
    ruleDao = new RuleDao(pool.getDataSource());
    cmdletDao = new CmdletDao(pool.getDataSource());
    actionDao = new ActionDao(pool.getDataSource());
    fileDao = new FileDao(pool.getDataSource());
    xattrDao = new XattrDao(pool.getDataSource());
    cacheFileDao = new CacheFileDao(pool.getDataSource());
    userDao = new UserDao(pool.getDataSource());
    storageDao = new StorageDao(pool.getDataSource());
    groupsDao = new GroupsDao(pool.getDataSource());
  }

  public Connection getConnection() throws SQLException {
    return pool != null ? pool.getConnection() : connProvided;
  }

  private void closeConnection(Connection conn) throws SQLException {
    if (pool != null) {
      pool.closeConnection(conn);
    }
  }

  private class QueryHelper {
    private String query;
    private Connection conn;
    private boolean connProvided = false;
    private Statement statement;
    private ResultSet resultSet;
    private boolean closed = false;

    public QueryHelper(String query) throws SQLException {
      this.query = query;
      conn = getConnection();
      if (conn == null) {
        throw new SQLException("Invalid null connection");
      }
    }

    public QueryHelper(String query, Connection conn) throws SQLException {
      this.query = query;
      this.conn = conn;
      connProvided = true;
      if (conn == null) {
        throw new SQLException("Invalid null connection");
      }
    }

    public ResultSet executeQuery() throws SQLException {
      statement = conn.createStatement();
      resultSet = statement.executeQuery(query);
      return resultSet;
    }

    public int executeUpdate() throws SQLException {
      statement = conn.createStatement();
      return statement.executeUpdate(query);
    }

    public void execute() throws SQLException {
      statement = conn.createStatement();
      statement.executeUpdate(query);
    }

    public void close() throws SQLException {
      if (closed) {
        return;
      }
      closed = true;

      if (resultSet != null && !resultSet.isClosed()) {
        resultSet.close();
      }

      if (statement != null && !statement.isClosed()) {
        statement.close();
      }

      if (conn != null && !connProvided) {
        closeConnection(conn);
      }
    }
  }

  public Map<Long, Integer> getAccessCount(long startTime, long endTime,
      String countFilter) throws SQLException {
    Map<Long, Integer> ret = new HashMap<>();
    String sqlGetTableNames = "SELECT table_name FROM access_count_tables "
        + "WHERE start_time >= " + startTime + " AND end_time <= " + endTime;
    Connection conn = getConnection();
    QueryHelper qhTableName = null;
    ResultSet rsTableNames = null;
    QueryHelper qhValues = null;
    ResultSet rsValues = null;
    try {
      qhTableName = new QueryHelper(sqlGetTableNames, conn);
      rsTableNames = qhTableName.executeQuery();
      List<String> tableNames = new LinkedList<>();
      while (rsTableNames.next()) {
        tableNames.add(rsTableNames.getString(1));
      }
      qhTableName.close();

      if (tableNames.size() == 0) {
        return ret;
      }


      String sqlPrefix = "SELECT fid, SUM(count) AS count FROM (\n";
      String sqlUnion = "SELECT fid, count FROM \'"
          + tableNames.get(0) + "\'\n";
      for (int i = 1; i < tableNames.size(); i++) {
        sqlUnion += "UNION ALL\n" +
            "SELECT fid, count FROM \'" + tableNames.get(i) + "\'\n";
      }
      String sqlSufix = ") GROUP BY fid ";
      // TODO: safe check
      String sqlCountFilter =
          (countFilter == null || countFilter.length() == 0) ?
              "" :
              "HAVING SUM(count) " + countFilter;
      String sqlFinal = sqlPrefix + sqlUnion + sqlSufix + sqlCountFilter;

      qhValues = new QueryHelper(sqlFinal, conn);
      rsValues = qhValues.executeQuery();

      while (rsValues.next()) {
        ret.put(rsValues.getLong(1), rsValues.getInt(2));
      }

      return ret;
    } finally {
      if (qhTableName != null) {
        qhTableName.close();
      }

      if (qhValues != null) {
        qhValues.close();
      }

      closeConnection(conn);
    }
  }

  private synchronized void addUser(String userName) throws SQLException {
    userDao.addUser(userName);
  }

  private synchronized void addGroup(String groupName) throws SQLException {
    groupsDao.addGroup(groupName);
  }

  private void updateUsersMap() throws SQLException {
    String sql = "SELECT * FROM owners";
    QueryHelper queryHelper = new QueryHelper(sql);
    try {
      mapOwnerIdName = convertToMap(queryHelper.executeQuery(), "oid", "owner_name");
    } finally {
      queryHelper.close();
    }
  }

  private void updateGroupsMap() throws SQLException {
    String sql = "SELECT * FROM groups";
    QueryHelper queryHelper = new QueryHelper(sql);
    try {
      mapGroupIdName = convertToMap(queryHelper.executeQuery(), "gid", "group_name");
    } finally {
      queryHelper.close();
    }
  }

  /**
   * Store files info into database.
   *
   * @param files
   */
  public synchronized void insertFiles(FileStatusInternal[] files)
      throws SQLException {
    updateCache();
    fileDao.insert(files);
  }

  public int updateFileStoragePolicy(String path, String policyName)
      throws SQLException {
    if (mapStoragePolicyIdName == null) {
      updateCache();
    }
    if (!mapStoragePolicyNameId.containsKey(policyName)) {
      throw new SQLException("Unknown storage policy name '"
          + policyName + "'");
    }
    return storageDao.updateFileStoragePolicy(path, policyName);
  }

  private int booleanToInt(boolean b) {
    return b ? 1 : 0;
  }

  private Integer getKey(Map<Integer, String> map, String value) {
    for (Integer key : map.keySet()) {
      if (map.get(key).equals(value)) {
        return key;
      }
    }
    return null;
  }

  public List<HdfsFileStatus> getFile() throws SQLException {
    return fileDao.getAll();
  }

  public HdfsFileStatus getFile(long fid) throws SQLException {
    return fileDao.getById(fid);

  }

  public Map<String, Long> getFileIDs(Collection<String> paths)
      throws SQLException {
    return fileDao.getFids(paths);
  }

  public Map<Long, String> getFilePaths(Collection<Long> ids)
      throws SQLException {
    return fileDao.getPaths(ids);
  }

  public synchronized List<FileAccessInfo> getHotFiles(List<AccessCountTable> tables, int topNum) throws Exception {
    Iterator<AccessCountTable> tableIterator = tables.iterator();
    if (tableIterator.hasNext()) {
      StringBuilder unioned = new StringBuilder();
      while (tableIterator.hasNext()) {
        AccessCountTable table = tableIterator.next();
        if (tableIterator.hasNext()) {
          unioned.append("SELECT * FROM " + table.getTableName() + " UNION ALL ");
        } else {
          unioned.append("SELECT * FROM " + table.getTableName());
        }
      }
      String statement =
          String.format(
              "SELECT %s, SUM(%s) as %s FROM (%s) tmp GROUP BY %s ORDER BY %s DESC LIMIT %s",
              AccessCountTable.FILE_FIELD,
              AccessCountTable.ACCESSCOUNT_FIELD,
              AccessCountTable.ACCESSCOUNT_FIELD,
              unioned,
              AccessCountTable.FILE_FIELD,
              AccessCountTable.ACCESSCOUNT_FIELD,
              topNum);
      ResultSet resultSet = this.executeQuery(statement);
      Map<Long, Integer> accessCounts = new HashMap<>();
      while (resultSet.next()) {
        accessCounts.put(
            resultSet.getLong(AccessCountTable.FILE_FIELD),
            resultSet.getInt(AccessCountTable.ACCESSCOUNT_FIELD));
      }
      Map<Long, String> idToPath = this.getFilePaths(accessCounts.keySet());
      List<FileAccessInfo> result = new ArrayList<>();
      for (Map.Entry<Long, Integer> entry : accessCounts.entrySet()) {
        Long fid = entry.getKey();
        if (idToPath.containsKey(fid)) {
          result.add(new FileAccessInfo(fid, idToPath.get(fid), accessCounts.get(fid)));
        }
      }
      return result;
    } else {
      return new ArrayList<>();
    }
  }

  public HdfsFileStatus getFile(String path) throws SQLException {
    return fileDao.getByPath(path);
  }

  public void insertStoragesTable(StorageCapacity[] storages)
      throws SQLException {
    mapStorageCapacity = null;
    storageDao.insertStoragesTable(storages);
  }

  public StorageCapacity getStorageCapacity(String type) throws SQLException {
    // TODO updateCache
    updateCache();
    return mapStorageCapacity.get(type);
  }

  public synchronized boolean updateStoragesTable(String type,
      Long capacity, Long free) throws SQLException {
    return storageDao.updateStoragesTable(type, capacity, free);
  }

  private void updateCache() throws SQLException {
    if (mapOwnerIdName == null) {
      String sql = "SELECT * FROM owners";
      QueryHelper queryHelper = new QueryHelper(sql);
      try {
        mapOwnerIdName = convertToMap(queryHelper.executeQuery(), "oid", "owner_name");
      } finally {
        queryHelper.close();
      }
    }

    if (mapGroupIdName == null) {
      String sql = "SELECT * FROM groups";
      QueryHelper queryHelper = new QueryHelper(sql);
      try {
        mapGroupIdName = convertToMap(queryHelper.executeQuery(), "gid", "group_name");
      } finally {
        queryHelper.close();
      }
    }

    if (mapStoragePolicyIdName == null) {
      mapStoragePolicyNameId = null;
      String sql = "SELECT * FROM storage_policy";
      QueryHelper queryHelper = new QueryHelper(sql);
      try {
        mapStoragePolicyIdName = convertToMap(queryHelper.executeQuery(), "sid", "policy_name");
        mapStoragePolicyNameId = new HashMap<>();
        for (Integer key : mapStoragePolicyIdName.keySet()) {
          mapStoragePolicyNameId.put(mapStoragePolicyIdName.get(key), key);
        }
      } finally {
        queryHelper.close();
      }
    }

    if (mapStorageCapacity == null) {
      String sql = "SELECT * FROM storages";
      QueryHelper queryHelper = new QueryHelper(sql);
      try {
        mapStorageCapacity =
            convertStorageTablesItem(queryHelper.executeQuery());
      } finally {
        queryHelper.close();
      }
    }
  }

  private Map<String, StorageCapacity> convertStorageTablesItem(
      ResultSet resultSet) throws SQLException {
    Map<String, StorageCapacity> map = new HashMap<>();
    if (resultSet == null) {
      return map;
    }

    while (resultSet.next()) {
      String type = resultSet.getString(1);
      StorageCapacity storage = new StorageCapacity(
          resultSet.getString(1),
          resultSet.getLong(2),
          resultSet.getLong(3));
      map.put(type, storage);
    }
    return map;
  }

  private Map<Integer, String> convertToMap(ResultSet resultSet, String keyIndex, String valueIndex)
      throws SQLException {
    Map<Integer, String> ret = new HashMap<>();
    if (resultSet == null) {
      return ret;
    }

    while (resultSet.next()) {
      // TODO: Tests for this
      ret.put(resultSet.getInt(keyIndex), resultSet.getString(valueIndex));
    }
    return ret;
  }

  public synchronized void insertCachedFiles(long fid, String path, long fromTime,
      long lastAccessTime, int numAccessed) throws SQLException {
    cacheFileDao.insert(fid, path, fromTime, lastAccessTime, numAccessed);
  }

  public synchronized void insertCachedFiles(List<CachedFileStatus> s)
      throws SQLException {
    cacheFileDao.insert(s.toArray(new CachedFileStatus[s.size()]));
  }

  public void deleteAllCachedFile() throws SQLException {
    cacheFileDao.deleteAll();
  }

  public void updateCachedFiles(Map<String, Long> pathToIds, List<FileAccessEvent> events)
      throws SQLException {
    Map<Long, CachedFileStatus> idToStatus = new HashMap<>();
    List<CachedFileStatus> cachedFileStatuses = this.getCachedFileStatus();
    for (CachedFileStatus status : cachedFileStatuses) {
      idToStatus.put(status.getFid(), status);
    }
    Collection<Long> cachedIds = idToStatus.keySet();
    Collection<Long> needToUpdate = CollectionUtils.intersection(cachedIds, pathToIds.values());
    if (!needToUpdate.isEmpty()) {
      Map<Long, Integer> idToCount = new HashMap<>();
      Map<Long, Long> idToLastTime = new HashMap<>();
      for (FileAccessEvent event : events) {
        Long fid = pathToIds.get(event.getPath());
        if (needToUpdate.contains(fid)) {
          if (!idToCount.containsKey(fid)) {
            idToCount.put(fid, 0);
          }
          idToCount.put(fid, idToCount.get(fid) + 1);
          if (!idToLastTime.containsKey(fid)) {
            idToLastTime.put(fid, event.getTimestamp());
          }
          idToLastTime.put(fid, Math.max(event.getTimestamp(), idToLastTime.get(fid)));
        }
      }
      for (Long fid : needToUpdate) {
        Integer newAccessCount = idToStatus.get(fid).getNumAccessed() + idToCount.get(fid);
        this.updateCachedFiles(fid, null, idToLastTime.get(fid), newAccessCount);
      }
    }
  }

  public void deleteCachedFile(long fid) throws SQLException {
    cacheFileDao.deleteById(fid);
  }

  public synchronized boolean updateCachedFiles(Long fid, Long fromTime,
      Long lastAccessTime, Integer numAccessed) throws SQLException {
    StringBuffer sb = new StringBuffer("UPDATE cached_files SET");
    if (fromTime != null) {
      sb.append(" from_time = ").append(fid).append(",");
    }
    if (lastAccessTime != null) {
      sb.append(" last_access_time = ").append(lastAccessTime).append(",");
    }
    if (numAccessed != null) {
      sb.append(" num_accessed = ").append(numAccessed).append(",");
    }
    int idx = sb.lastIndexOf(",");
    sb.replace(idx, idx + 1, "");
    sb.append(" WHERE fid = ").append(fid).append(";");

    QueryHelper queryHelper = new QueryHelper(sb.toString());
    try {
      return queryHelper.executeUpdate() == 1;
    } finally {
      queryHelper.close();
    }
  }

  public List<CachedFileStatus> getCachedFileStatus() throws SQLException {
    return cacheFileDao.getAll();
  }

  public List<Long> getCachedFids() throws SQLException {
    return cacheFileDao.getFids();
  }

  public CachedFileStatus getCachedFileStatus(long fid) throws SQLException {
    return cacheFileDao.getById(fid);
  }

  public void createProportionView(AccessCountTable dest, AccessCountTable source)
      throws SQLException {
    double percentage =
        ((double) dest.getEndTime() - dest.getStartTime())
            / (source.getEndTime() - source.getStartTime());
    String sql =
        String.format(
            "CREATE VIEW %s AS SELECT %s, FLOOR(%s.%s * %s) AS %s FROM %s",
            dest.getTableName(),
            AccessCountTable.FILE_FIELD,
            source.getTableName(),
            AccessCountTable.ACCESSCOUNT_FIELD,
            percentage,
            AccessCountTable.ACCESSCOUNT_FIELD,
            source.getTableName());
    execute(sql);
  }

  public void dropTable(String tableName) throws SQLException {
    execute("DROP TABLE " + tableName);
  }

  private List<CachedFileStatus> getCachedFileStatus(String sql)
      throws SQLException {
    QueryHelper queryHelper = new QueryHelper(sql);
    List<CachedFileStatus> ret = new LinkedList<>();

    try {
      ResultSet resultSet = queryHelper.executeQuery();
      while (resultSet.next()) {
        CachedFileStatus f = new CachedFileStatus(
            resultSet.getLong("fid"),
            resultSet.getString("path"),
            resultSet.getLong("from_time"),
            resultSet.getLong("last_access_time"),
            resultSet.getInt("num_accessed")
        );
        ret.add(f);
      }
      return ret;
    } finally {
      queryHelper.close();
    }
  }

  public int executeUpdate(String sql) throws SQLException {
    QueryHelper queryHelper = new QueryHelper(sql);
    try {
      return queryHelper.executeUpdate();
    } finally {
      queryHelper.close();
    }
  }

  public void execute(String sql) throws SQLException {
    QueryHelper queryHelper = new QueryHelper(sql);
    try {
      queryHelper.execute();
    } finally {
      queryHelper.close();
    }
  }

  //Todo: optimize
  public void execute(List<String> statements) throws SQLException {
    for (String statement : statements) {
      this.execute(statement);
    }
  }

  public List<String> executeFilesPathQuery(String sql) throws SQLException {
    List<String> paths = new LinkedList<>();
    QueryHelper queryHelper = new QueryHelper(sql);
    try {
      ResultSet res = queryHelper.executeQuery();
      while (res.next()) {
        paths.add(res.getString(1));
      }
      return paths;
    } finally {
      queryHelper.close();
    }
  }

  public synchronized boolean insertNewRule(RuleInfo info)
      throws SQLException {
    long id = ruleDao.insert(info);
    if (id >= 0) {
      return true;
    }
    return false;
  }

  public synchronized boolean updateRuleInfo(long ruleId, RuleState rs,
      long lastCheckTime, long checkedCount, int commandsGen)
      throws SQLException {
    return ruleDao.update(ruleId,
        rs.getValue(), lastCheckTime, checkedCount, commandsGen) >= 0;
  }

  public RuleInfo getRuleInfo(long ruleId) throws SQLException {
    return ruleDao.getById(ruleId);
  }

  public List<RuleInfo> getRuleInfo() throws SQLException {
    return ruleDao.getAll();
  }

  public synchronized void insertCmdletsTable(CmdletInfo[] commands)
      throws SQLException {
    cmdletDao.insert(commands);
  }

  public synchronized void insertCmdletTable(CmdletInfo command)
      throws SQLException {
    cmdletDao.insert(command);
  }

  public long getMaxCmdletId() throws SQLException {
    return cmdletDao.getMaxId();
  }

  public List<CmdletInfo> getCmdletsTableItem(String cidCondition,
      String ridCondition, CmdletState state) throws SQLException {
    return cmdletDao.getByCondition(cidCondition, ridCondition, state);
  }

  public boolean updateCmdletStatus(long cid, long rid, CmdletState state)
      throws SQLException {
    return cmdletDao.update(cid, rid, state.getValue()) >= 0;
  }

  public void deleteCmdlet(long cid) throws SQLException {
    cmdletDao.delete(cid);
  }

  public synchronized void insertActionsTable(ActionInfo[] actionInfos)
      throws SQLException {
    actionDao.insert(actionInfos);
  }

  public synchronized void insertActionTable(ActionInfo actionInfo)
      throws SQLException {
    actionDao.insert(actionInfo);
  }

  public synchronized void updateActionsTable(ActionInfo[] actionInfos)
      throws SQLException {
    actionDao.update(actionInfos);
  }

  public List<ActionInfo> getNewCreatedActionsTableItem(int size) throws SQLException {
    if (size <= 0) {
      return new ArrayList<>();
    }
    return actionDao.getLatestActions(size);
  }

  public List<ActionInfo> getActionsTableItem(List<Long> aids) throws SQLException {
    if (aids == null || aids.size() == 0){
      return null;
    }
    return actionDao.getByIds(aids);
  }

  public List<ActionInfo> getActionsTableItem(String aidCondition,
      String cidCondition) throws SQLException {
    return actionDao.getByCondition(aidCondition, cidCondition);
  }

  public long getMaxActionId() throws SQLException {
    return actionDao.getMaxId();
  }

  public synchronized void insertStoragePolicyTable(StoragePolicy s)
      throws SQLException {
    String sql = "INSERT INTO `storage_policy` (sid, policy_name) VALUES('"
        + s.getSid() + "','" + s.getPolicyName() + "');";
    mapStoragePolicyIdName = null;
    execute(sql);
  }

  public String getStoragePolicyName(int sid) throws SQLException {
    updateCache();
    return mapStoragePolicyIdName.get(sid);
  }

  public Integer getStoragePolicyID(String policyName) throws SQLException {
    updateCache();
    return getKey(mapStoragePolicyIdName, policyName);
  }

  public synchronized boolean insertXattrTable(Long fid, Map<String,
      byte[]> map) throws SQLException {
    return xattrDao.insertXattrTable(fid, map);
  }

  public Map<String, byte[]> getXattrTable(Long fid) throws SQLException {
    return xattrDao.getXattrTable(fid);
  }

  public void dropAllTables() throws SQLException {
    Connection conn = getConnection();
    try {
      String url = conn.getMetaData().getURL();
      if (url.startsWith(Util.SQLITE_URL_PREFIX)) {
        dropAllTablesSqlite(conn);
      } else if (url.startsWith(Util.MYSQL_URL_PREFIX)) {
        dropAllTablesMysql(conn, url);
      } else {
        throw new SQLException("Unsupported database");
      }
    } finally {
      closeConnection(conn);
    }
  }

  public void dropAllTablesSqlite(Connection conn) throws SQLException {
    try {
      Statement s = conn.createStatement();
      ResultSet rs = s.executeQuery("SELECT tbl_name FROM sqlite_master;");
      List<String> list = new ArrayList<>();
      while (rs.next()) {
        list.add(rs.getString(1));
      }
      for (String tb : list) {
        if (!"sqlite_sequence".equals(tb)) {
          s.execute("DROP TABLE IF EXISTS '" + tb + "';");
        }
      }
    } finally {
      closeConnection(conn);
    }
  }

  public void dropAllTablesMysql(Connection conn, String url) throws SQLException {
    Statement stat = conn.createStatement();
    if(!url.contains("?")) {
      throw new SQLException("Invalid MySQL url without db_name");
    }
    String dbName = url.substring(url.indexOf("/", 13) + 1, url.indexOf("?"));
    ResultSet rs = stat.executeQuery("SELECT TABLE_NAME FROM "
        + "INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '" + dbName + "';");
    List<String> list = new ArrayList<>();
    while (rs.next()) {
      list.add(rs.getString(1));
    }
    for (String tb : list) {
      stat.execute("DROP TABLE IF EXISTS '" + tb + "';");
    }
  }

  public synchronized void initializeDataBase() throws SQLException {
    Connection conn = getConnection();
    try {
      Util.initializeDataBase(conn);
    } finally {
      closeConnection(conn);
    }
  }

  public synchronized void formatDataBase() throws SQLException {
    dropAllTables();
    initializeDataBase();
  }

  @VisibleForTesting
  public ResultSet executeQuery(String sqlQuery) throws SQLException {
    Connection conn = getConnection();
    try {
      Statement s = conn.createStatement();
      return s.executeQuery(sqlQuery);
    } finally {
      closeConnection(conn);
    }
  }
}