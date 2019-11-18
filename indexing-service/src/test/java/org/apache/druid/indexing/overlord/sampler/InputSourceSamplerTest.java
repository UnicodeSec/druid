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

package org.apache.druid.indexing.overlord.sampler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.druid.common.config.NullHandling;
import org.apache.druid.data.input.FirehoseFactoryToInputSourceAdaptor;
import org.apache.druid.data.input.InputFormat;
import org.apache.druid.data.input.InputSource;
import org.apache.druid.data.input.impl.CsvInputFormat;
import org.apache.druid.data.input.impl.DelimitedParseSpec;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.data.input.impl.InlineInputSource;
import org.apache.druid.data.input.impl.InputRowParser;
import org.apache.druid.data.input.impl.JSONParseSpec;
import org.apache.druid.data.input.impl.JsonInputFormat;
import org.apache.druid.data.input.impl.StringDimensionSchema;
import org.apache.druid.data.input.impl.StringInputRowParser;
import org.apache.druid.data.input.impl.TimestampSpec;
import org.apache.druid.indexing.overlord.sampler.SamplerResponse.SamplerResponseRow;
import org.apache.druid.jackson.DefaultObjectMapper;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.collect.Utils;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.LongSumAggregatorFactory;
import org.apache.druid.query.expression.TestExprMacroTable;
import org.apache.druid.query.filter.SelectorDimFilter;
import org.apache.druid.segment.indexing.DataSchema;
import org.apache.druid.segment.indexing.granularity.GranularitySpec;
import org.apache.druid.segment.indexing.granularity.UniformGranularitySpec;
import org.apache.druid.segment.realtime.firehose.InlineFirehoseFactory;
import org.apache.druid.segment.transform.ExpressionTransform;
import org.apache.druid.segment.transform.TransformSpec;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.stream.Collectors;

@RunWith(Parameterized.class)
public class InputSourceSamplerTest
{
  private enum ParserType
  {
    STR_JSON, STR_CSV
  }

  private static final ObjectMapper OBJECT_MAPPER = new DefaultObjectMapper();
  private static final boolean USE_DEFAULT_VALUE_FOR_NULL = Boolean.parseBoolean(
      System.getProperty(NullHandling.NULL_HANDLING_CONFIG_STRING, "true")
  );

  private static final List<String> STR_JSON_ROWS = ImmutableList.of(
      "{ \"t\": \"2019-04-22T12:00\", \"dim1\": \"foo\", \"met1\": 1 }",
      "{ \"t\": \"2019-04-22T12:00\", \"dim1\": \"foo\", \"met1\": 2 }",
      "{ \"t\": \"2019-04-22T12:01\", \"dim1\": \"foo\", \"met1\": 3 }",
      "{ \"t\": \"2019-04-22T12:00\", \"dim1\": \"foo2\", \"met1\": 4 }",
      "{ \"t\": \"2019-04-22T12:00\", \"dim1\": \"foo\", \"dim2\": \"bar\", \"met1\": 5 }",
      "{ \"t\": \"bad_timestamp\", \"dim1\": \"foo\", \"met1\": 6 }"
  );

  private static final List<String> STR_CSV_ROWS = ImmutableList.of(
      "2019-04-22T12:00,foo,,1",
      "2019-04-22T12:00,foo,,2",
      "2019-04-22T12:01,foo,,3",
      "2019-04-22T12:00,foo2,,4",
      "2019-04-22T12:00,foo,bar,5",
      "bad_timestamp,foo,,6"
  );


  private List<Map<String, Object>> mapOfRows;
  private InputSourceSampler inputSourceSampler;
  private ParserType parserType;
  private boolean useInputFormatApi;

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Parameterized.Parameters(name = "parserType = {0}, useInputFormatApi={1}")
  public static Iterable<Object[]> constructorFeeder()
  {
    return ImmutableList.of(
        new Object[]{ParserType.STR_JSON, false},
        new Object[]{ParserType.STR_JSON, true},
        new Object[]{ParserType.STR_CSV, false},
        new Object[]{ParserType.STR_CSV, true}
    );
  }

  public InputSourceSamplerTest(ParserType parserType, boolean useInputFormatApi)
  {
    this.parserType = parserType;
    this.useInputFormatApi = useInputFormatApi;
  }

