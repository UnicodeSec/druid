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

package org.apache.druid.query.movingaverage.averagers;

import org.apache.druid.query.aggregation.AggregatorFactory;

import java.util.Map;

public class CumulativeLongSumAverager extends BaseAverager<Long, Long>
{
  private int startFrom = 0;

  public CumulativeLongSumAverager(
      String name,
      String fieldName
  )
  {
    super(Long.class, 1, name, fieldName, 1);
//    super(Long.class, 2, name, fieldName, 1);
  }

  @Override
  public void addElement(Map<String, Object> e, Map<String, AggregatorFactory> a)
  {
    Object metric = e.get(getFieldName());
    final Long finalMetric;
    if (a.containsKey(getFieldName()) && isShouldFinalize()) {
      AggregatorFactory af = a.get(getFieldName());
      finalMetric = metric != null ? (Long) af.finalizeComputation(metric) : null;
    } else {
      finalMetric = ((Number) metric).longValue();
    }
    if (getCurrent() != null && finalMetric != null) {
      update(getCurrent() + finalMetric);
    } else {
      update(finalMetric);
    }
  }

  @Override
  protected Long computeResult()
  {
    final Long[] obj = getBuckets();
    return obj[0] == null ? 0 : obj[0];
  }

//  @Override
//  protected Long computeResult()
//  {
//    long result = 0;
//    int cycleSize = getCycleSize();
//    int numBuckets = getNumBuckets();
//    Number[] obj = getBuckets();
//
//    for (int i = 0; i < numBuckets; i += cycleSize) {
//      final Number next = obj[(i + startFrom) % numBuckets];
//      if (next != null) {
//        result += next.longValue();
//      } else {
//        result += 0;
//      }
//    }
//
//    startFrom++;
//    return result;
//  }
}
