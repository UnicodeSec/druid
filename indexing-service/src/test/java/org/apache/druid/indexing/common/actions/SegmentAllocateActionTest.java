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

package org.apache.druid.indexing.common.actions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.apache.druid.indexing.common.TaskLock;
import org.apache.druid.indexing.common.task.NoopTask;
import org.apache.druid.indexing.common.task.Task;
import org.apache.druid.jackson.DefaultObjectMapper;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.java.util.common.granularity.Granularity;
import org.apache.druid.java.util.emitter.EmittingLogger;
import org.apache.druid.java.util.emitter.service.ServiceEmitter;
import org.apache.druid.segment.realtime.appenderator.SegmentIdWithShardSpec;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.partition.HashBasedNumberedShardSpec;
import org.apache.druid.timeline.partition.HashBasedNumberedShardSpecFactory;
import org.apache.druid.timeline.partition.LinearShardSpec;
import org.apache.druid.timeline.partition.LinearShardSpecFactory;
import org.apache.druid.timeline.partition.NumberedShardSpec;
import org.apache.druid.timeline.partition.NumberedShardSpecFactory;
import org.apache.druid.timeline.partition.ShardSpec;
import org.apache.druid.timeline.partition.ShardSpecFactory;
import org.apache.druid.timeline.partition.SingleDimensionShardSpec;
import org.easymock.EasyMock;
import org.joda.time.DateTime;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class SegmentAllocateActionTest
{
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Rule
  public TaskActionTestKit taskActionTestKit = new TaskActionTestKit();

  private static final String DATA_SOURCE = "none";
  private static final DateTime PARTY_TIME = DateTimes.of("1999");
  private static final DateTime THE_DISTANT_FUTURE = DateTimes.of("3000");

  @Before
  public void setUp()
  {
    ServiceEmitter emitter = EasyMock.createMock(ServiceEmitter.class);
    EmittingLogger.registerEmitter(emitter);
    EasyMock.replay(emitter);
  }

  @Test
  public void testGranularitiesFinerThanDay()
  {
    Assert.assertEquals(
        ImmutableList.of(
            Granularities.DAY,
            Granularities.SIX_HOUR,
            Granularities.HOUR,
            Granularities.THIRTY_MINUTE,
            Granularities.FIFTEEN_MINUTE,
            Granularities.TEN_MINUTE,
            Granularities.FIVE_MINUTE,
            Granularities.MINUTE,
            Granularities.SECOND
        ),
        Granularity.granularitiesFinerThan(Granularities.DAY)
    );
  }

  @Test
  public void testGranularitiesFinerThanHour()
  {
    Assert.assertEquals(
        ImmutableList.of(
            Granularities.HOUR,
            Granularities.THIRTY_MINUTE,
            Granularities.FIFTEEN_MINUTE,
            Granularities.TEN_MINUTE,
            Granularities.FIVE_MINUTE,
            Granularities.MINUTE,
            Granularities.SECOND
        ),
        Granularity.granularitiesFinerThan(Granularities.HOUR)
    );
  }

  @Test
  public void testManySegmentsSameInterval()
  {
    final Task task = new NoopTask(null, null, 0, 0, null, null, null);

    taskActionTestKit.getTaskLockbox().add(task);

    final SegmentIdWithShardSpec id1 = allocate(
        task,
        PARTY_TIME,
        Granularities.NONE,
        Granularities.HOUR,
        "s1",
        null
    );
    final SegmentIdWithShardSpec id2 = allocate(
        task,
        PARTY_TIME,
        Granularities.NONE,
        Granularities.HOUR,
        "s1",
        id1.toString()
    );
    final SegmentIdWithShardSpec id3 = allocate(
        task,
        PARTY_TIME,
        Granularities.NONE,
        Granularities.HOUR,
        "s1",
        id2.toString()
    );

    final List<TaskLock> partyTimeLocks = taskActionTestKit.getTaskLockbox()
                                                           .findLocksForTask(task)
                                                           .stream()
                                                           .filter(input -> input.getInterval().contains(PARTY_TIME))
                                                           .collect(Collectors.toList());

    Assert.assertEquals(3, partyTimeLocks.size());

    for (TaskLock partyLock : partyTimeLocks) {
      assertSameIdentifier(
          new SegmentIdWithShardSpec(
              DATA_SOURCE,
              Granularities.HOUR.bucket(PARTY_TIME),
              partyLock.getVersion(),
              new NumberedShardSpec(0, 0)
          ),
          id1
      );
      assertSameIdentifier(
          new SegmentIdWithShardSpec(
              DATA_SOURCE,
              Granularities.HOUR.bucket(PARTY_TIME),
              partyLock.getVersion(),
              new NumberedShardSpec(1, 0)
          ),
          id2
      );
      assertSameIdentifier(
          new SegmentIdWithShardSpec(
              DATA_SOURCE,
              Granularities.HOUR.bucket(PARTY_TIME),
              partyLock.getVersion(),
              new NumberedShardSpec(2, 0)
          ),
          id3
      );
    }
  }

  @Test
  public void testResumeSequence()
  {
    final Task task = new NoopTask(null, null, 0, 0, null, null, null);

    taskActionTestKit.getTaskLockbox().add(task);

    final SegmentIdWithShardSpec id1 = allocate(
        task,
        PARTY_TIME,
        Granularities.NONE,
        Granularities.HOUR,
        "s1",
        null
    );
    Assert.assertNotNull(id1);
    final SegmentIdWithShardSpec id2 = allocate(
        task,
        THE_DISTANT_FUTURE,
        Granularities.NONE,
        Granularities.HOUR,
        "s1",
        id1.toString()
    );
    Assert.assertNotNull(id2);
    final SegmentIdWithShardSpec id3 = allocate(
        task,
        PARTY_TIME,
        Granularities.NONE,
        Granularities.HOUR,
        "s1",
        id2.toString()
    );
    Assert.assertNotNull(id3);
    final SegmentIdWithShardSpec id4 = allocate(
        task,
        PARTY_TIME,
        Granularities.NONE,
        Granularities.HOUR,
        "s1",
        id1.toString()
    );
    Assert.assertNull(id4);
    final SegmentIdWithShardSpec id5 = allocate(
        task,
        THE_DISTANT_FUTURE,
        Granularities.NONE,
        Granularities.HOUR,
        "s1",
        id1.toString()
    );
    Assert.assertNotNull(id5);
    final SegmentIdWithShardSpec id6 = allocate(
        task,
        THE_DISTANT_FUTURE,
        Granularities.NONE,
        Granularities.MINUTE,
        "s1",
        id1.toString()
    );
    Assert.assertNull(id6);
    final SegmentIdWithShardSpec id7 = allocate(
        task,
        THE_DISTANT_FUTURE,
        Granularities.NONE,
        Granularities.DAY,
        "s1",
        id1.toString()
    );
    Assert.assertNotNull(id7);

    final List<TaskLock> partyLocks = taskActionTestKit.getTaskLockbox()
                                                       .findLocksForTask(task)
                                                       .stream()
                                                       .filter(input -> input.getInterval().contains(PARTY_TIME))
                                                       .collect(Collectors.toList());

    Assert.assertEquals(2, partyLocks.size());

    for (TaskLock partyLock : partyLocks) {
      assertSameIdentifier(
          new SegmentIdWithShardSpec(
              DATA_SOURCE,
              Granularities.HOUR.bucket(PARTY_TIME),
              partyLock.getVersion(),
              new NumberedShardSpec(0, 0)
          ),
          id1
      );
      assertSameIdentifier(
          new SegmentIdWithShardSpec(
              DATA_SOURCE,
              Granularities.HOUR.bucket(PARTY_TIME),
              partyLock.getVersion(),
              new NumberedShardSpec(1, 0)
          ),
          id3
      );
    }

    final List<TaskLock> futureLocks = taskActionTestKit
        .getTaskLockbox()
        .findLocksForTask(task)
        .stream()
        .filter(input -> input.getInterval().contains(THE_DISTANT_FUTURE))
        .collect(Collectors.toList());

    Assert.assertEquals(3, futureLocks.size());

    for (TaskLock futureLock : futureLocks) {
      assertSameIdentifier(
          new SegmentIdWithShardSpec(
              DATA_SOURCE,
              Granularities.HOUR.bucket(THE_DISTANT_FUTURE),
              futureLock.getVersion(),
              new NumberedShardSpec(0, 0)
          ),
          id2
      );
    }

    Assert.assertNull(id4);
    assertSameIdentifier(id2, id5);
    Assert.assertNull(id6);
    assertSameIdentifier(id2, id7);
  }

  @Test
  public void testMultipleSequences()
  {
    final Task task = new NoopTask(null, null, 0, 0, null, null, null);

    taskActionTestKit.getTaskLockbox().add(task);

    final SegmentIdWithShardSpec id1 = allocate(task, PARTY_TIME, Granularities.NONE, Granularities.HOUR, "s1", null);
    final SegmentIdWithShardSpec id2 = allocate(task, PARTY_TIME, Granularities.NONE, Granularities.HOUR, "s2", null);
    final SegmentIdWithShardSpec id3 = allocate(
        task,
        PARTY_TIME,
        Granularities.NONE,
        Granularities.HOUR,
        "s1",
        id1.toString()
    );
    final SegmentIdWithShardSpec id4 = allocate(
        task,
        THE_DISTANT_FUTURE,
        Granularities.NONE,
        Granularities.HOUR,
        "s1",
        id3.toString()
    );
    final SegmentIdWithShardSpec id5 = allocate(
        task,
        THE_DISTANT_FUTURE,
        Granularities.NONE,
        Granularities.HOUR,
        "s2",
        id2.toString()
    );
    final SegmentIdWithShardSpec id6 = allocate(
        task,
        PARTY_TIME,
        Granularities.NONE,
        Granularities.HOUR,
        "s1",
        null
    );

    final List<TaskLock> partyLocks = taskActionTestKit.getTaskLockbox()
                                                       .findLocksForTask(task)
                                                       .stream()
                                                       .filter(input -> input.getInterval().contains(PARTY_TIME))
                                                       .collect(Collectors.toList());

    Assert.assertEquals(4, partyLocks.size());

    for (TaskLock partyLock : partyLocks) {
      assertSameIdentifier(
          new SegmentIdWithShardSpec(
              DATA_SOURCE,
              Granularities.HOUR.bucket(PARTY_TIME),
              partyLock.getVersion(),
              new NumberedShardSpec(0, 0)
          ),
          id1
      );
      assertSameIdentifier(
          new SegmentIdWithShardSpec(
              DATA_SOURCE,
              Granularities.HOUR.bucket(PARTY_TIME),
              partyLock.getVersion(),
              new NumberedShardSpec(1, 0)
          ),
          id2
      );
      assertSameIdentifier(
          new SegmentIdWithShardSpec(
              DATA_SOURCE,
              Granularities.HOUR.bucket(PARTY_TIME),
              partyLock.getVersion(),
              new NumberedShardSpec(2, 0)
          ),
          id3
      );
    }

    final List<TaskLock> futureLocks = taskActionTestKit
        .getTaskLockbox()
        .findLocksForTask(task)
        .stream()
        .filter(input -> input.getInterval().contains(THE_DISTANT_FUTURE))
        .collect(Collectors.toList());

    Assert.assertEquals(2, futureLocks.size());

    for (TaskLock futureLock : futureLocks) {
      assertSameIdentifier(
          new SegmentIdWithShardSpec(
              DATA_SOURCE,
              Granularities.HOUR.bucket(THE_DISTANT_FUTURE),
              futureLock.getVersion(),
              new NumberedShardSpec(0, 0)
          ),
          id4
      );
      assertSameIdentifier(
          new SegmentIdWithShardSpec(
              DATA_SOURCE,
              Granularities.HOUR.bucket(THE_DISTANT_FUTURE),
              futureLock.getVersion(),
              new NumberedShardSpec(1, 0)
          ),
          id5
      );
    }

    assertSameIdentifier(
        id1,
        id6
    );
  }

  @Test
  public void testAddToExistingLinearShardSpecsSameGranularity() throws Exception
  {
    final Task task = new NoopTask(null, null, 0, 0, null, null, null);

    taskActionTestKit.getMetadataStorageCoordinator().announceHistoricalSegments(
        ImmutableSet.of(
            DataSegment.builder()
                       .dataSource(DATA_SOURCE)
                       .interval(Granularities.HOUR.bucket(PARTY_TIME))
                       .version(PARTY_TIME.toString())
                       .shardSpec(new LinearShardSpec(0))
                       .build(),
            DataSegment.builder()
                       .dataSource(DATA_SOURCE)
                       .interval(Granularities.HOUR.bucket(PARTY_TIME))
                       .version(PARTY_TIME.toString())
                       .shardSpec(new LinearShardSpec(1))
                       .build()
        )
    );

    taskActionTestKit.getTaskLockbox().add(task);

    final SegmentIdWithShardSpec id1 = allocate(
        task,
        PARTY_TIME,
        Granularities.NONE,
        Granularities.HOUR,
        "s1",
        null,
        LinearShardSpecFactory.instance()
    );
    final SegmentIdWithShardSpec id2 = allocate(
        task,
        PARTY_TIME,
        Granularities.NONE,
        Granularities.HOUR,
        "s1",
        id1.toString(),
        LinearShardSpecFactory.instance()
    );

    assertSameIdentifier(
        new SegmentIdWithShardSpec(
            DATA_SOURCE,
            Granularities.HOUR.bucket(PARTY_TIME),
            PARTY_TIME.toString(),
            new LinearShardSpec(2)
        ),
        id1
    );
    assertSameIdentifier(
        new SegmentIdWithShardSpec(
            DATA_SOURCE,
            Granularities.HOUR.bucket(PARTY_TIME),
            PARTY_TIME.toString(),
            new LinearShardSpec(3)
        ),
        id2
    );
  }

  @Test
  public void testAddToExistingNumberedShardSpecsSameGranularity() throws Exception
  {
    final Task task = new NoopTask(null, null, 0, 0, null, null, null);

    taskActionTestKit.getMetadataStorageCoordinator().announceHistoricalSegments(
        ImmutableSet.of(
            DataSegment.builder()
                       .dataSource(DATA_SOURCE)
                       .interval(Granularities.HOUR.bucket(PARTY_TIME))
                       .version(PARTY_TIME.toString())
                       .shardSpec(new NumberedShardSpec(0, 2))
                       .build(),
            DataSegment.builder()
                       .dataSource(DATA_SOURCE)
                       .interval(Granularities.HOUR.bucket(PARTY_TIME))
                       .version(PARTY_TIME.toString())
                       .shardSpec(new NumberedShardSpec(1, 2))
                       .build()
        )
    );

    taskActionTestKit.getTaskLockbox().add(task);

    final SegmentIdWithShardSpec id1 = allocate(
        task,
        PARTY_TIME,
        Granularities.NONE,
        Granularities.HOUR,
        "s1",
        null
    );
    final SegmentIdWithShardSpec id2 = allocate(
        task,
        PARTY_TIME,
        Granularities.NONE,
        Granularities.HOUR,
        "s1",
        id1.toString()
    );

    assertSameIdentifier(
        new SegmentIdWithShardSpec(
            DATA_SOURCE,
            Granularities.HOUR.bucket(PARTY_TIME),
            PARTY_TIME.toString(),
            new NumberedShardSpec(2, 2)
        ),
        id1
    );
    assertSameIdentifier(
        new SegmentIdWithShardSpec(
            DATA_SOURCE,
            Granularities.HOUR.bucket(PARTY_TIME),
            PARTY_TIME.toString(),
            new NumberedShardSpec(3, 2)
        ),
        id2
    );
  }

  @Test
  public void testAddToExistingNumberedShardSpecsCoarserPreferredGranularity() throws Exception
  {
    final Task task = new NoopTask(null, null, 0, 0, null, null, null);

    taskActionTestKit.getMetadataStorageCoordinator().announceHistoricalSegments(
        ImmutableSet.of(
            DataSegment.builder()
                       .dataSource(DATA_SOURCE)
                       .interval(Granularities.HOUR.bucket(PARTY_TIME))
                       .version(PARTY_TIME.toString())
                       .shardSpec(new NumberedShardSpec(0, 2))
                       .build(),
            DataSegment.builder()
                       .dataSource(DATA_SOURCE)
                       .interval(Granularities.HOUR.bucket(PARTY_TIME))
                       .version(PARTY_TIME.toString())
                       .shardSpec(new NumberedShardSpec(1, 2))
                       .build()
        )
    );

    taskActionTestKit.getTaskLockbox().add(task);

    final SegmentIdWithShardSpec id1 = allocate(task, PARTY_TIME, Granularities.NONE, Granularities.DAY, "s1", null);

    assertSameIdentifier(
        new SegmentIdWithShardSpec(
            DATA_SOURCE,
            Granularities.HOUR.bucket(PARTY_TIME),
            PARTY_TIME.toString(),
            new NumberedShardSpec(2, 2)
        ),
        id1
    );
  }

  @Test
  public void testAddToExistingNumberedShardSpecsFinerPreferredGranularity() throws Exception
  {
    final Task task = new NoopTask(null, null, 0, 0, null, null, null);

    taskActionTestKit.getMetadataStorageCoordinator().announceHistoricalSegments(
        ImmutableSet.of(
            DataSegment.builder()
                       .dataSource(DATA_SOURCE)
                       .interval(Granularities.HOUR.bucket(PARTY_TIME))
                       .version(PARTY_TIME.toString())
                       .shardSpec(new NumberedShardSpec(0, 2))
                       .build(),
            DataSegment.builder()
                       .dataSource(DATA_SOURCE)
                       .interval(Granularities.HOUR.bucket(PARTY_TIME))
                       .version(PARTY_TIME.toString())
                       .shardSpec(new NumberedShardSpec(1, 2))
                       .build()
        )
    );

    taskActionTestKit.getTaskLockbox().add(task);

    final SegmentIdWithShardSpec id1 = allocate(task, PARTY_TIME, Granularities.NONE, Granularities.MINUTE, "s1", null);

    assertSameIdentifier(
        new SegmentIdWithShardSpec(
            DATA_SOURCE,
            Granularities.HOUR.bucket(PARTY_TIME),
            PARTY_TIME.toString(),
            new NumberedShardSpec(2, 2)
        ),
        id1
    );
  }

  @Test
  public void testCannotAddToExistingNumberedShardSpecsWithCoarserQueryGranularity() throws Exception
  {
    final Task task = new NoopTask(null, null, 0, 0, null, null, null);

    taskActionTestKit.getMetadataStorageCoordinator().announceHistoricalSegments(
        ImmutableSet.of(
            DataSegment.builder()
                       .dataSource(DATA_SOURCE)
                       .interval(Granularities.HOUR.bucket(PARTY_TIME))
                       .version(PARTY_TIME.toString())
                       .shardSpec(new NumberedShardSpec(0, 2))
                       .build(),
            DataSegment.builder()
                       .dataSource(DATA_SOURCE)
                       .interval(Granularities.HOUR.bucket(PARTY_TIME))
                       .version(PARTY_TIME.toString())
                       .shardSpec(new NumberedShardSpec(1, 2))
                       .build()
        )
    );

    taskActionTestKit.getTaskLockbox().add(task);

    final SegmentIdWithShardSpec id1 = allocate(task, PARTY_TIME, Granularities.DAY, Granularities.DAY, "s1", null);

    Assert.assertNull(id1);
  }

  @Test
  public void testCannotDoAnythingWithSillyQueryGranularity()
  {
    final Task task = new NoopTask(null, null, 0, 0, null, null, null);
    taskActionTestKit.getTaskLockbox().add(task);

    final SegmentIdWithShardSpec id1 = allocate(task, PARTY_TIME, Granularities.DAY, Granularities.HOUR, "s1", null);

    Assert.assertNull(id1);
  }

  @Test
  public void testCannotAddToExistingSingleDimensionShardSpecs() throws Exception
  {
    final Task task = new NoopTask(null, null, 0, 0, null, null, null);

    taskActionTestKit.getMetadataStorageCoordinator().announceHistoricalSegments(
        ImmutableSet.of(
            DataSegment.builder()
                       .dataSource(DATA_SOURCE)
                       .interval(Granularities.HOUR.bucket(PARTY_TIME))
                       .version(PARTY_TIME.toString())
                       .shardSpec(new SingleDimensionShardSpec("foo", null, "bar", 0))
                       .build(),
            DataSegment.builder()
                       .dataSource(DATA_SOURCE)
                       .interval(Granularities.HOUR.bucket(PARTY_TIME))
                       .version(PARTY_TIME.toString())
                       .shardSpec(new SingleDimensionShardSpec("foo", "bar", null, 1))
                       .build()
        )
    );

    taskActionTestKit.getTaskLockbox().add(task);

    final SegmentIdWithShardSpec id1 = allocate(task, PARTY_TIME, Granularities.NONE, Granularities.HOUR, "s1", null);

    Assert.assertNull(id1);
  }

  @Test
  public void testSerde() throws Exception
  {
    final ObjectMapper objectMapper = new DefaultObjectMapper();
    objectMapper.registerSubtypes(NumberedShardSpecFactory.class);

    final SegmentAllocateAction action = new SegmentAllocateAction(
        DATA_SOURCE,
        PARTY_TIME,
        Granularities.MINUTE,
        Granularities.HOUR,
        "s1",
        "prev",
        false,
        NumberedShardSpecFactory.instance()
    );

    final SegmentAllocateAction action2 = (SegmentAllocateAction) objectMapper.readValue(
        objectMapper.writeValueAsBytes(action),
        TaskAction.class
    );

    Assert.assertEquals(action.getDataSource(), action2.getDataSource());
    Assert.assertEquals(action.getTimestamp(), action2.getTimestamp());
    Assert.assertEquals(action.getQueryGranularity(), action2.getQueryGranularity());
    Assert.assertEquals(action.getPreferredSegmentGranularity(), action2.getPreferredSegmentGranularity());
    Assert.assertEquals(action.getSequenceName(), action2.getSequenceName());
    Assert.assertEquals(action.getPreviousSegmentId(), action2.getPreviousSegmentId());
    Assert.assertEquals(action.isSkipSegmentLineageCheck(), action2.isSkipSegmentLineageCheck());
  }

  @Test
  public void testWithShardSpecFactoryAndOvershadowingSegments() throws IOException
  {
    final Task task = NoopTask.create();
    taskActionTestKit.getTaskLockbox().add(task);

    final ObjectMapper objectMapper = new DefaultObjectMapper();

    taskActionTestKit.getMetadataStorageCoordinator().announceHistoricalSegments(
        ImmutableSet.of(
            DataSegment.builder()
                       .dataSource(DATA_SOURCE)
                       .interval(Granularities.HOUR.bucket(PARTY_TIME))
                       .version(PARTY_TIME.toString())
                       .shardSpec(new HashBasedNumberedShardSpec(0, 2, ImmutableList.of("dim1"), objectMapper))
                       .build(),
            DataSegment.builder()
                       .dataSource(DATA_SOURCE)
                       .interval(Granularities.HOUR.bucket(PARTY_TIME))
                       .version(PARTY_TIME.toString())
                       .shardSpec(new HashBasedNumberedShardSpec(1, 2, ImmutableList.of("dim1"), objectMapper))
                       .build()
        )
    );

    final SegmentAllocateAction action = new SegmentAllocateAction(
        DATA_SOURCE,
        PARTY_TIME,
        Granularities.MINUTE,
        Granularities.HOUR,
        "seq",
        null,
        true,
        new HashBasedNumberedShardSpecFactory(ImmutableList.of("dim1"), 2)
    );
    final SegmentIdWithShardSpec segmentIdentifier = action.perform(task, taskActionTestKit.getTaskActionToolbox());
    Assert.assertNotNull(segmentIdentifier);

    final ShardSpec shardSpec = segmentIdentifier.getShardSpec();
    Assert.assertEquals(2, shardSpec.getPartitionNum());

    Assert.assertTrue(shardSpec instanceof HashBasedNumberedShardSpec);
    final HashBasedNumberedShardSpec hashBasedNumberedShardSpec = (HashBasedNumberedShardSpec) shardSpec;
    Assert.assertEquals(2, hashBasedNumberedShardSpec.getPartitions());
    Assert.assertEquals(ImmutableList.of("dim1"), hashBasedNumberedShardSpec.getPartitionDimensions());

//    Assert.assertEquals(ImmutableSet.of(0, 1), segmentIdentifier.getDirectOvershadowedSegments());
    // TODO: check shardSpec
  }

  private SegmentIdWithShardSpec allocate(
      final Task task,
      final DateTime timestamp,
      final Granularity queryGranularity,
      final Granularity preferredSegmentGranularity,
      final String sequenceName,
      final String sequencePreviousId
  )
  {
    return allocate(
        task,
        timestamp,
        queryGranularity,
        preferredSegmentGranularity,
        sequenceName,
        sequencePreviousId,
        NumberedShardSpecFactory.instance()
    );
  }

  private SegmentIdWithShardSpec allocate(
      final Task task,
      final DateTime timestamp,
      final Granularity queryGranularity,
      final Granularity preferredSegmentGranularity,
      final String sequenceName,
      final String sequencePreviousId,
      final ShardSpecFactory shardSpecFactory
      )
  {
    final SegmentAllocateAction action = new SegmentAllocateAction(
        DATA_SOURCE,
        timestamp,
        queryGranularity,
        preferredSegmentGranularity,
        sequenceName,
        sequencePreviousId,
        false,
        shardSpecFactory
    );
    return action.perform(task, taskActionTestKit.getTaskActionToolbox());
  }

  private void assertSameIdentifier(final SegmentIdWithShardSpec expected, final SegmentIdWithShardSpec actual)
  {
    Assert.assertEquals(expected, actual);
    Assert.assertEquals(expected.getShardSpec().getPartitionNum(), actual.getShardSpec().getPartitionNum());
    Assert.assertEquals(expected.getShardSpec().getClass(), actual.getShardSpec().getClass());

    if (expected.getShardSpec().getClass() == NumberedShardSpec.class
        && actual.getShardSpec().getClass() == NumberedShardSpec.class) {
      Assert.assertEquals(
          ((NumberedShardSpec) expected.getShardSpec()).getPartitions(),
          ((NumberedShardSpec) actual.getShardSpec()).getPartitions()
      );
    } else if (expected.getShardSpec().getClass() == LinearShardSpec.class
               && actual.getShardSpec().getClass() == LinearShardSpec.class) {
      // do nothing
    }
  }
}
