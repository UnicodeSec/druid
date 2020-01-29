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

package org.apache.druid.indexing.common.task;

import org.apache.druid.data.input.InputRow;
import org.apache.druid.indexer.partitions.HashedPartitionsSpec;
import org.apache.druid.indexer.partitions.PartitionsSpec;
import org.apache.druid.indexer.partitions.SingleDimensionPartitionsSpec;
import org.apache.druid.indexing.appenderator.ActionBasedSegmentAllocator;
import org.apache.druid.indexing.common.TaskToolbox;
import org.apache.druid.indexing.common.actions.SegmentAllocateAction;
import org.apache.druid.indexing.common.actions.TaskAction;
import org.apache.druid.indexing.common.task.TaskLockHelper.OverwritingRootGenerationPartitions;
import org.apache.druid.indexing.common.task.batch.parallel.SupervisorTaskAccess;
import org.apache.druid.indexing.common.task.batch.partition.HashPartitionAnalysis;
import org.apache.druid.indexing.common.task.batch.partition.PartitionAnalysis;
import org.apache.druid.indexing.common.task.batch.partition.RangePartitionAnalysis;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.segment.indexing.DataSchema;
import org.apache.druid.segment.indexing.granularity.GranularitySpec;
import org.apache.druid.segment.realtime.appenderator.SegmentAllocator;
import org.apache.druid.segment.realtime.appenderator.SegmentIdWithShardSpec;
import org.apache.druid.timeline.partition.HashBasedNumberedShardSpec;
import org.apache.druid.timeline.partition.HashBasedNumberedShardSpecBuilder;
import org.apache.druid.timeline.partition.NumberedOverwriteShardSpecBuilder;
import org.apache.druid.timeline.partition.NumberedShardSpecBuilder;
import org.apache.druid.timeline.partition.PartitionBoundaries;
import org.apache.druid.timeline.partition.ShardSpecBuilder;
import org.apache.druid.timeline.partition.SingleDimensionShardSpec;
import org.apache.druid.timeline.partition.SingleDimensionShardSpecBuilder;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.io.IOException;

/**
 * Segment allocator which allocates new segments using the overlord per request.
 */
public class OverlordCoordinatingSegmentAllocator implements SegmentAllocator
{
  private final ActionBasedSegmentAllocator internalAllocator;

  OverlordCoordinatingSegmentAllocator(
      final TaskToolbox toolbox,
      final @Nullable SupervisorTaskAccess supervisorTaskAccess,
      final DataSchema dataSchema,
      final TaskLockHelper taskLockHelper,
      final boolean appendToExisting,
      final PartitionAnalysis partitionAnalysis
  )
  {
    this.internalAllocator = new ActionBasedSegmentAllocator(
        toolbox.getTaskActionClient(),
        dataSchema,
        (schema, row, sequenceName, previousSegmentId, skipSegmentLineageCheck) -> {
          final GranularitySpec granularitySpec = schema.getGranularitySpec();
          final Interval interval = granularitySpec
              .bucketInterval(row.getTimestamp())
              .or(granularitySpec.getSegmentGranularity().bucket(row.getTimestamp()));
          final ShardSpecBuilder shardSpecBuilder = createShardSpecBuilder(
              toolbox,
              taskLockHelper,
              appendToExisting,
              partitionAnalysis,
              interval,
              row
          );
          final TaskAction<SegmentIdWithShardSpec> action = new SegmentAllocateAction(
              schema.getDataSource(),
              row.getTimestamp(),
              schema.getGranularitySpec().getQueryGranularity(),
              schema.getGranularitySpec().getSegmentGranularity(),
              sequenceName,
              previousSegmentId,
              skipSegmentLineageCheck,
              shardSpecBuilder,
              taskLockHelper.getLockGranularityToUse()
          );
          if (supervisorTaskAccess != null) {
            return supervisorTaskAccess.wrapAction(action);
          } else {
            return action;
          }
        }
    );
  }

  @Nullable
  @Override
  public SegmentIdWithShardSpec allocate(
      InputRow row,
      String sequenceName,
      String previousSegmentId,
      boolean skipSegmentLineageCheck
  ) throws IOException
  {
    return internalAllocator.allocate(row, sequenceName, previousSegmentId, skipSegmentLineageCheck);
  }

