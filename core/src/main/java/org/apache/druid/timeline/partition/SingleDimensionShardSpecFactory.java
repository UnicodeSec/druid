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
import java.util.Objects;

public class SingleDimensionShardSpecFactory implements ShardSpecFactory
{
  private final String partitionDimension;
  private final PartitionBoundaries partitionBoundaries;

  @JsonCreator
  public SingleDimensionShardSpecFactory(
      @JsonProperty("partitionDimension") String partitionDimension,
      @JsonProperty("partitionBoundaries") PartitionBoundaries partitionBoundaries
  )
  {
    this.partitionDimension = partitionDimension;
    this.partitionBoundaries = partitionBoundaries;
  }

  @JsonProperty
  public String getPartitionDimension()
  {
    return partitionDimension;
  }

  @JsonProperty
  public PartitionBoundaries getPartitionBoundaries()
  {
    return partitionBoundaries;
  }

  @Override
  public ShardSpec create(ObjectMapper objectMapper, @Nullable ShardSpec specOfPreviousMaxPartitionId, int bucketId)
  {
    final int partitionId = PartitionUtils.nextValidPartitionId(
        specOfPreviousMaxPartitionId == null ? null : specOfPreviousMaxPartitionId.getPartitionNum(),
        bucketId,
        partitionBoundaries.numBuckets()
    );

    return new SingleDimensionShardSpec(
        partitionDimension,
        partitionBoundaries.get(bucketId),
        partitionBoundaries.get(bucketId + 1),
        partitionId,
        partitionBoundaries.numBuckets()
    );
  }

  @Override
  public Class<? extends ShardSpec> getShardSpecClass()
  {
    return SingleDimensionShardSpec.class;
  }

  @Override
  public int numBuckets()
  {
    return partitionBoundaries.numBuckets();
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
    SingleDimensionShardSpecFactory that = (SingleDimensionShardSpecFactory) o;
    return Objects.equals(partitionDimension, that.partitionDimension) &&
           Objects.equals(partitionBoundaries, that.partitionBoundaries);
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(partitionDimension, partitionBoundaries);
  }
}
