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

package dev.responsive.internal.stores;

import static dev.responsive.internal.utils.SharedClients.loadSharedClients;
import static org.apache.kafka.streams.processor.internals.ProcessorContextUtils.asInternalProcessorContext;
import static org.apache.kafka.streams.processor.internals.ProcessorContextUtils.changelogFor;

import dev.responsive.api.config.ResponsiveConfig;
import dev.responsive.api.stores.ResponsiveWindowParams;
import dev.responsive.internal.config.InternalConfigs;
import dev.responsive.internal.db.CassandraClient;
import dev.responsive.internal.db.RemoteWindowedSchema;
import dev.responsive.internal.db.StampedKeySpec;
import dev.responsive.internal.db.partitioning.SubPartitioner;
import dev.responsive.internal.utils.Iterators;
import dev.responsive.internal.utils.Result;
import dev.responsive.internal.utils.SharedClients;
import dev.responsive.internal.utils.Stamped;
import dev.responsive.internal.utils.TableName;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.streams.errors.ProcessorStateException;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.StateStore;
import org.apache.kafka.streams.processor.StateStoreContext;
import org.apache.kafka.streams.processor.internals.InternalProcessorContext;
import org.apache.kafka.streams.query.Position;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.WindowStore;
import org.apache.kafka.streams.state.WindowStoreIterator;
import org.apache.kafka.streams.state.internals.StoreQueryUtils;
import org.slf4j.Logger;

public class ResponsiveWindowStore implements WindowStore<Bytes, byte[]> {

  private final Logger log;

  private final ResponsiveWindowParams params;
  private final TableName name;
  private final Position position; // TODO(IQ): update the position during restoration
  private final long windowSize;
  private final long retentionPeriod;

  @SuppressWarnings("rawtypes")
  private InternalProcessorContext context;
  private TopicPartition partition;

  private CommitBuffer<Stamped<Bytes>, RemoteWindowedSchema> buffer;
  private RemoteWindowedSchema schema;
  private ResponsiveStoreRegistry storeRegistry;
  private ResponsiveStoreRegistration registration;
  private SubPartitioner partitioner;

  private boolean open;
  private long observedStreamTime;

  public ResponsiveWindowStore(final ResponsiveWindowParams params) {
    this.params = params;
    this.name = params.name();

    // TODO: figure out how to implement retention period in Cassandra
    // there are a few options for this: we can use the wall-clock based
    // TTL feature, but this would be a departure from how Kafka Streams
    // handles the retention period (based on stream time). Alternatively
    // we can post-filter from Cassandra and occasionally run a process
    // that cleans up expired records, this would work well in a background
    // process but we'd need to figure out where to run that - we could also
    // run these deletes asynchronously
    //
    // for now (so we can get correct behavior) we just post-filter anything
    // that is past the TTL
    this.retentionPeriod = params.retentionPeriod();
    this.windowSize = params.windowSize();
    this.position = Position.emptyPosition();
    log = new LogContext(
        String.format("window-store [%s]", name.kafkaName())
    ).logger(ResponsiveWindowStore.class);
  }

  @Override
  public String name() {
    return name.kafkaName();
  }

  @Override
  @SuppressWarnings("deprecation")
  public void init(final ProcessorContext context, final StateStore root) {
    if (context instanceof StateStoreContext) {
      init((StateStoreContext) context, root);
    } else {
      throw new UnsupportedOperationException(
          "Use ResponsiveWindowStore#init(StateStoreContext, StateStore) instead."
      );
    }
  }

  @Override
  public void init(final StateStoreContext storeContext, final StateStore root) {
    try {
      log.info("Initializing state store");
      context = asInternalProcessorContext(storeContext);

      final ResponsiveConfig config = ResponsiveConfig.responsiveConfig(storeContext.appConfigs());
      final SharedClients sharedClients = loadSharedClients(storeContext.appConfigs());
      final CassandraClient client = sharedClients.cassandraClient;

      storeRegistry = InternalConfigs.loadStoreRegistry(context.appConfigs());
      partition =  new TopicPartition(
          changelogFor(storeContext, name.kafkaName(), false),
          context.taskId().partition()
      );
      partitioner = config.getSubPartitioner(sharedClients.admin, name, partition.topic());

      schema = client.prepareWindowedTableSchema(params);
      log.info("Remote table {} is available for querying.", name.cassandraName());

      buffer = CommitBuffer.from(
          sharedClients,
          name,
          partition,
          schema,
          new StampedKeySpec(this::withinRetention),
          params.truncateChangelog(),
          partitioner,
          config
      );
      buffer.init();

      open = true;

      final long offset = buffer.offset();
      registration = new ResponsiveStoreRegistration(
          name.kafkaName(),
          partition,
          offset == -1 ? 0 : offset,
          buffer::flush
      );
      storeRegistry.registerStore(registration);

      storeContext.register(root, buffer);
    } catch (InterruptedException | TimeoutException e) {
      throw new ProcessorStateException("Failed to initialize store.", e);
    }
  }

