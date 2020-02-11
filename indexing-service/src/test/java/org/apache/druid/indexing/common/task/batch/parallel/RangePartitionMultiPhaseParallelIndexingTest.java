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

package org.apache.druid.indexing.common.task.batch.parallel;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.common.collect.SetMultimap;
import org.apache.druid.data.input.impl.CSVParseSpec;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.data.input.impl.ParseSpec;
import org.apache.druid.data.input.impl.TimestampSpec;
import org.apache.druid.indexer.partitions.SingleDimensionPartitionsSpec;
import org.apache.druid.indexing.common.LockGranularity;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.guava.Comparators;
import org.apache.druid.query.scan.ScanResultValue;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.partition.SingleDimensionShardSpec;
import org.hamcrest.Matchers;
import org.joda.time.Interval;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

@RunWith(Parameterized.class)
public class RangePartitionMultiPhaseParallelIndexingTest extends AbstractMultiPhaseParallelIndexingTest
{
  private static final int NUM_FILE = 10;
  private static final int NUM_ROW = 20;
  private static final int NUM_DAY = 2;
  private static final int NUM_PARTITION = 2;
  private static final int YEAR = 2017;
  private static final String DIM1 = "dim1";
  private static final String DIM2 = "dim2";
  private static final List<String> DIMS = ImmutableList.of(DIM1, DIM2);
  private static final String TEST_FILE_NAME_PREFIX = "test_";
  private static final ParseSpec PARSE_SPEC = new CSVParseSpec(
      new TimestampSpec(
          "ts",
          "auto",
          null
      ),
      new DimensionsSpec(DimensionsSpec.getDefaultSchemas(Arrays.asList("ts", DIM1, DIM2))),
      null,
      Arrays.asList("ts", DIM1, DIM2, "val"),
      false,
      0
  );

  @Parameterized.Parameters(name = "{0}, useInputFormatApi={1}, maxNumConcurrentSubTasks={2}")
  public static Iterable<Object[]> constructorFeeder()
  {
    return ImmutableList.of(
        new Object[]{LockGranularity.TIME_CHUNK, false, 2},
        new Object[]{LockGranularity.TIME_CHUNK, true, 2},
        new Object[]{LockGranularity.SEGMENT, true, 2},
        new Object[]{LockGranularity.SEGMENT, true, 1}  // currently spawns subtask instead of running in supervisor
    );
  }

  private File inputDir;
  private SetMultimap<Interval, String> intervalToDim1;

  private final int maxNumConcurrentSubTasks;

  public RangePartitionMultiPhaseParallelIndexingTest(
      LockGranularity lockGranularity,
      boolean useInputFormatApi,
      int maxNumConcurrentSubTasks
  )
  {
    super(lockGranularity, useInputFormatApi);
    this.maxNumConcurrentSubTasks = maxNumConcurrentSubTasks;
  }

  @Before
  public void setup() throws IOException
  {
    inputDir = temporaryFolder.newFolder("data");
    intervalToDim1 = createInputFiles(inputDir);
  }

  private static SetMultimap<Interval, String> createInputFiles(File inputDir) throws IOException
  {
    SetMultimap<Interval, String> intervalToDim1 = HashMultimap.create();

    for (int fileIndex = 0; fileIndex < NUM_FILE; fileIndex++) {
      Path path = new File(inputDir, TEST_FILE_NAME_PREFIX + fileIndex).toPath();
      try (final Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
        for (int i = 0; i < (NUM_ROW / NUM_DAY); i++) {
          for (int d = 0; d < NUM_DAY; d++) {
            writeRow(writer, i + d, fileIndex + d, intervalToDim1);
          }
        }
      }
    }

    return intervalToDim1;
  }

  private static void writeRow(Writer writer, int day, int fileIndex, Multimap<Interval, String> intervalToDim1)
      throws IOException
  {
    Interval interval = Intervals.of("%s-12-%d/%s-12-%d", YEAR, day + 1, YEAR, day + 2);
    String startDate = interval.getStart().toString("y-M-d");
    String dim1Value = String.valueOf(fileIndex + 10);
    writer.write(StringUtils.format("%s,%s,%d th test file\n", startDate, dim1Value, fileIndex));
    intervalToDim1.put(interval, dim1Value);
  }

