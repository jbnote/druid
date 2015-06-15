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

package io.druid.server.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Supplier;
import com.google.inject.Inject;
import com.metamx.emitter.service.ServiceEmitter;
import com.metamx.emitter.service.ServiceMetricEvent;
import io.druid.audit.AuditEntry;
import io.druid.audit.AuditManager;
import io.druid.guice.ManageLifecycle;
import io.druid.guice.annotations.Json;
import io.druid.metadata.MetadataStorageTablesConfig;
import io.druid.metadata.SQLMetadataConnector;
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.IDBI;
import org.skife.jdbi.v2.StatementContext;
import org.skife.jdbi.v2.tweak.HandleCallback;
import org.skife.jdbi.v2.tweak.ResultSetMapper;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@ManageLifecycle
public class SQLAuditManager implements AuditManager
{
  private final IDBI dbi;
  private final Supplier<MetadataStorageTablesConfig> dbTables;
  private final ServiceEmitter emitter;
  private final ObjectMapper jsonMapper;
  private final SQLAuditManagerConfig config;

  @Inject
  public SQLAuditManager(
      SQLMetadataConnector connector,
      Supplier<MetadataStorageTablesConfig> dbTables,
      ServiceEmitter emitter,
      @Json ObjectMapper jsonMapper,
      SQLAuditManagerConfig config
  )
  {
    this.dbi = connector.getDBI();
    this.dbTables = dbTables;
    this.emitter = emitter;
    this.jsonMapper = jsonMapper;
    this.config = config;
  }

  public String getAuditTable()
  {
    return dbTables.get().getAuditTable();
  }

  @Override
  public void doAudit(final AuditEntry auditEntry)
  {
    dbi.withHandle(
        new HandleCallback<Void>()
        {
          @Override
          public Void withHandle(Handle handle) throws Exception
          {
            doAudit(auditEntry, handle);
            return null;
          }
        }
    );
  }

  @Override
  public void doAudit(AuditEntry auditEntry, Handle handle) throws IOException
  {
    emitter.emit(
        new ServiceMetricEvent.Builder()
            .setUser1(auditEntry.getKey())
            .setUser2(auditEntry.getType())
            .setUser3(auditEntry.getAuditInfo().getAuthor())
            .setUser5(jsonMapper.writeValueAsString(auditEntry.getPayload()))
            .setUser6(auditEntry.getAuditInfo().getComment())
            .setUser7(auditEntry.getAuditTime().toString())
            .build("config/audit", 1)
    );

    handle.createStatement(
        String.format(
                      "UPSERT INTO %1$s (id, audit_key, type, author, comment, created_date, payload) VALUES (NEXT VALUE FOR sequence_%1$s, :audit_key, :type, :author, :comment, :created_date, :payload)",
                      //            "INSERT INTO %s ( audit_key, type, author, comment, created_date, payload) VALUES (:audit_key, :type, :author, :comment, :created_date, :payload)",
            getAuditTable()
        )
    )
          .bind("audit_key", auditEntry.getKey())
          .bind("type", auditEntry.getType())
          .bind("author", auditEntry.getAuditInfo().getAuthor())
          .bind("comment", auditEntry.getAuditInfo().getComment())
          .bind("created_date", auditEntry.getAuditTime().toString())
          .bind("payload", jsonMapper.writeValueAsBytes(auditEntry))
          .execute();
  }

  @Override
  public List<AuditEntry> fetchAuditHistory(final String key, final String type, Interval interval)
  {
    final Interval theInterval;
    if (interval == null) {
      DateTime now = new DateTime();
      theInterval = new Interval(now.minus(config.getAuditHistoryMillis()), now);
    } else {
      theInterval = interval;
    }
    return dbi.withHandle(
        new HandleCallback<List<AuditEntry>>()
        {
          @Override
          public List<AuditEntry> withHandle(Handle handle) throws Exception
          {
            return handle.createQuery(
                String.format(
                    "SELECT payload FROM %s WHERE audit_key = :audit_key and type = :type and created_date between :start_date and :end_date ORDER BY created_date",
                    getAuditTable()
                )
            ).bind("audit_key", key)
                         .bind("type", type)
                         .bind("start_date", theInterval.getStart().toString())
                         .bind("end_date", theInterval.getEnd().toString())
                         .map(
                             new ResultSetMapper<AuditEntry>()
                             {
                               @Override
                               public AuditEntry map(int index, ResultSet r, StatementContext ctx)
                                   throws SQLException
                               {
                                 try {
                                   return jsonMapper.readValue(r.getBytes("payload"), AuditEntry.class);
                                 }
                                 catch (IOException e) {
                                   throw new SQLException(e);
                                 }
                               }
                             }
                         )
                         .list();
          }
        }
    );
  }

}