  @Override
  public boolean persistent() {
    // Kafka Streams uses this to determine whether it
    // needs to create and lock state directories. since
    // the Responsive Client doesn't require flushing state
    // to disk, we return false even though the store is
    // persistent in a remote store
    return false;
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public void put(final Bytes key, final byte[] value, final long windowStartTimestamp) {
    observedStreamTime = Math.max(observedStreamTime, windowStartTimestamp);

    final Stamped<Bytes> windowedKey = new Stamped<>(key, windowStartTimestamp);

    putInternal(windowedKey, value);
  }

  private void putInternal(final Stamped<Bytes> windowedKey, final byte[] value) {
    if (value != null) {
      buffer.put(windowedKey, value, context.timestamp());
    } else {
      buffer.tombstone(windowedKey, context.timestamp());
    }
    StoreQueryUtils.updatePosition(position, context);
  }

  @Override
  public byte[] fetch(final Bytes key, final long time) {
    final Result<Stamped<Bytes>> localResult = buffer.get(new Stamped<>(key, time));
    if (localResult != null)  {
      return localResult.isTombstone ? null : localResult.value;
    }

    return schema.fetch(
        name.cassandraName(),
        partitioner.partition(partition.partition(), key),
        key,
        time
    );
  }

  @Override
  public WindowStoreIterator<byte[]> fetch(
      final Bytes key,
      final long timeFrom,
      final long timeTo
  ) {
    final long start = Math.max(observedStreamTime - retentionPeriod, timeFrom);
    final Stamped<Bytes> from = new Stamped<>(key, start);
    final Stamped<Bytes> to = new Stamped<>(key, timeTo);

    final int subPartition = partitioner.partition(partition.partition(), key);
    return Iterators.windowed(
        new LocalRemoteKvIterator<>(
            buffer.range(from, to),
            schema.fetch(name.cassandraName(), subPartition, key, start, timeTo),
            ResponsiveWindowStore::compareKeys
        )
    );
  }

  @Override
  public KeyValueIterator<Windowed<Bytes>, byte[]> fetch(
      final Bytes keyFrom,
      final Bytes keyTo,
      final long timeFrom,
      final long timeTo
  ) {
    throw new UnsupportedOperationException("Not yet implemented.");
  }

  @Override
  public WindowStoreIterator<byte[]> backwardFetch(
      final Bytes key,
      final long timeFrom,
      final long timeTo
  ) {
    final long start = Math.max(observedStreamTime - retentionPeriod, timeFrom);
    final Stamped<Bytes> from = new Stamped<>(key, start);
    final Stamped<Bytes> to = new Stamped<>(key, timeTo);

    final int subPartition = partitioner.partition(partition.partition(), key);
    return Iterators.windowed(
        new LocalRemoteKvIterator<>(
            buffer.backRange(from, to),
            schema.backFetch(name.cassandraName(), subPartition, key, start, timeTo),
            ResponsiveWindowStore::compareKeys
        )
    );
  }

  @Override
  public KeyValueIterator<Windowed<Bytes>, byte[]> backwardFetch(
      final Bytes keyFrom,
      final Bytes keyTo,
      final long timeFrom,
      final long timeTo
  ) {
    throw new UnsupportedOperationException("Not yet implemented");
  }

  @Override
  public KeyValueIterator<Windowed<Bytes>, byte[]> fetchAll(
      final long timeFrom,
      final long timeTo
  ) {
    throw new UnsupportedOperationException("Not yet implemented");
  }

  @Override
  public KeyValueIterator<Windowed<Bytes>, byte[]> backwardFetchAll(
      final long timeFrom,
      final long timeTo
  ) {
    throw new UnsupportedOperationException("Not yet implemented");
  }

  @Override
  public KeyValueIterator<Windowed<Bytes>, byte[]> all() {
    throw new UnsupportedOperationException("Not yet implemented");
  }

  @Override
  public KeyValueIterator<Windowed<Bytes>, byte[]> backwardAll() {
    throw new UnsupportedOperationException("Not yet implemented");
  }

  @Override
  public void flush() {
  }

  @Override
  public void close() {
    // no need to flush the buffer here, will happen through the kafka client commit as usual
    if (storeRegistry != null) {
      storeRegistry.deregisterStore(registration);
    }
  }

  @Override
  public Position getPosition() {
    return position;
  }

  private boolean withinRetention(final Stamped<Bytes> key) {
    return key.stamp > observedStreamTime - retentionPeriod;
  }

  public static int compareKeys(final Stamped<Bytes> o1, final Stamped<Bytes> o2) {
    final int key = o1.key.compareTo(o2.key);
    if (key != 0) {
      return key;
    }

    return Long.compare(o1.stamp, o2.stamp);
  }

}