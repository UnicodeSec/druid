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

package org.apache.druid.indexing.common.task.batch.partition;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import org.apache.druid.indexer.partitions.SingleDimensionPartitionsSpec;
import org.apache.druid.indexing.common.TaskToolbox;
import org.apache.druid.segment.realtime.appenderator.SegmentIdWithShardSpec;
import org.apache.druid.timeline.partition.PartitionBoundaries;
import org.apache.druid.timeline.partition.SingleDimensionShardSpec;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class RangePartitionAnalysis
    implements CompletePartitionAnalysis<RangePartitionBucketAnalysis, SingleDimensionPartitionsSpec>
{
  private final Map<Interval, RangePartitionBucketAnalysis> intervalToPartitionBoundaries = new HashMap<>();
  private final SingleDimensionPartitionsSpec partitionsSpec;

  public RangePartitionAnalysis(SingleDimensionPartitionsSpec partitionsSpec)
  {
    this.partitionsSpec = partitionsSpec;
  }

  @Override
  public SingleDimensionPartitionsSpec getPartitionsSpec()
  {
    return partitionsSpec;
  }

  @Override
  public void updateBucket(Interval interval, RangePartitionBucketAnalysis bucketAnalysis)
  {
    intervalToPartitionBoundaries.put(interval, bucketAnalysis);
  }

  @Override
  public RangePartitionBucketAnalysis getBucketAnalysis(Interval interval)
  {
    return Preconditions.checkNotNull(
        intervalToPartitionBoundaries.get(interval),
        "Missing numBuckets for interval[%s]",
        interval
    );
  }

  @Override
  public Set<Interval> getAllIntervalsToIndex()
  {
    return Collections.unmodifiableSet(intervalToPartitionBoundaries.keySet());
  }

  public void forEach(BiConsumer<Interval, RangePartitionBucketAnalysis> consumer)
  {
    intervalToPartitionBoundaries.forEach(consumer);
  }

  @Override
  public int numTimePartitions()
  {
    return intervalToPartitionBoundaries.size();
  }

  @Override
  public Map<Interval, List<SegmentIdWithShardSpec>> convertToIntervalToSegmentIds(
      TaskToolbox toolbox,
      String dataSource,
      Function<Interval, String> versionFinder
  )
  {
    final String partitionDimension = partitionsSpec.getPartitionDimension();
    final Map<Interval, List<SegmentIdWithShardSpec>> intervalToSegmentIds = Maps.newHashMapWithExpectedSize(
        numTimePartitions()
    );

    forEach((interval, bucketAnalysis) ->
                intervalToSegmentIds.put(
                    interval,
                    translatePartitionBoundaries(
                        dataSource,
                        interval,
                        partitionDimension,
                        bucketAnalysis.getPartitionBoundaries(),
                        versionFinder
                    )
                )
    );

    return intervalToSegmentIds;
  }

  /**
   * Translate {@link PartitionBoundaries} into the corresponding
   * {@link SingleDimensionPartitionsSpec} with segment id.
   */
  private static List<SegmentIdWithShardSpec> translatePartitionBoundaries(
      String dataSource,
      Interval interval,
      String partitionDimension,
      PartitionBoundaries partitionBoundaries,
      Function<Interval, String> versionFinder
  )
  {
    if (partitionBoundaries.isEmpty()) {
      return Collections.emptyList();
    }

    return IntStream.range(0, partitionBoundaries.size() - 1)
                    .mapToObj(i -> createSegmentIdWithShardSpec(
                        dataSource,
                        interval,
                        versionFinder.apply(interval),
                        partitionDimension,
                        partitionBoundaries.get(i),
                        partitionBoundaries.get(i + 1),
                        i,
                        partitionBoundaries.getNumBuckets()
                    ))
                    .collect(Collectors.toList());
  }

  private static SegmentIdWithShardSpec createSegmentIdWithShardSpec(
      String dataSource,
      Interval interval,
      String version,
      String partitionDimension,
      String partitionStart,
      @Nullable String partitionEnd,
      int partitionNum,
      int numBuckets
  )
  {
    // The shardSpec created here will be reused in PartialGenericSegmentMergeTask. This is ok because
    // all PartialSegmentGenerateTasks create the same set of segmentIds (and thus shardSpecs).
    return new SegmentIdWithShardSpec(
        dataSource,
        interval,
        version,
        new SingleDimensionShardSpec(
            partitionDimension,
            partitionStart,
            partitionEnd,
            partitionNum,
            numBuckets
        )
    );
  }
}
