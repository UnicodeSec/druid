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

package org.apache.druid.query.aggregation;

import com.google.common.collect.ImmutableList;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.query.aggregation.AggregateTestBase.TestColumn;
import org.apache.druid.query.expression.TestExprMacroTable;
import org.apache.druid.segment.ListBasedSingleColumnCursor;
import org.apache.druid.testing.InitializedNullHandlingTest;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.function.LongFunction;

@RunWith(Parameterized.class)
public class LongSumAggregatorReplaceNullModeTest extends InitializedNullHandlingTest
{
  @ClassRule
  public static AssumingReplaceNullWithDefaultMode ASSUMING_REPLACE_NULL_WITH_DEFAULT_MODE
      = new AssumingReplaceNullWithDefaultMode();

  @Parameters
  public static Collection<Object[]> constructorFeeder()
  {
    return ImmutableList.of(
        new Object[]{
            new LongSumAggregatorFactory(TestColumn.LONG_COLUMN.getName(), TestColumn.LONG_COLUMN.getName()),
            (LongFunction<Number>) val -> val
        },
        new Object[]{
            new LongSumAggregatorFactory(
                TestColumn.LONG_COLUMN.getName(),
                null,
                StringUtils.format("%s + 1", TestColumn.LONG_COLUMN.getName()),
                TestExprMacroTable.INSTANCE
            ),
            (LongFunction<Number>) val -> val + 1
        }
    );
  }

  private final LongSumAggregatorFactory aggregatorFactory;
  private final LongFunction<Number> expectedResultCalculator;

  public LongSumAggregatorReplaceNullModeTest(
      LongSumAggregatorFactory aggregatorFactory,
      LongFunction<Number> expectedResultCalculator
  )
  {
    this.aggregatorFactory = aggregatorFactory;
    this.expectedResultCalculator = expectedResultCalculator;
  }

  @Test
  public void testGet()
  {
    final long val = 1L;
    final Number expectedResult = expectedResultCalculator.apply(val);
    try (Aggregator aggregator = createAggregatorForValue(val)) {
      aggregator.aggregate();
      Assert.assertEquals(expectedResult, aggregator.get());
      Assert.assertEquals(expectedResult.longValue(), aggregator.getLong());
      Assert.assertEquals(expectedResult.doubleValue(), aggregator.getDouble(), 0);
      Assert.assertEquals(expectedResult.floatValue(), aggregator.getFloat(), 0);
    }
  }

  @Test
  public void testGetReplacedNull()
  {
    try (Aggregator aggregator = createAggregatorForValue(0L)) {
      aggregator.aggregate();
      Assert.assertFalse(aggregator.isNull());
      Assert.assertEquals(expectedResultCalculator.apply(0L), aggregator.get());
    }
  }

  private Aggregator createAggregatorForValue(@Nullable Long val)
  {
    ListBasedSingleColumnCursor<Long> cursor = new ListBasedSingleColumnCursor<>(
        Long.class,
        Collections.singletonList(val)
    );
    return aggregatorFactory.factorize(cursor.getColumnSelectorFactory());
  }
}
