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
package io.druid.indexing.common.task;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import io.druid.indexer.TaskState;
import io.druid.indexer.TaskStatusPlus;

import java.util.List;

class TaskHistory<T extends Task>
{
  private final SubTaskSpec<T> spec;
  private final List<TaskStatusPlus> attemptHistory; // old to recent

  TaskHistory(SubTaskSpec<T> spec, List<TaskStatusPlus> attemptHistory)
  {
    attemptHistory.forEach(status -> {
      Preconditions.checkState(
          status.getState() == TaskState.SUCCESS || status.getState() == TaskState.FAILED,
          "Complete tasks should be recorded, but the state of task[%s] is [%s]",
          status.getId(),
          status.getState()
      );
    });
    this.spec = spec;
    this.attemptHistory = ImmutableList.copyOf(attemptHistory);
  }

  SubTaskSpec<T> getSpec()
  {
    return spec;
  }

  List<TaskStatusPlus> getAttemptHistory()
  {
    return attemptHistory;
  }

  boolean isEmpty()
  {
    return attemptHistory.isEmpty();
  }
}