  private static ShardSpecBuilder createShardSpecBuilder(
      TaskToolbox toolbox,
      TaskLockHelper taskLockHelper,
      boolean appendToExisting,
      PartitionAnalysis partitionAnalysis,
      Interval interval,
      InputRow row
  )
  {
    final PartitionsSpec partitionsSpec = partitionAnalysis.getPartitionsSpec();
    switch (partitionsSpec.getType()) {
      case LINEAR:
        return createLinearShardSpecBuilder(appendToExisting, taskLockHelper, interval);
      case HASH:
        return createHashShardSpecBuilder(
            toolbox,
            taskLockHelper,
            (HashPartitionAnalysis) partitionAnalysis,
            interval,
            row
        );
      case RANGE:
        return createRangeShardSpecBuilder(
            taskLockHelper,
            (RangePartitionAnalysis) partitionAnalysis,
            interval,
            row
        );
      default:
        throw new ISE(
            "%s is not supported for partitionsSpec[%s]",
            OverlordCoordinatingSegmentAllocator.class.getName(),
            partitionsSpec.getClass().getName()
        );
    }
  }

  private static ShardSpecBuilder createLinearShardSpecBuilder(
      boolean appendToExisting,
      TaskLockHelper taskLockHelper,
      Interval interval
  )
  {
    if (taskLockHelper.isUseSegmentLock()) {
      if (taskLockHelper.hasOverwritingRootGenerationPartition(interval) && !appendToExisting) {
        final OverwritingRootGenerationPartitions overwritingRootGenerationPartitions = taskLockHelper
            .getOverwritingRootGenerationPartition(interval);
        if (overwritingRootGenerationPartitions == null) {
          throw new ISE("Can't find overwritingSegmentMeta for interval[%s]", interval);
        }
        return new NumberedOverwriteShardSpecBuilder(
            overwritingRootGenerationPartitions.getStartRootPartitionId(),
            overwritingRootGenerationPartitions.getEndRootPartitionId(),
            overwritingRootGenerationPartitions.getMinorVersionForNewSegments()
        );
      }
    }
    return NumberedShardSpecBuilder.instance();
  }

  private static HashBasedNumberedShardSpecBuilder createHashShardSpecBuilder(
      TaskToolbox toolbox,
      TaskLockHelper taskLockHelper,
      HashPartitionAnalysis partitionAnalysis,
      Interval interval,
      InputRow row
  )
  {
    if (taskLockHelper.isUseSegmentLock()) {
      throw new UnsupportedOperationException("Hash partitioning is not supported with segment lock yet");
    } else {
      final HashedPartitionsSpec partitionsSpec = partitionAnalysis.getPartitionsSpec();
      final int hash = HashBasedNumberedShardSpec.hash(
          toolbox.getJsonMapper(),
          partitionsSpec.getPartitionDimensions(),
          row.getTimestampFromEpoch(),
          row
      );
      final int numBuckets = partitionAnalysis.getBucketAnalysis(interval);
      final int bucketId = hash % numBuckets;
      return new HashBasedNumberedShardSpecBuilder(
          partitionsSpec.getPartitionDimensions(),
          bucketId,
          numBuckets
      );
    }
  }

  private static SingleDimensionShardSpecBuilder createRangeShardSpecBuilder(
      TaskLockHelper taskLockHelper,
      RangePartitionAnalysis partitionAnalysis,
      Interval interval,
      InputRow row
  )
  {
    if (taskLockHelper.isUseSegmentLock()) {
      throw new UnsupportedOperationException("Range partitioning is not supported with segment lock yet");
    } else {
      final SingleDimensionPartitionsSpec partitionsSpec = partitionAnalysis.getPartitionsSpec();
      final PartitionBoundaries partitionBoundaries = partitionAnalysis.getBucketAnalysis(interval);
      if (partitionBoundaries.isEmpty()) {
        throw new ISE("Cannot create shardSpecBuilder from empty partition boundaries");
      }
      final int bucketId = partitionBoundaries.indexFor(
          SingleDimensionShardSpec.getKey(row, partitionsSpec.getPartitionDimension())
      );
      return new SingleDimensionShardSpecBuilder(
          partitionsSpec.getPartitionDimension(),
          bucketId,
          partitionBoundaries.get(bucketId),
          partitionBoundaries.get(bucketId + 1),
          partitionBoundaries.numBuckets()
      );
    }
  }
}
