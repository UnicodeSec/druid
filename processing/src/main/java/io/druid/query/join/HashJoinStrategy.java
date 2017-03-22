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

package io.druid.query.join;

import io.druid.query.join.exception.NotImplementedException;

public class HashJoinStrategy implements JoinStrategy
{
  public HashJoinQueryEngine createEngine(JoinSpec joinSpec)
  {
    if (isEquiJoin(joinSpec) && isAndPredicate(joinSpec)) {
      // TODO: singleton
      return new HashJoinQueryEngine();
    } else {
      throw new NotImplementedException();
    }
  }

  private static boolean isEquiJoin(JoinSpec joinSpec)
  {
    return true;
  }

  // TODO: rename
  private static boolean isAndPredicate(JoinSpec joinSpec)
  {
    return true;
  }
}
