/*
 * Copyright 2023 Responsive Computing, Inc.
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

package dev.responsive.db;

import static com.datastax.oss.driver.api.querybuilder.QueryBuilder.bindMarker;
import static dev.responsive.db.ColumnNames.DATA_KEY;
import static dev.responsive.db.ColumnNames.DATA_VALUE;
import static dev.responsive.db.ColumnNames.METADATA_KEY;
import static dev.responsive.db.ColumnNames.OFFSET;

import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.api.querybuilder.QueryBuilder;
import com.datastax.oss.driver.api.querybuilder.SchemaBuilder;
import dev.responsive.db.partitioning.SubPartitioner;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.CheckReturnValue;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CassandraFactSchema implements RemoteKeyValueSchema {

  private static final Logger LOG = LoggerFactory.getLogger(
      CassandraFactSchema.class);

  private final CassandraClient client;

  // use ConcurrentHashMap instead of ConcurrentMap in the declaration here
  // because ConcurrentHashMap guarantees that the supplied function for
  // computeIfAbsent is invoked exactly once per invocation of the method
  // if the key is absent, else not at all. this guarantee is not present
  // in all implementations of ConcurrentMap
  private final ConcurrentHashMap<String, PreparedStatement> get;
  private final ConcurrentHashMap<String, PreparedStatement> insert;
  private final ConcurrentHashMap<String, PreparedStatement> delete;
  private final ConcurrentHashMap<String, PreparedStatement> getMeta;
  private final ConcurrentHashMap<String, PreparedStatement> setOffset;

  public CassandraFactSchema(final CassandraClient client) {
    this.client = client;
    get = new ConcurrentHashMap<>();
    insert = new ConcurrentHashMap<>();
    delete = new ConcurrentHashMap<>();
    getMeta = new ConcurrentHashMap<>();
    setOffset = new ConcurrentHashMap<>();
  }

  @Override
  public void create(final String tableName) {
    LOG.info("Creating data table {} in remote store.", tableName);
    client.execute(SchemaBuilder
        .createTable(tableName)
        .ifNotExists()
        .withPartitionKey(DATA_KEY.column(), DataTypes.BLOB)
        .withColumn(DATA_VALUE.column(), DataTypes.BLOB)
        .withColumn(OFFSET.column(), DataTypes.BIGINT)
        .build()
    );
  }

  /**
   * Initializes the metadata entry for {@code table} by adding a
   * row with key {@code _metadata} and sets special columns
   * {@link ColumnNames#OFFSET} and no {@link ColumnNames#EPOCH}.
   *
   * <p>Note that this method is idempotent as it uses Cassandra's
   * {@code IF NOT EXISTS} functionality.
   *
   * @param table          the table that is initialized
   * @param kafkaPartition the partition to initialize
   */
  @Override
  public FencingToken init(
      final String table,
      final SubPartitioner partitioner,
      final int kafkaPartition
  ) {
    client.execute(
        QueryBuilder.insertInto(table)
            .value(DATA_KEY.column(), QueryBuilder.literal(metadataKey(kafkaPartition)))
            .value(OFFSET.column(), OFFSET.literal(-1L))
            .ifNotExists()
            .build()
    );

    return new NoOpFencingToken();
  }

  @Override
  public MetadataRow metadata(final String table, final int partition) {
    final BoundStatement bound = getMeta.get(table)
        .bind()
        .setByteBuffer(DATA_KEY.bind(), metadataKey(partition));
    final List<Row> result = client.execute(bound).all();

    if (result.size() != 1) {
      throw new IllegalStateException(String.format(
          "Expected exactly one offset row for %s[%s] but got %d",
          table, partition, result.size()));
    } else {
      final long offset = result.get(0).getLong(OFFSET.column());
      LOG.info("Got offset for {}[{}]: {}", table, partition, offset);
      return new MetadataRow(offset, -1L);
    }
  }

  @Override
  public BoundStatement setOffset(
      final String table,
      final FencingToken token,
      final int partition,
      final long offset
  ) {
    LOG.info("Setting offset for {}[{}] to {}", table, partition, offset);
    return setOffset.get(table)
        .bind()
        .setByteBuffer(DATA_KEY.bind(), metadataKey(partition))
        .setLong(OFFSET.bind(), offset);
  }

  @Override
  public void prepare(final String tableName) {
    insert.computeIfAbsent(tableName, k -> client.prepare(
        QueryBuilder
            .insertInto(tableName)
            .value(DATA_KEY.column(), bindMarker(DATA_KEY.bind()))
            .value(DATA_VALUE.column(), bindMarker(DATA_VALUE.bind()))
            .build()
    ));

    get.computeIfAbsent(tableName, k -> client.prepare(
        QueryBuilder
            .selectFrom(tableName)
            .columns(DATA_VALUE.column())
            .where(DATA_KEY.relation().isEqualTo(bindMarker(DATA_KEY.bind())))
            .build()
    ));

    delete.computeIfAbsent(tableName, k -> client.prepare(
        QueryBuilder
            .deleteFrom(tableName)
            .where(DATA_KEY.relation().isEqualTo(bindMarker(DATA_KEY.bind())))
            .build()
    ));

    getMeta.computeIfAbsent(tableName, k -> client.prepare(
        QueryBuilder
            .selectFrom(tableName)
            .column(OFFSET.column())
            .where(DATA_KEY.relation().isEqualTo(bindMarker(DATA_KEY.bind())))
            .build()
    ));

    setOffset.computeIfAbsent(tableName, k -> client.prepare(QueryBuilder
        .update(tableName)
        .setColumn(OFFSET.column(), bindMarker(OFFSET.bind()))
        .where(DATA_KEY.relation().isEqualTo(bindMarker(DATA_KEY.bind())))
        .build()
    ));
  }

  @Override
  public CassandraClient getClient() {
    return client;
  }

  /**
   * @param table         the table to delete from
   * @param partitionKey  the partitioning key
   * @param key           the data key
   *
   * @return a statement that, when executed, will delete the row
   *         matching {@code partitionKey} and {@code key} in the
   *         {@code table}
   */
  @Override
  @CheckReturnValue
  public BoundStatement delete(
      final String table,
      final int partitionKey,
      final Bytes key
  ) {
    return delete.get(table)
        .bind()
        .setByteBuffer(DATA_KEY.bind(), ByteBuffer.wrap(key.get()));
  }


  /**
   * Inserts data into {@code table}. Note that this will overwrite
   * any existing entry in the table with the same key.
   *
   * @param table         the table to insert into
   * @param partitionKey  the partitioning key
   * @param key           the data key
   * @param value         the data value
   *
   * @return a statement that, when executed, will insert the row
   *         matching {@code partitionKey} and {@code key} in the
   *         {@code table} with value {@code value}
   */
  @Override
  @CheckReturnValue
  public BoundStatement insert(
      final String table,
      final int partitionKey,
      final Bytes key,
      final byte[] value
  ) {
    return insert.get(table)
        .bind()
        .setByteBuffer(DATA_KEY.bind(), ByteBuffer.wrap(key.get()))
        .setByteBuffer(DATA_VALUE.bind(), ByteBuffer.wrap(value));
  }

  /**
   * Retrieves the value of the given {@code partitionKey} and {@code key}
   * from {@code table}.
   *
   * @param tableName the table to retrieve from
   * @param partition the partition
   * @param key       the data key
   *
   * @return the value previously set
   */
  @Override
  public byte[] get(final String tableName, final int partition, final Bytes key) {
    final BoundStatement get = this.get.get(tableName)
        .bind()
        .setByteBuffer(DATA_KEY.bind(), ByteBuffer.wrap(key.get()));

    final List<Row> result = client.execute(get).all();
    if (result.size() > 1) {
      throw new IllegalArgumentException();
    } else if (result.isEmpty()) {
      return null;
    } else {
      final ByteBuffer value = result.get(0).getByteBuffer(DATA_VALUE.column());
      return Objects.requireNonNull(value).array();
    }
  }

  @Override
  public KeyValueIterator<Bytes, byte[]> range(
      final String tableName,
      final int partition,
      final Bytes from,
      final Bytes to
  ) {
    throw new UnsupportedOperationException("range scans are not supported on Idempotent schemas.");
  }

  @Override
  public KeyValueIterator<Bytes, byte[]> all(
      final String tableName,
      final int partition
  ) {
    throw new UnsupportedOperationException("all is not supported on Idempotent schemas");
  }

  private static ByteBuffer metadataKey(final int partition) {
    final ByteBuffer key = ByteBuffer.wrap(new byte[METADATA_KEY.get().length + Integer.BYTES]);
    key.put(METADATA_KEY.get());
    key.putInt(partition);
    return key.position(0);
  }

}