  @Before
  public void setupTest()
  {
    inputSourceSampler = new InputSourceSampler();

    mapOfRows = new ArrayList<>();
    final List<String> columns = ImmutableList.of("t", "dim1", "dim2", "met1");
    for (String row : STR_CSV_ROWS) {
      final List<Object> values = new ArrayList<>();
      final String[] tokens = row.split(",");
      for (int i = 0; i < tokens.length; i++) {
        if (i < tokens.length - 1) {
          values.add("".equals(tokens[i]) ? null : tokens[i]);
        } else {
          values.add(Integer.parseInt(tokens[i]));
        }
      }
      mapOfRows.add(Utils.zipMapPartial(columns, values));
    }
  }

  @Test
  public void testNoParams()
  {
    expectedException.expect(NullPointerException.class);
    expectedException.expectMessage("inputSource required");

    inputSourceSampler.sample(null, null, null, null);
  }

  @Test
  public void testNoDataSchema()
  {
    final InputSource inputSource = createInputSource(getTestRows(), null);
    final SamplerResponse response = inputSourceSampler.sample(inputSource, createInputFormat(), null, null);

    Assert.assertEquals(6, response.getNumRowsRead());
    Assert.assertEquals(0, response.getNumRowsIndexed());
    Assert.assertEquals(6, response.getData().size());

    List<SamplerResponseRow> data = response.getData();

    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(0),
            null,
            true,
            unparseableTimestampErrorString(data.get(0).getInput())
        ),
        data.get(0)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(1),
            null,
            true,
            unparseableTimestampErrorString(data.get(1).getInput())
        ),
        data.get(1)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(2),
            null,
            true,
            unparseableTimestampErrorString(data.get(2).getInput())
        ),
        data.get(2)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(3),
            null,
            true,
            unparseableTimestampErrorString(data.get(3).getInput())
        ),
        data.get(3)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(4),
            null,
            true,
            unparseableTimestampErrorString(data.get(4).getInput())
        ),
        data.get(4)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(5),
            null,
            true,
            unparseableTimestampErrorString(data.get(5).getInput())
        ),
        data.get(5)
    );
  }

  @Test
  public void testNoDataSchemaNumRows()
  {
    final InputSource inputSource = createInputSource(getTestRows(), null);
    final SamplerResponse response = inputSourceSampler.sample(
        inputSource,
        createInputFormat(),
        null,
        new SamplerConfig(3, null)
    );

    Assert.assertEquals(3, response.getNumRowsRead());
    Assert.assertEquals(0, response.getNumRowsIndexed());
    Assert.assertEquals(3, response.getData().size());

    List<SamplerResponseRow> data = response.getData();

    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(0),
            null,
            true,
            unparseableTimestampErrorString(data.get(0).getInput())
        ),
        data.get(0)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(1),
            null,
            true,
            unparseableTimestampErrorString(data.get(1).getInput())
        ),
        data.get(1)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(2),
            null,
            true,
            unparseableTimestampErrorString(data.get(2).getInput())
        ),
        data.get(2)
    );
  }

  @Test
  public void testMissingValueTimestampSpec() throws IOException
  {
    final TimestampSpec timestampSpec = new TimestampSpec(null, null, DateTimes.of("1970"));
    final DimensionsSpec dimensionsSpec = new DimensionsSpec(null);
    final DataSchema dataSchema = createDataSchema(timestampSpec, dimensionsSpec, null, null, null);
    final InputSource inputSource = createInputSource(getTestRows(), dataSchema);
    final InputFormat inputFormat = createInputFormat();

    SamplerResponse response = inputSourceSampler.sample(inputSource, inputFormat, dataSchema, null);

    Assert.assertEquals(6, response.getNumRowsRead());
    Assert.assertEquals(6, response.getNumRowsIndexed());
    Assert.assertEquals(6, response.getData().size());

    List<SamplerResponseRow> data = removeEmptyColumns(response.getData());

    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(0),
            ImmutableMap.of("__time", 0L, "t", "2019-04-22T12:00", "dim1", "foo", "met1", "1"),
            null,
            null
        ),
        data.get(0)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(1),
            ImmutableMap.of("__time", 0L, "t", "2019-04-22T12:00", "dim1", "foo", "met1", "2"),
            null,
            null
        ),
        data.get(1)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(2),
            ImmutableMap.of("__time", 0L, "t", "2019-04-22T12:01", "dim1", "foo", "met1", "3"),
            null,
            null
        ),
        data.get(2)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(3),
            ImmutableMap.of("__time", 0L, "t", "2019-04-22T12:00", "dim1", "foo2", "met1", "4"),
            null,
            null
        ),
        data.get(3)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(4),
            ImmutableMap.of("__time", 0L, "t", "2019-04-22T12:00", "dim1", "foo", "dim2", "bar", "met1", "5"),
            null,
            null
        ),
        data.get(4)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(5),
            ImmutableMap.of("__time", 0L, "t", "bad_timestamp", "dim1", "foo", "met1", "6"),
            null,
            null
        ),
        data.get(5)
    );
  }

  @Test
  public void testWithTimestampSpec() throws IOException
  {
    final TimestampSpec timestampSpec = new TimestampSpec("t", null, null);
    final DimensionsSpec dimensionsSpec = new DimensionsSpec(null);
    final DataSchema dataSchema = createDataSchema(timestampSpec, dimensionsSpec, null, null, null);
    final InputSource inputSource = createInputSource(getTestRows(), dataSchema);
    final InputFormat inputFormat = createInputFormat();

    SamplerResponse response = inputSourceSampler.sample(inputSource, inputFormat, dataSchema, null);

    Assert.assertEquals(6, response.getNumRowsRead());
    Assert.assertEquals(5, response.getNumRowsIndexed());
    Assert.assertEquals(6, response.getData().size());

    List<SamplerResponseRow> data = removeEmptyColumns(response.getData());

    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(0),
            ImmutableMap.of("__time", 1555934400000L, "dim1", "foo", "met1", "1"),
            null,
            null
        ),
        data.get(0)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(1),
            ImmutableMap.of("__time", 1555934400000L, "dim1", "foo", "met1", "2"),
            null,
            null
        ),
        data.get(1)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(2),
            ImmutableMap.of("__time", 1555934460000L, "dim1", "foo", "met1", "3"),
            null,
            null
        ),
        data.get(2)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(3),
            ImmutableMap.of("__time", 1555934400000L, "dim1", "foo2", "met1", "4"),
            null,
            null
        ),
        data.get(3)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(4),
            ImmutableMap.of("__time", 1555934400000L, "dim1", "foo", "dim2", "bar", "met1", "5"),
            null,
            null
        ),
        data.get(4)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(5),
            null,
            true,
            getUnparseableTimestampString()
        ),
        data.get(5)
    );
  }

  @Test
  public void testWithDimensionSpec() throws IOException
  {
    final TimestampSpec timestampSpec = new TimestampSpec("t", null, null);
    final DimensionsSpec dimensionsSpec = new DimensionsSpec(
        ImmutableList.of(StringDimensionSchema.create("dim1"), StringDimensionSchema.create("met1"))
    );
    final DataSchema dataSchema = createDataSchema(timestampSpec, dimensionsSpec, null, null, null);
    final InputSource inputSource = createInputSource(getTestRows(), dataSchema);
    final InputFormat inputFormat = createInputFormat();

    SamplerResponse response = inputSourceSampler.sample(inputSource, inputFormat, dataSchema, null);

    Assert.assertEquals(6, response.getNumRowsRead());
    Assert.assertEquals(5, response.getNumRowsIndexed());
    Assert.assertEquals(6, response.getData().size());

    List<SamplerResponseRow> data = response.getData();

    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(0),
            ImmutableMap.of("__time", 1555934400000L, "dim1", "foo", "met1", "1"),
            null,
            null
        ),
        data.get(0)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(1),
            ImmutableMap.of("__time", 1555934400000L, "dim1", "foo", "met1", "2"),
            null,
            null
        ),
        data.get(1)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(2),
            ImmutableMap.of("__time", 1555934460000L, "dim1", "foo", "met1", "3"),
            null,
            null
        ),
        data.get(2)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(3),
            ImmutableMap.of("__time", 1555934400000L, "dim1", "foo2", "met1", "4"),
            null,
            null
        ),
        data.get(3)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(4),
            ImmutableMap.of("__time", 1555934400000L, "dim1", "foo", "met1", "5"),
            null,
            null
        ),
        data.get(4)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(5),
            null,
            true,
            getUnparseableTimestampString()
        ),
        data.get(5)
    );
  }

  @Test
  public void testWithNoRollup() throws IOException
  {
    final TimestampSpec timestampSpec = new TimestampSpec("t", null, null);
    final DimensionsSpec dimensionsSpec = new DimensionsSpec(null);
    final AggregatorFactory[] aggregatorFactories = {new LongSumAggregatorFactory("met1", "met1")};
    final GranularitySpec granularitySpec = new UniformGranularitySpec(
        Granularities.DAY,
        Granularities.HOUR,
        false,
        null
    );
    final DataSchema dataSchema = createDataSchema(
        timestampSpec,
        dimensionsSpec,
        aggregatorFactories,
        granularitySpec,
        null
    );
    final InputSource inputSource = createInputSource(getTestRows(), dataSchema);
    final InputFormat inputFormat = createInputFormat();

    SamplerResponse response = inputSourceSampler.sample(inputSource, inputFormat, dataSchema, null);

    Assert.assertEquals(6, response.getNumRowsRead());
    Assert.assertEquals(5, response.getNumRowsIndexed());
    Assert.assertEquals(6, response.getData().size());

    List<SamplerResponseRow> data = removeEmptyColumns(response.getData());

    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(0),
            ImmutableMap.of("__time", 1555934400000L, "dim1", "foo", "met1", 1L),
            null,
            null
        ),
        data.get(0)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(1),
            ImmutableMap.of("__time", 1555934400000L, "dim1", "foo", "met1", 2L),
            null,
            null
        ),
        data.get(1)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(2),
            ImmutableMap.of("__time", 1555934400000L, "dim1", "foo", "met1", 3L),
            null,
            null
        ),
        data.get(2)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(3),
            ImmutableMap.of("__time", 1555934400000L, "dim1", "foo2", "met1", 4L),
            null,
            null
        ),
        data.get(3)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(4),
            ImmutableMap.of("__time", 1555934400000L, "dim1", "foo", "dim2", "bar", "met1", 5L),
            null,
            null
        ),
        data.get(4)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(5),
            null,
            true,
            getUnparseableTimestampString()
        ),
        data.get(5)
    );
  }

  @Test
  public void testWithRollup() throws IOException
  {
    final TimestampSpec timestampSpec = new TimestampSpec("t", null, null);
    final DimensionsSpec dimensionsSpec = new DimensionsSpec(null);
    final AggregatorFactory[] aggregatorFactories = {new LongSumAggregatorFactory("met1", "met1")};
    final GranularitySpec granularitySpec = new UniformGranularitySpec(
        Granularities.DAY,
        Granularities.HOUR,
        true,
        null
    );
    final DataSchema dataSchema = createDataSchema(
        timestampSpec,
        dimensionsSpec,
        aggregatorFactories,
        granularitySpec,
        null
    );
    final InputSource inputSource = createInputSource(getTestRows(), dataSchema);
    final InputFormat inputFormat = createInputFormat();

    SamplerResponse response = inputSourceSampler.sample(inputSource, inputFormat, dataSchema, null);

    Assert.assertEquals(6, response.getNumRowsRead());
    Assert.assertEquals(5, response.getNumRowsIndexed());
    Assert.assertEquals(4, response.getData().size());

    List<SamplerResponseRow> data = removeEmptyColumns(response.getData());

    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(0),
            ImmutableMap.of("__time", 1555934400000L, "dim1", "foo", "met1", 6L),
            null,
            null
        ),
        data.get(0)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(3),
            ImmutableMap.of("__time", 1555934400000L, "dim1", "foo2", "met1", 4L),
            null,
            null
        ),
        data.get(1)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(4),
            ImmutableMap.of("__time", 1555934400000L, "dim1", "foo", "dim2", "bar", "met1", 5L),
            null,
            null
        ),
        data.get(2)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(5),
            null,
            true,
            getUnparseableTimestampString()
        ),
        data.get(3)
    );
  }

  @Test
  public void testWithMoreRollup() throws IOException
  {
    final TimestampSpec timestampSpec = new TimestampSpec("t", null, null);
    final DimensionsSpec dimensionsSpec = new DimensionsSpec(ImmutableList.of(StringDimensionSchema.create("dim1")));
    final AggregatorFactory[] aggregatorFactories = {new LongSumAggregatorFactory("met1", "met1")};
    final GranularitySpec granularitySpec = new UniformGranularitySpec(
        Granularities.DAY,
        Granularities.HOUR,
        true,
        null
    );
    final DataSchema dataSchema = createDataSchema(
        timestampSpec,
        dimensionsSpec,
        aggregatorFactories,
        granularitySpec,
        null
    );
    final InputSource inputSource = createInputSource(getTestRows(), dataSchema);
    final InputFormat inputFormat = createInputFormat();

    SamplerResponse response = inputSourceSampler.sample(inputSource, inputFormat, dataSchema, null);

    Assert.assertEquals(6, response.getNumRowsRead());
    Assert.assertEquals(5, response.getNumRowsIndexed());
    Assert.assertEquals(3, response.getData().size());

    List<SamplerResponseRow> data = response.getData();

    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(0),
            ImmutableMap.of("__time", 1555934400000L, "dim1", "foo", "met1", 11L),
            null,
            null
        ),
        data.get(0)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(3),
            ImmutableMap.of("__time", 1555934400000L, "dim1", "foo2", "met1", 4L),
            null,
            null
        ),
        data.get(1)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(5),
            null,
            true,
            getUnparseableTimestampString()
        ),
        data.get(2)
    );
  }

  @Test
  public void testWithTransformsAutoDimensions() throws IOException
  {
    final TimestampSpec timestampSpec = new TimestampSpec("t", null, null);
    final DimensionsSpec dimensionsSpec = new DimensionsSpec(null);
    final TransformSpec transformSpec = new TransformSpec(
        null,
        ImmutableList.of(new ExpressionTransform("dim1PlusBar", "concat(dim1, 'bar')", TestExprMacroTable.INSTANCE))
    );
    final AggregatorFactory[] aggregatorFactories = {new LongSumAggregatorFactory("met1", "met1")};
    final GranularitySpec granularitySpec = new UniformGranularitySpec(
        Granularities.DAY,
        Granularities.HOUR,
        true,
        null
    );
    final DataSchema dataSchema = createDataSchema(
        timestampSpec,
        dimensionsSpec,
        aggregatorFactories,
        granularitySpec,
        transformSpec
    );
    final InputSource inputSource = createInputSource(getTestRows(), dataSchema);
    final InputFormat inputFormat = createInputFormat();

    SamplerResponse response = inputSourceSampler.sample(inputSource, inputFormat, dataSchema, null);

    Assert.assertEquals(6, response.getNumRowsRead());
    Assert.assertEquals(5, response.getNumRowsIndexed());
    Assert.assertEquals(4, response.getData().size());

    List<SamplerResponseRow> data = removeEmptyColumns(response.getData());

    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(0),
            ImmutableMap.of("__time", 1555934400000L, "dim1", "foo", "met1", 6L),
            null,
            null
        ),
        data.get(0)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(3),
            ImmutableMap.of("__time", 1555934400000L, "dim1", "foo2", "met1", 4L),
            null,
            null
        ),
        data.get(1)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(4),
            ImmutableMap.of("__time", 1555934400000L, "dim1", "foo", "dim2", "bar", "met1", 5L),
            null,
            null
        ),
        data.get(2)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(5),
            null,
            true,
            getUnparseableTimestampString()
        ),
        data.get(3)
    );
  }

  @Test
  public void testWithTransformsDimensionsSpec() throws IOException
  {
    final TimestampSpec timestampSpec = new TimestampSpec("t", null, null);
    final DimensionsSpec dimensionsSpec = new DimensionsSpec(
        ImmutableList.of(StringDimensionSchema.create("dim1PlusBar"))
    );
    final TransformSpec transformSpec = new TransformSpec(
        null,
        ImmutableList.of(new ExpressionTransform("dim1PlusBar", "concat(dim1 + 'bar')", TestExprMacroTable.INSTANCE))
    );
    final AggregatorFactory[] aggregatorFactories = {new LongSumAggregatorFactory("met1", "met1")};
    final GranularitySpec granularitySpec = new UniformGranularitySpec(
        Granularities.DAY,
        Granularities.HOUR,
        true,
        null
    );
    final DataSchema dataSchema = createDataSchema(
        timestampSpec,
        dimensionsSpec,
        aggregatorFactories,
        granularitySpec,
        transformSpec
    );
    final InputSource inputSource = createInputSource(getTestRows(), dataSchema);
    final InputFormat inputFormat = createInputFormat();

    SamplerResponse response = inputSourceSampler.sample(inputSource, inputFormat, dataSchema, null);

    Assert.assertEquals(6, response.getNumRowsRead());
    Assert.assertEquals(5, response.getNumRowsIndexed());
    Assert.assertEquals(3, response.getData().size());

    List<SamplerResponseRow> data = response.getData();

    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(0),
            ImmutableMap.of("__time", 1555934400000L, "dim1PlusBar", "foobar", "met1", 11L),
            null,
            null
        ),
        data.get(0)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(3),
            ImmutableMap.of("__time", 1555934400000L, "dim1PlusBar", "foo2bar", "met1", 4L),
            null,
            null
        ),
        data.get(1)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(5),
            null,
            true,
            getUnparseableTimestampString()
        ),
        data.get(2)
    );
  }

  @Test
  public void testWithFilter() throws IOException
  {
    final TimestampSpec timestampSpec = new TimestampSpec("t", null, null);
    final DimensionsSpec dimensionsSpec = new DimensionsSpec(null);
    final TransformSpec transformSpec = new TransformSpec(new SelectorDimFilter("dim1", "foo", null), null);
    final AggregatorFactory[] aggregatorFactories = {new LongSumAggregatorFactory("met1", "met1")};
    final GranularitySpec granularitySpec = new UniformGranularitySpec(
        Granularities.DAY,
        Granularities.HOUR,
        true,
        null
    );
    final DataSchema dataSchema = createDataSchema(
        timestampSpec,
        dimensionsSpec,
        aggregatorFactories,
        granularitySpec,
        transformSpec
    );
    final InputSource inputSource = createInputSource(getTestRows(), dataSchema);
    final InputFormat inputFormat = createInputFormat();

    SamplerResponse response = inputSourceSampler.sample(inputSource, inputFormat, dataSchema, null);

    Assert.assertEquals(5, response.getNumRowsRead());
    Assert.assertEquals(4, response.getNumRowsIndexed());
    Assert.assertEquals(3, response.getData().size());

    List<SamplerResponseRow> data = removeEmptyColumns(response.getData());

    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(0),
            ImmutableMap.of("__time", 1555934400000L, "dim1", "foo", "met1", 6L),
            null,
            null
        ),
        data.get(0)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(4),
            ImmutableMap.of("__time", 1555934400000L, "dim1", "foo", "dim2", "bar", "met1", 5L),
            null,
            null
        ),
        data.get(1)
    );
    assertEqualsSamplerResponseRow(
        new SamplerResponseRow(
            getRawColumns().get(5),
            null,
            true,
            getUnparseableTimestampString()
        ),
        data.get(2)
    );
  }

  private List<String> getTestRows()
  {
    switch (parserType) {
      case STR_JSON:
        return STR_JSON_ROWS;
      case STR_CSV:
        return STR_CSV_ROWS;
      default:
        throw new IAE("Unknown parser type: %s", parserType);
    }
  }

  private List<Map<String, Object>> getRawColumns()
  {
    switch (parserType) {
      case STR_JSON:
        return mapOfRows.stream().map(this::removeEmptyValues).collect(Collectors.toList());
      case STR_CSV:
        return mapOfRows;
      default:
        throw new IAE("Unknown parser type: %s", parserType);
    }
  }

  private InputFormat createInputFormat()
  {
    switch (parserType) {
      case STR_JSON:
        return new JsonInputFormat(null, null);
      case STR_CSV:
        return new CsvInputFormat(ImmutableList.of("t", "dim1", "dim2", "met1"), null, false, 0);
      default:
        throw new IAE("Unknown parser type: %s", parserType);
    }
  }

  private InputRowParser createInputRowParser(TimestampSpec timestampSpec, DimensionsSpec dimensionsSpec)
  {
    switch (parserType) {
      case STR_JSON:
        return new StringInputRowParser(new JSONParseSpec(timestampSpec, dimensionsSpec, null, null));
      case STR_CSV:
        return new StringInputRowParser(
            new DelimitedParseSpec(
                timestampSpec,
                dimensionsSpec,
                ",",
                null,
                ImmutableList.of("t", "dim1", "dim2", "met1"),
                false,
                0
            )
        );
      default:
        throw new IAE("Unknown parser type: %s", parserType);
    }
  }

  private DataSchema createDataSchema(
      @Nullable TimestampSpec timestampSpec,
      @Nullable DimensionsSpec dimensionsSpec,
      @Nullable AggregatorFactory[] aggregators,
      @Nullable GranularitySpec granularitySpec,
      @Nullable TransformSpec transformSpec
  ) throws IOException
  {
    if (useInputFormatApi) {
      return new DataSchema(
          "sampler",
          timestampSpec,
          dimensionsSpec,
          aggregators,
          granularitySpec,
          transformSpec
      );
    } else {
      final Map<String, Object> parserMap = getParserMap(createInputRowParser(timestampSpec, dimensionsSpec));
      return new DataSchema(
          "sampler",
          parserMap,
          aggregators,
          granularitySpec,
          transformSpec,
          OBJECT_MAPPER
      );
    }
  }

  private Map<String, Object> getParserMap(InputRowParser parser) throws IOException
  {
    if (useInputFormatApi) {
      throw new RuntimeException("Don't call this if useInputFormatApi = true");
    }
    return OBJECT_MAPPER.readValue(OBJECT_MAPPER.writeValueAsBytes(parser), Map.class);
  }

  private InputSource createInputSource(List<String> rows, DataSchema dataSchema)
  {
    final String data = String.join("\n", rows);
    if (useInputFormatApi) {
      return new InlineInputSource(data);
    } else {
      return new FirehoseFactoryToInputSourceAdaptor(
          new InlineFirehoseFactory(data),
          createInputRowParser(
              dataSchema == null ? new TimestampSpec(null, null, null) : dataSchema.getTimestampSpec(),
              dataSchema == null ? new DimensionsSpec(null) : dataSchema.getDimensionsSpec()
          )
      );
    }
  }

  private String getUnparseableTimestampString()
  {
    return ParserType.STR_CSV.equals(parserType)
           ? (USE_DEFAULT_VALUE_FOR_NULL
              ? "Unparseable timestamp found! Event: {t=bad_timestamp, dim1=foo, dim2=null, met1=6}"
              : "Unparseable timestamp found! Event: {t=bad_timestamp, dim1=foo, dim2=, met1=6}")
           : "Unparseable timestamp found! Event: {t=bad_timestamp, dim1=foo, met1=6}";
  }

  private String unparseableTimestampErrorString(Map<String, Object> rawColumns)
  {
    return StringUtils.format("Unparseable timestamp found! Event: %s", rawColumns);
  }

  private List<SamplerResponseRow> removeEmptyColumns(List<SamplerResponseRow> rows)
  {
    return USE_DEFAULT_VALUE_FOR_NULL
           ? rows
           : rows.stream().map(x -> x.withParsed(removeEmptyValues(x.getParsed()))).collect(Collectors.toList());
  }

  @Nullable
  private Map<String, Object> removeEmptyValues(Map<String, Object> data)
  {
    return data == null
           ? null : data.entrySet()
                        .stream()
                        .filter(x -> x.getValue() != null)
                        .filter(x -> !(x.getValue() instanceof String) || !((String) x.getValue()).isEmpty())
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private static void assertEqualsSamplerResponseRow(SamplerResponseRow row1, SamplerResponseRow row2)
  {
    Assert.assertTrue(equalsIgnoringType(row1.getInput(), row2.getInput()));
    Assert.assertEquals(row1.getParsed(), row2.getParsed());
    Assert.assertEquals(row1.getError(), row2.getError());
    Assert.assertEquals(row1.isUnparseable(), row2.isUnparseable());
  }

  private static boolean equalsIgnoringType(Map<String, Object> map1, Map<String, Object> map2)
  {
    for (Entry<String, Object> entry1 : map1.entrySet()) {
      final Object val1 = entry1.getValue();
      final Object val2 = map2.get(entry1.getKey());
      if (!equalsStringOrInteger(val1, val2)) {
        return false;
      }
    }
    return true;
  }

  private static boolean equalsStringOrInteger(Object val1, Object val2)
  {
    if (val1 == null || val2 == null) {
      return val1 == val2;
    } else if (val1.equals(val2)) {
      return true;
    } else {
      if (val1 instanceof Number || val2 instanceof Number) {
        final Integer int1, int2;
        if (val1 instanceof String) {
          int1 = Integer.parseInt((String) val1);
        } else if (val1 instanceof Number) {
          int1 = ((Number) val1).intValue();
        } else {
          int1 = null;
        }

        if (val2 instanceof String) {
          int2 = Integer.parseInt((String) val2);
        } else if (val2 instanceof Number) {
          int2 = ((Number) val2).intValue();
        } else {
          int2 = null;
        }

        return Objects.equals(int1, int2);
      }
    }

    return false;
  }
}