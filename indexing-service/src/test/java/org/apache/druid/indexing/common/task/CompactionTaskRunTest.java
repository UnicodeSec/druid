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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import org.apache.druid.data.input.impl.CSVParseSpec;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.data.input.impl.ParseSpec;
import org.apache.druid.data.input.impl.TimestampSpec;
import org.apache.druid.indexer.TaskStatus;
import org.apache.druid.indexing.common.TaskToolbox;
import org.apache.druid.indexing.common.TestUtils;
import org.apache.druid.indexing.common.actions.LocalTaskActionClient;
import org.apache.druid.indexing.common.stats.RowIngestionMetersFactory;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.concurrent.Execs;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.segment.indexing.granularity.UniformGranularitySpec;
import org.apache.druid.segment.loading.DataSegmentPusher;
import org.apache.druid.segment.loading.LocalDataSegmentPuller;
import org.apache.druid.segment.loading.LocalDataSegmentPusher;
import org.apache.druid.segment.loading.LocalDataSegmentPusherConfig;
import org.apache.druid.segment.loading.LocalLoadSpec;
import org.apache.druid.segment.loading.NoopDataSegmentKiller;
import org.apache.druid.segment.loading.SegmentLoader;
import org.apache.druid.segment.loading.SegmentLoaderConfig;
import org.apache.druid.segment.loading.SegmentLoaderLocalCacheManager;
import org.apache.druid.segment.loading.StorageLocationConfig;
import org.apache.druid.server.security.AuthTestUtils;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.partition.NumberedShardSpec;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class CompactionTaskRunTest extends IngestionTestBase
{
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private static final ParseSpec DEFAULT_PARSE_SPEC = new CSVParseSpec(
      new TimestampSpec(
          "ts",
          "auto",
          null
      ),
      new DimensionsSpec(
          DimensionsSpec.getDefaultSchemas(Arrays.asList("ts", "dim")),
          Lists.newArrayList(),
          Lists.newArrayList()
      ),
      null,
      Arrays.asList("ts", "dim", "val"),
      false,
      0
  );

  private RowIngestionMetersFactory rowIngestionMetersFactory;
  private LocalTaskActionClient actionClient;
  private ExecutorService exec;

  public CompactionTaskRunTest()
  {
    TestUtils testUtils = new TestUtils();
    rowIngestionMetersFactory = testUtils.getRowIngestionMetersFactory();
  }

  @Before
  public void setup()
  {
    exec = Execs.multiThreaded(2, "compaction-task-run-test-%d");
  }

  @After
  public void teardown()
  {
    exec.shutdownNow();
  }

  @Test
  public void testRun() throws Exception
  {
    runIndexTask();

    final CompactionTask compactionTask = new CompactionTask(
        null,
        null,
        DATA_SOURCE,
        Intervals.of("2014-01-01/2014-01-02"),
        null,
        null,
        null,
        null,
        null,
        null,
        getObjectMapper(),
        AuthTestUtils.TEST_AUTHORIZER_MAPPER,
        null,
        rowIngestionMetersFactory
    );

    final Pair<TaskStatus, List<DataSegment>> resultPair = runTask(compactionTask);

    Assert.assertTrue(resultPair.lhs.isSuccess());

    final List<DataSegment> segments = resultPair.rhs;
    Assert.assertEquals(1, segments.size());
    Assert.assertEquals(Intervals.of("2014-01-01T01:00:00/2014-01-01T02:00:00"), segments.get(0).getInterval());
    Assert.assertEquals(new NumberedShardSpec(2, 0), segments.get(0).getShardSpec());
    Assert.assertEquals(ImmutableSet.of(0, 1), new HashSet<>(segments.get(0).getOvershadowedGroup()));
  }

  @Test
  public void testRunIndexAndCompactAtTheSameTime() throws Exception
  {
    runIndexTask();

    final CompactionTask compactionTask = new CompactionTask(
        null,
        null,
        DATA_SOURCE,
        Intervals.of("2014-01-01T00:00:00/2014-01-02T03:00:00"),
        null,
        null,
        null,
        null,
        null,
        null,
        getObjectMapper(),
        AuthTestUtils.TEST_AUTHORIZER_MAPPER,
        null,
        rowIngestionMetersFactory
    );

    System.err.println("data gen finished");

    File tmpDir = temporaryFolder.newFolder();
    File tmpFile = File.createTempFile("druid", "index", tmpDir);

    try (BufferedWriter writer = Files.newWriter(tmpFile, StandardCharsets.UTF_8)) {
      writer.write("2014-01-01T03:00:10Z,a,1\n");
      writer.write("2014-01-01T03:00:10Z,b,2\n");
      writer.write("2014-01-01T03:00:10Z,c,3\n");
      writer.write("2014-01-01T04:00:20Z,a,1\n");
      writer.write("2014-01-01T04:00:20Z,b,2\n");
      writer.write("2014-01-01T04:00:20Z,c,3\n");
      writer.write("2014-01-01T05:00:30Z,a,1\n");
      writer.write("2014-01-01T05:00:30Z,b,2\n");
      writer.write("2014-01-01T05:00:30Z,c,3\n");
    }

    IndexTask indexTask = new IndexTask(
        null,
        null,
        createIngestionSpec(
            tmpDir,
            DEFAULT_PARSE_SPEC,
            new UniformGranularitySpec(
                Granularities.HOUR,
                Granularities.MINUTE,
                null
            ),
            IndexTaskTest.createTuningConfig(2, 2, null, 2L, null, false, false, true),
            false
        ),
        null,
        AuthTestUtils.TEST_AUTHORIZER_MAPPER,
        null,
        rowIngestionMetersFactory
    );

    final Future<Pair<TaskStatus, List<DataSegment>>> compactionFuture = exec.submit(
        () -> runTask(compactionTask)
    );

//    final Future<Pair<TaskStatus, List<DataSegment>>> indexFuture = exec.submit(
//        () -> runTask(indexTask)
//    );

    Assert.assertTrue(compactionFuture.get().lhs.isSuccess());
//    Assert.assertTrue(indexFuture.get().lhs.isSuccess());
  }

  private void runIndexTask() throws Exception
  {
    File tmpDir = temporaryFolder.newFolder();
    File tmpFile = File.createTempFile("druid", "index", tmpDir);

    try (BufferedWriter writer = Files.newWriter(tmpFile, StandardCharsets.UTF_8)) {
      writer.write("2014-01-01T00:00:10Z,a,1\n");
      writer.write("2014-01-01T00:00:10Z,b,2\n");
      writer.write("2014-01-01T00:00:10Z,c,3\n");
      writer.write("2014-01-01T01:00:20Z,a,1\n");
      writer.write("2014-01-01T01:00:20Z,b,2\n");
      writer.write("2014-01-01T01:00:20Z,c,3\n");
      writer.write("2014-01-01T02:00:30Z,a,1\n");
      writer.write("2014-01-01T02:00:30Z,b,2\n");
      writer.write("2014-01-01T02:00:30Z,c,3\n");
    }

    IndexTask indexTask = new IndexTask(
        null,
        null,
        createIngestionSpec(
            tmpDir,
            DEFAULT_PARSE_SPEC,
            new UniformGranularitySpec(
                Granularities.HOUR,
                Granularities.MINUTE,
                null
            ),
            IndexTaskTest.createTuningConfig(2, 2, null, 2L, null, false, false, true),
            false
        ),
        null,
        AuthTestUtils.TEST_AUTHORIZER_MAPPER,
        null,
        rowIngestionMetersFactory
    );

    Assert.assertTrue(runTask(indexTask).lhs.isSuccess());
  }

  private Pair<TaskStatus, List<DataSegment>> runTask(Task task) throws Exception
  {
    getLockbox().add(task);
    getTaskStorage().insert(task, TaskStatus.running(task.getId()));
    actionClient = createActionClient(task);

    final File deepStorageDir = temporaryFolder.newFolder();
    final ObjectMapper objectMapper = getObjectMapper();
    objectMapper.registerSubtypes(
        new NamedType(LocalLoadSpec.class, "local")
    );
    objectMapper.registerSubtypes(LocalDataSegmentPuller.class);

    final List<DataSegment> segments = new ArrayList<>();
    final DataSegmentPusher pusher = new LocalDataSegmentPusher(
        new LocalDataSegmentPusherConfig()
        {
          @Override
          public File getStorageDirectory()
          {
            return deepStorageDir;
          }
        },
        objectMapper
    )
    {
      @Override
      public DataSegment push(File file, DataSegment segment, boolean useUniquePath) throws IOException
      {
        segments.add(segment);
        return super.push(file, segment, useUniquePath);
      }
    };

    final SegmentLoader loader = new SegmentLoaderLocalCacheManager(
        getIndexIO(),
        new SegmentLoaderConfig(){
          @Override
          public List<StorageLocationConfig> getLocations()
          {
            return ImmutableList.of(
                new StorageLocationConfig()
                {
                  @Override
                  public File getPath()
                  {
                    return deepStorageDir;
                  }
                }
            );
          }
        },
        objectMapper
    );

    final TaskToolbox box = new TaskToolbox(
        null,
        actionClient,
        null,
        pusher,
        new NoopDataSegmentKiller(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        loader,
        objectMapper,
        temporaryFolder.newFolder(),
        getIndexIO(),
        null,
        null,
        null,
        getIndexMerger(),
        null,
        null,
        null,
        null,
        new NoopTestTaskFileWriter()
    );

    if (task.isReady(box.getTaskActionClient())) {
      TaskStatus status = task.run(box);
      shutdownTask(task);
      Collections.sort(segments);
      return Pair.of(status, segments);
    } else {
      throw new ISE("task is not ready");
    }
  }
}
