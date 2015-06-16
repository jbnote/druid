/*
 * Druid - a distributed column store.
 * Copyright 2012 - 2015 Metamarkets Group Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.druid.metadata.storage.phoenix;

import com.google.common.base.Supplier;
import com.google.inject.Inject;
import com.metamx.common.logger.Logger;
import io.druid.metadata.MetadataStorageConnectorConfig;
import io.druid.metadata.MetadataStorageTablesConfig;
import io.druid.metadata.SQLMetadataConnector;
import org.apache.commons.dbcp2.BasicDataSource;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.tweak.HandleCallback;
import org.skife.jdbi.v2.util.StringMapper;

public class PhoenixConnector extends SQLMetadataConnector
{
  private static final Logger log = new Logger(PhoenixConnector.class);
  private static final String PAYLOAD_TYPE = "VARBINARY";
  private static final String SERIAL_TYPE = "BIGINT";

  private final DBI dbi;

  @Inject
  public PhoenixConnector(Supplier<MetadataStorageConnectorConfig> config, Supplier<MetadataStorageTablesConfig> dbTables)
  {
    super(config, dbTables);

    final BasicDataSource datasource = getDatasource();
    // Phoenix driver is classloader isolated as part of the extension
    // so we need to help JDBC find the driver
    datasource.setDriverClassLoader(getClass().getClassLoader());
    datasource.setDriverClassName("org.apache.phoenix.jdbc.PhoenixDriver");

    this.dbi = new DBI(datasource);
  }

  @Override
  public String getUpsertFormatString(String vars, String vals) {
      return String.format("UPSERT INTO %%1$s (id, %s) VALUES (NEXT VALUE FOR sequence_%%1$s, %s)", vars, vals);
  }

  @Override
  protected String getPayloadType() {
    return PAYLOAD_TYPE;
  }

  @Override
  protected String getSerialType()
  {
      /* Audit, Log, Lock */
      return SERIAL_TYPE;
  }

  @Override
  public boolean tableExists(final Handle handle, final String tableName)
  {
      try {
          return handle.getConnection().getMetaData().getTables(null, null, tableName, null).first();
      } catch (java.sql.SQLException e) {
          /* Let table creation handle the exn */
          return true;
      }
  }

  @Override
  public Void insertOrUpdate(
      final String tableName,
      final String keyColumn,
      final String valueColumn,
      final String key,
      final byte[] value
  ) throws Exception
  {
    return getDBI().withHandle(
        new HandleCallback<Void>()
        {
          @Override
          public Void withHandle(Handle handle) throws Exception
          {
            handle.createStatement(
                String.format(
                              "UPSERT INTO %1$s (%2$s, %3$s) VALUES (:key, :value)",
                              tableName,
                              keyColumn,
                              valueColumn
                              )
            )
                  .bind("key", key)
                  .bind("value", value)
                  .execute();
            return null;
          }
        }
    );
  }

  @Override
  public DBI getDBI() { return dbi; }
}
