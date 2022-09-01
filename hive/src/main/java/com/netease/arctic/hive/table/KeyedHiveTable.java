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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.hive.table;

import com.netease.arctic.AmsClient;
import com.netease.arctic.ams.api.TableMeta;
import com.netease.arctic.hive.HMSClient;
import com.netease.arctic.hive.HiveTableProperties;
import com.netease.arctic.hive.utils.HiveMetaSynchronizer;
import com.netease.arctic.table.BaseKeyedTable;
import com.netease.arctic.table.ChangeTable;
import com.netease.arctic.table.PrimaryKeySpec;
import org.apache.iceberg.util.PropertyUtil;

/**
 * Implementation of {@link com.netease.arctic.table.KeyedTable} with Hive table as base store.
 */
public class KeyedHiveTable extends BaseKeyedTable implements SupportHive {

  private final HMSClient hiveClient;

  public KeyedHiveTable(
      TableMeta tableMeta,
      String tableLocation,
      PrimaryKeySpec primaryKeySpec,
      AmsClient client,
      HMSClient hiveClient,
      UnkeyedHiveTable baseTable,
      ChangeTable changeTable) {
    super(tableMeta, tableLocation, primaryKeySpec, client, baseTable, changeTable);
    this.hiveClient = hiveClient;
    syncHiveSchemaToArctic();
    syncHiveDataToArctic();
  }

  @Override
  public void refresh() {
    super.refresh();
    syncHiveSchemaToArctic();
    syncHiveDataToArctic();
  }


  @Override
  public String hiveLocation() {
    return ((SupportHive)baseTable()).hiveLocation();
  }

  private void syncHiveSchemaToArctic() {
    if (PropertyUtil.propertyAsBoolean(
        properties(),
        HiveTableProperties.AUTO_SYNC_HIVE_SCHEMA_CHANGE,
        HiveTableProperties.AUTO_SYNC_HIVE_SCHEMA_CHANGE_DEFAULT)) {
      HiveMetaSynchronizer.syncHiveSchemaToArctic(this, hiveClient);
    }
  }

  private void syncHiveDataToArctic() {
    if (PropertyUtil.propertyAsBoolean(
        properties(),
        HiveTableProperties.AUTO_SYNC_HIVE_DATA_WRITE,
        HiveTableProperties.AUTO_SYNC_HIVE_DATA_WRITE_DEFAULT)) {
      HiveMetaSynchronizer.syncHiveDataToArctic(this, hiveClient);
    }
  }

  @Override
  public HMSClient getHMSClient() {
    return hiveClient;
  }
}