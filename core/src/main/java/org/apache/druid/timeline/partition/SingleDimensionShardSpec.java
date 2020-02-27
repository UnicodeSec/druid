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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.Numbers;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@link ShardSpec} for range partitioning based on a single dimension
 */
public class SingleDimensionShardSpec implements ShardSpec
{
  public static final short UNKNOWN_NUM_BUCKETS = -1;
  public static final Comparator<SingleDimensionShardSpec> COMPARATOR = (s1, s2) -> {
    if (s1.start == null) {
      return -1;
    } else if (s2.start == null) {
      return 1;
    } else {
      return s1.start.compareTo(s2.start);
    }
  };

  private final String dimension;
  @Nullable
  private final String start;
  @Nullable
  private final String end;
  private final int partitionNum;
  private final short numBuckets;

  /**
   * @param dimension    partition dimension
   * @param start        inclusive start of this range
   * @param end          exclusive end of this range
   * @param partitionNum unique ID for this shard
   * @param numBuckets   number of range-partitioned buckets
   */
  @JsonCreator
  public SingleDimensionShardSpec(
      @JsonProperty("dimension") String dimension,
      @JsonProperty("start") @Nullable String start,
      @JsonProperty("end") @Nullable String end,
      @JsonProperty("partitionNum") int partitionNum,
      @JsonProperty("numBuckets") @Nullable Integer numBuckets
  )
  {
    Preconditions.checkArgument(partitionNum >= 0, "partitionNum >= 0");
    this.dimension = Preconditions.checkNotNull(dimension, "dimension");
    this.start = start;
    this.end = end;
    this.partitionNum = partitionNum;
    this.numBuckets = numBuckets == null
                      ? UNKNOWN_NUM_BUCKETS
                      : Numbers.toShortExact(numBuckets, () -> "Illegal numBuckets[" + numBuckets + "]");
  }

  @JsonProperty("dimension")
  public String getDimension()
  {
    return dimension;
  }

  @Nullable
  @JsonProperty("start")
  public String getStart()
  {
    return start;
  }

  @Nullable
  @JsonProperty("end")
  public String getEnd()
  {
    return end;
  }

  @Override
  @JsonProperty("partitionNum")
  public int getPartitionNum()
  {
    return partitionNum;
  }

  @JsonProperty("numBuckets")
  public short getNumBuckets()
  {
    return numBuckets;
  }

  @Override
  public ShardSpecLookup getLookup(final List<ShardSpec> shardSpecs)
  {
    final List<SingleDimensionShardSpec> sortedSpecs = shardSpecs
        .stream()
        .map(shardSpec -> (SingleDimensionShardSpec) shardSpec)
        .sorted(COMPARATOR)
        .collect(Collectors.toList());
    final PartitionBoundaries partitionBoundaries = PartitionBoundaries.fromSortedShardSpecs(sortedSpecs);
    return (timestamp, row) -> {
      if (partitionBoundaries.isEmpty()) {
        throw new ISE("row[%s] doesn't fit in any shard[%s]", row, sortedSpecs);
      }
      return sortedSpecs.get(partitionBoundaries.bucketFor(getKey(row, dimension)));
    };
  }

  @Nullable
  public static String getKey(InputRow row, String dimension)
  {
    final List<String> values = row.getDimension(dimension);
    if (values == null || values.size() != 1) {
      return null;
    } else {
      return values.get(0);
    }
  }

  @Override
  public List<String> getDomainDimensions()
  {
    return ImmutableList.of(dimension);
  }

  private Range<String> getRange()
  {
    Range<String> range;
    if (start == null && end == null) {
      range = Range.all();
    } else if (start == null) {
      range = Range.atMost(end);
    } else if (end == null) {
      range = Range.atLeast(start);
    } else {
      range = Range.closed(start, end);
    }
    return range;
  }

  @Override
  public boolean possibleInDomain(Map<String, RangeSet<String>> domain)
  {
    RangeSet<String> rangeSet = domain.get(dimension);
    if (rangeSet == null) {
      return true;
    }
    return !rangeSet.subRangeSet(getRange()).isEmpty();
  }

  @Override
  public boolean isCompatible(Class<? extends ShardSpec> other)
  {
    return other == SingleDimensionShardSpec.class;
  }

  @Override
  public boolean isSamePartitionBucket(PartialShardSpec partialShardSpec)
  {
    if (partialShardSpec instanceof SingleDimensionPartialShardSpec) {
      final SingleDimensionPartialShardSpec that = (SingleDimensionPartialShardSpec) partialShardSpec;
      return Objects.equals(dimension, that.getPartitionDimension()) &&
             numBuckets == that.getNumBuckets() &&
             getBucketId() == that.getBucketId() &&
             Objects.equals(start, that.getStart()) &&
             Objects.equals(end, that.getEnd());
    }
    return false;
  }

  @Override
  public <T> PartitionChunk<T> createChunk(T obj)
  {
    return new StringPartitionChunk<T>(start, end, partitionNum, obj);
  }

  @Override
  public boolean isInChunk(long timestamp, InputRow inputRow)
  {
    final List<String> values = inputRow.getDimension(dimension);

    if (values == null || values.size() != 1) {
      return checkValue(null);
    } else {
      return checkValue(values.get(0));
    }
  }

  @Override
  public short getBucketId()
  {
    if (numBuckets == UNKNOWN_NUM_BUCKETS) {
      return (short) partitionNum;
    } else {
      return PartitionUtils.getBucketId(partitionNum, numBuckets);
    }
  }

  private boolean checkValue(@Nullable String value)
  {
    if (value == null) {
      return start == null;
    }

    if (start == null) {
      return end == null || value.compareTo(end) < 0;
    }

    return value.compareTo(start) >= 0 &&
           (end == null || value.compareTo(end) < 0);
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
    SingleDimensionShardSpec that = (SingleDimensionShardSpec) o;
    return partitionNum == that.partitionNum &&
           numBuckets == that.numBuckets &&
           Objects.equals(dimension, that.dimension) &&
           Objects.equals(start, that.start) &&
           Objects.equals(end, that.end);
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(dimension, start, end, partitionNum, numBuckets);
  }

  @Override
  public String toString()
  {
    return "SingleDimensionShardSpec{" +
           "dimension='" + dimension + '\'' +
           ", start='" + start + '\'' +
           ", end='" + end + '\'' +
           ", partitionNum=" + partitionNum +
           ", numBuckets=" + numBuckets +
           '}';
  }
}
