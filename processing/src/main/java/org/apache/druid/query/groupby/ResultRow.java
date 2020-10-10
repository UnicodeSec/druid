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

package org.apache.druid.query.groupby;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.apache.druid.data.input.MapBasedRow;
import org.apache.druid.data.input.Row;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.PostAggregator;
import org.apache.druid.query.dimension.DimensionSpec;
import org.apache.druid.segment.column.RowSignature;
import org.joda.time.DateTime;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Used by {@link GroupByQuery} for results. Each row is positional, and has the following fields, in order:
 *
 * - Timestamp (optional; only if granularity != ALL)
 * - Dimensions (in order)
 * - Aggregators (in order)
 * - Post-aggregators (optional; in order, if present)
 *
 * ResultRows may sometimes be created without space reserved for post-aggregators, in contexts where it is known
 * that post-aggregators will not be computed.
 *
 * @see GroupByQuery#getResultRowSignature()
 * @see GroupByQuery#getResultRowHasTimestamp()
 * @see GroupByQuery#getUniversalTimestamp()
 * @see GroupByQuery#getResultRowDimensionStart()
 * @see GroupByQuery#getResultRowAggregatorStart()
 * @see GroupByQuery#getResultRowPostAggregatorStart()
 * @see GroupByQuery#getResultRowSizeWithPostAggregators()
 * @see GroupByQuery#getResultRowSizeWithoutPostAggregators()
 */
public class ResultRow
{
  private final Object[] row;

  protected ResultRow(final Object[] row)
  {
    this.row = row;
  }

  /**
   * Create a row from an array of objects.
   */
  @JsonCreator
  public static ResultRow of(final Object... row)
  {
    return new ResultRow(row);
  }

  /**
   * Create a row of a certain size, initialized to all nulls.
   */
  public static ResultRow create(final int size)
  {
    return new ResultRow(new Object[size]);
  }

  /**
   * Create a row based on a legacy {@link Row} that was generated by a given {@link GroupByQuery}. This is useful
   * for deserializing rows that have come off the wire in the older format. (In the past, GroupBy query results
   * were sequences of {@link Row}, not ResultRow.)
   *
   * @param row   legacy row
   * @param query query corresponding to the output ResultRow
   */
  public static ResultRow fromLegacyRow(Row row, final GroupByQuery query)
  {
    // Can't be sure if we'll get result rows with or without postaggregations, so be safe.
    final ResultRow resultRow = ResultRow.create(query.getResultRowSizeWithPostAggregators());

    int i = 0;
    if (query.getResultRowHasTimestamp()) {
      resultRow.set(i++, row.getTimestamp().getMillis());
    }

    for (DimensionSpec dimensionSpec : query.getDimensions()) {
      resultRow.set(i++, row.getRaw(dimensionSpec.getOutputName()));
    }

    for (AggregatorFactory aggregatorFactory : query.getAggregatorSpecs()) {
      resultRow.set(i++, row.getRaw(aggregatorFactory.getName()));
    }

    for (PostAggregator postAggregator : query.getPostAggregatorSpecs()) {
      resultRow.set(i++, row.getRaw(postAggregator.getName()));
    }

    return resultRow;
  }

  /**
   * Get the backing array for this row (not a copy).
   */
  @JsonValue
  public Object[] getArray()
  {
    return row;
  }

  public void set(final int i, @Nullable final Object o)
  {
    row[i] = o;
  }

  @Nullable
  public Object get(final int i)
  {
    return row[i];
  }

  public int getInt(final int i)
  {
    // TODO Virtual column sometimes returns double....
    assert row[i] != null && (row[i] instanceof Number);
    return ((Number) row[i]).intValue();
  }

  public long getLong(final int i)
  {
    return ((Number) row[i]).longValue();
  }

  public int length()
  {
    return row.length;
  }

  /**
   * Returns a copy of this row. The backing array will be copied as well.
   */
  public ResultRow copy()
  {
    final Object[] newArray = new Object[row.length];
    System.arraycopy(row, 0, newArray, 0, row.length);
    return new ResultRow(newArray);
  }

  /**
   * Returns a Map representation of the data in this row. Does not include the timestamp.
   */
  public Map<String, Object> toMap(final GroupByQuery query)
  {
    final RowSignature signature = query.getResultRowSignature();
    final Map<String, Object> map = new HashMap<>();

    for (int i = query.getResultRowDimensionStart(); i < row.length; i++) {
      final String columnName = signature.getColumnName(i);
      map.put(columnName, row[i]);
    }

    return map;
  }

  /**
   * Returns a {@link Row} representation of the data in this row.
   */
  public MapBasedRow toMapBasedRow(final GroupByQuery query)
  {
    // May be null, if so it'll get replaced later
    final DateTime timestamp;

    if (query.getResultRowHasTimestamp()) {
      timestamp = query.getGranularity().toDateTime(getLong(0));
    } else {
      timestamp = query.getUniversalTimestamp();
    }

    return new MapBasedRow(timestamp, toMap(query));
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
    ResultRow resultRow = (ResultRow) o;
    return Arrays.equals(row, resultRow.row);
  }

  @Override
  public int hashCode()
  {
    return Arrays.hashCode(row);
  }

  @Override
  public String toString()
  {
    return "ResultRow{" +
           "row=" + Arrays.toString(row) +
           '}';
  }
}
