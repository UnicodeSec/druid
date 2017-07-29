/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.query.groupby.epinephelinae;

import org.junit.Test;

public class BufferArrayGrouperTest
{
  @Test
  public void testAggregate()
  {
//    final TestColumnSelectorFactory columnSelectorFactory = GrouperTestUtil.newColumnSelectorFactory();
//    final Grouper<Integer> grouper = newGrouper(columnSelectorFactory, 1024);
//
//    columnSelectorFactory.setRow(new MapBasedRow(0, ImmutableMap.of("value", 10L)));
//    grouper.aggregate(12);
//    grouper.aggregate(6);
//    grouper.aggregate(10);
//    grouper.aggregate(6);
//    grouper.aggregate(12);
//    grouper.aggregate(6);
//
//    final List<Entry<Integer>> expected = ImmutableList.of(
//        new Grouper.Entry<>(6, new Object[]{30L, 3L}),
//        new Grouper.Entry<>(10, new Object[]{10L, 1L}),
//        new Grouper.Entry<>(12, new Object[]{20L, 2L})
//    );
//    final List<Grouper.Entry<Integer>> unsortedEntries = Lists.newArrayList(grouper.iterator(false));
//    final List<Grouper.Entry<Integer>> sortedEntries = Lists.newArrayList(grouper.iterator(true));
//
//    Assert.assertEquals(expected, sortedEntries);
//    Assert.assertEquals(
//        expected,
//        Ordering.from((Comparator<Entry<Integer>>) (o1, o2) -> Ints.compare(o1.getKey(), o2.getKey()))
//                .sortedCopy(unsortedEntries)
//    );
  }

//  private BufferArrayGrouper<Integer> newGrouper(
//      TestColumnSelectorFactory columnSelectorFactory,
//      int bufferSize
//  )
//  {
//    final ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
//
//    final BufferArrayGrouper<Integer> grouper = new BufferArrayGrouper<>(
//        Suppliers.ofInstance(buffer),
//        GrouperTestUtil.intKeySerde(),
//        columnSelectorFactory,
//        new AggregatorFactory[]{
//            new LongSumAggregatorFactory("valueSum", "value"),
//            new CountAggregatorFactory("count")
//        },
//        1000
//    );
//    grouper.init();
//    return grouper;
//  }
}