  @Test
  public void createsCorrectRangePartitions() throws Exception
  {
    int targetRowsPerSegment = NUM_ROW / NUM_DAY / NUM_PARTITION;
    final Set<DataSegment> publishedSegments = runTestTask(
        PARSE_SPEC,
        Intervals.of("%s-12/P1M", YEAR),
        inputDir,
        TEST_FILE_NAME_PREFIX + "*",
        new SingleDimensionPartitionsSpec(
            targetRowsPerSegment,
            null,
            DIM1,
            false
        ),
        maxNumConcurrentSubTasks
    );
    assertRangePartitions(publishedSegments);
  }

  private void assertRangePartitions(Set<DataSegment> publishedSegments) throws IOException
  {
    Multimap<Interval, DataSegment> intervalToSegments = ArrayListMultimap.create();
    publishedSegments.forEach(s -> intervalToSegments.put(s.getInterval(), s));

    SortedSet<Interval> publishedIntervals = new TreeSet<>(Comparators.intervalsByStartThenEnd());
    publishedIntervals.addAll(intervalToSegments.keySet());
    assertHasExpectedIntervals(publishedIntervals);

    Interval firstInterval = publishedIntervals.first();
    Interval lastInterval = publishedIntervals.last();
    File tempSegmentDir = temporaryFolder.newFolder();

    intervalToSegments.asMap().forEach((interval, segments) -> {
      assertNumPartition(interval, segments, firstInterval, lastInterval);

      List<String> allValues = new ArrayList<>(NUM_ROW);
      for (DataSegment segment : segments) {
        List<String> values = getColumnValues(segment, tempSegmentDir);
        assertValuesInRange(values, segment);
        allValues.addAll(values);
      }

      assertIntervalHasAllExpectedValues(interval, allValues);
    });
  }

  private void assertHasExpectedIntervals(Set<Interval> publishedSegmentIntervals)
  {
    Assert.assertEquals(intervalToDim1.keySet(), publishedSegmentIntervals);
  }

  private static void assertNumPartition(
      Interval interval,
      Collection<DataSegment> segments,
      Interval firstInterval,
      Interval lastInterval
  )
  {
    int expectedNumPartition = NUM_PARTITION;
    if (interval.equals(firstInterval) || interval.equals(lastInterval)) {
      expectedNumPartition -= 1;
    }
    expectedNumPartition *= NUM_DAY;
    Assert.assertEquals(expectedNumPartition, segments.size());
  }

  private List<String> getColumnValues(DataSegment segment, File tempDir)
  {
    List<ScanResultValue> results = querySegment(segment, DIMS, tempDir);
    Assert.assertEquals(1, results.size());
    List<LinkedHashMap<String, String>> rows = (List<LinkedHashMap<String, String>>) results.get(0).getEvents();
    return rows.stream()
               .map(row -> row.get(DIM1))
               .collect(Collectors.toList());
  }

  private static void assertValuesInRange(List<String> values, DataSegment segment)
  {
    SingleDimensionShardSpec shardSpec = (SingleDimensionShardSpec) segment.getShardSpec();
    String start = shardSpec.getStart();
    String end = shardSpec.getEnd();
    Assert.assertTrue(shardSpec.toString(), start != null || end != null);

    for (String value : values) {
      if (start != null) {
        Assert.assertThat(value.compareTo(start), Matchers.greaterThanOrEqualTo(0));
      }

      if (end != null) {
        Assert.assertThat(value.compareTo(end), Matchers.lessThan(0));
      }
    }
  }

  private void assertIntervalHasAllExpectedValues(Interval interval, List<String> actualValues)
  {
    List<String> expectedValues = new ArrayList<>(intervalToDim1.get(interval));
    Assert.assertEquals(expectedValues.size(), actualValues.size());
    Collections.sort(expectedValues);
    Collections.sort(actualValues);
    Assert.assertEquals(expectedValues, actualValues);
  }
}
