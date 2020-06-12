/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.timeline.partition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

public class HashBasedNumberedPartialShardSpec implements PartialShardSpec
{
  public static final String TYPE = "hashed";

  @Nullable
  private final List<String> partitionDimensions;
  private final int bucketId;
  private final int numBuckets;

  @JsonCreator
  public HashBasedNumberedPartialShardSpec(
      @JsonProperty("partitionDimensions") @Nullable List<String> partitionDimensions,
      @JsonProperty("bucketId") int bucketId,
      @JsonProperty("numPartitions") int numBuckets
  )
  {
    this.partitionDimensions = partitionDimensions;
    this.bucketId = bucketId;
    this.numBuckets = numBuckets;
  }

  @Nullable
  @JsonProperty
  public List<String> getPartitionDimensions()
  {
    return partitionDimensions;
  }

  @JsonProperty("numPartitions")
  public int getNumBuckets()
  {
    return numBuckets;
  }

  @JsonProperty
  public int getBucketId()
  {
    return bucketId;
  }

  @Override
  public ShardSpec complete(ObjectMapper objectMapper, @Nullable ShardSpec specOfPreviousMaxPartitionId)
  {
    if (specOfPreviousMaxPartitionId == null) {
      // The shardSpec is created by the Overlord.
      // For batch tasks, this code can is executed only with segment locking (forceTimeChunkLock = false).
      // In this mode, you can have 2 or more tasks concurrently ingesting into the same time chunk of
      // the same datasource. Since there is no restriction for those tasks in segment allocation, the
      // allocated IDs for each task can interleave. As a result, the core partition set cannot be
      // represented as a range. We always set 0 for the core partition set size.
      return new HashBasedNumberedShardSpec(
          0,
          0,
          bucketId,
          numBuckets,
          partitionDimensions,
          objectMapper
      );
    } else {
      return new HashBasedNumberedShardSpec(
          specOfPreviousMaxPartitionId.getPartitionNum() + 1,
          specOfPreviousMaxPartitionId.getNumCorePartitions(),
          bucketId,
          numBuckets,
          partitionDimensions,
          objectMapper
      );
    }
  }

  @Override
  public ShardSpec complete(ObjectMapper objectMapper, int partitionId)
  {
    return new HashBasedNumberedShardSpec(partitionId, 0, bucketId, numBuckets, partitionDimensions, objectMapper);
  }

  @Override
  public Class<? extends ShardSpec> getShardSpecClass()
  {
    return HashBasedNumberedShardSpec.class;
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    HashBasedNumberedPartialShardSpec that = (HashBasedNumberedPartialShardSpec) o;
    return bucketId == that.bucketId &&
           numBuckets == that.numBuckets &&
           Objects.equals(partitionDimensions, that.partitionDimensions);
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(partitionDimensions, bucketId, numBuckets);
  }
}
