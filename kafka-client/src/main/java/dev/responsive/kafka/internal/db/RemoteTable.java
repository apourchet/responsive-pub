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

package dev.responsive.kafka.internal.db;

import javax.annotation.CheckReturnValue;

public interface RemoteTable<K, S> {

  String name();

  /**
   * Initializes the table by setting the metadata fields to
   * their initialized values.
   *
   * @return a {@link WriterFactory} that gives the callee access
   * to run statements on {@code table}
   *
   * TODO: this is the only place where the partition type is needed so it
   *  doesn't make sense to generic-ify the entire RemoteTable class just for
   *  this. Of course it's also not ideal to leave the generic as a ? because we
   *  now have to cast/suppress "unchecked" warnings everywhere this is used.
   *  We should explore cleaning up partition types in general, and move this
   *  this method out of RemoteTable either by creating the WriterFactory up
   *  front and passing it in as a param, or possibly moving it to the
   *  {@link TableMetadata} interface which is already parameterized by table
   *  partition type
   */
  WriterFactory<K, ?> init(
      final int kafkaPartition
  );

  /**
   * Inserts data into {@code table}. Note that this will overwrite
   * any existing entry in the table with the same key.
   *
   * @param kafkaPartition  the kafka partition
   * @param key             the data key
   * @param value           the data value
   * @param epochMillis     the event time with which this event
   *                      was inserted in epochMillis
   *
   * @return a statement that, when executed, will insert the entry
   *         corresponding to the given {@code kafkaPartition} and
   *         {@code key} to this {@code table} with value {@code value}
   */
  @CheckReturnValue
  S insert(
      final int kafkaPartition,
      final K key,
      final byte[] value,
      final long epochMillis
  );

  /**
   * @param kafkaPartition  the kafka partition
   * @param key             the data key
   *
   * @return a statement that, when executed, will delete the entry
   *         corresponding to the given {@code kafkaPartition} and
   *         {@code key} in this {@code table}
   */
  @CheckReturnValue
  S delete(
      final int kafkaPartition,
      final K key
  );

  /**
   * @param kafkaPartition the kafka partition
   * @return the current offset fetched from the metadata table
   *         partition for the given kafka partition
   */
  long fetchOffset(final int kafkaPartition);

  /**
   * @param kafkaPartition the kafka partition
   * @return a statement that can be used to set the offset
   *         in the metadata row of {@code table}.
   */
  @CheckReturnValue
  S setOffset(
      final int kafkaPartition,
      final long offset
  );
}
