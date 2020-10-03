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

package org.apache.druid.query;

import com.google.common.collect.ImmutableList;
import org.apache.druid.java.util.common.concurrent.Execs;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.query.dimension.DefaultDimensionSpec;
import org.apache.druid.testing.InitializedNullHandlingTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;

public class DictionaryMergingQueryRunnerTest extends InitializedNullHandlingTest
{
  private ExecutorService service;

  @Before
  public void setup()
  {
    service = Execs.multiThreaded(4, "test-%d");
  }

  @After
  public void teardown()
  {
    service.shutdownNow();
  }

  @Test
  public void testMerge()
  {
    final DictionaryMergingQueryRunnerFactory factory = new DictionaryMergingQueryRunnerFactory();
    List<QueryRunner<DictionaryConversion[]>> runners = QueryRunnerTestHelper.makeQueryRunners(factory);
    final QueryRunner<DictionaryConversion[]> mergingRunner = factory.mergeRunners(
        service,
        ImmutableList.of(runners.get(2), runners.get(3), runners.get(4))
    );
    final Query<DictionaryConversion[]> query = new DictionaryMergeQuery(
        new TableDataSource(QueryRunnerTestHelper.DATA_SOURCE),
        QueryRunnerTestHelper.FIRST_TO_THIRD,
        ImmutableList.of(new DefaultDimensionSpec("quality", "alias"))
    );
    final Sequence<DictionaryConversion[]> sequence = mergingRunner.run(QueryPlus.wrap(query));
    for (DictionaryConversion[] conversions : sequence.toList()) {
      System.out.println(Arrays.toString(conversions));
    }
  }
}
