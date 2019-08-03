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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

public class GeneratedPartitionsReport implements SubTaskReport
{
  public static final String TYPE = "generated_partitions";

  private final String taskId;
  private final List<PartitionStat> partitionStats;

  @JsonCreator
  public GeneratedPartitionsReport(
      @JsonProperty("taskId") String taskId,
      @JsonProperty("partitionStats") List<PartitionStat> partitionStats
  )
  {
    this.taskId = taskId;
    this.partitionStats = partitionStats;
  }

  @Override
  @JsonProperty
  public String getTaskId()
  {
    return taskId;
  }

  @JsonProperty
  public List<PartitionStat> getPartitionStats()
  {
    return partitionStats;
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
    GeneratedPartitionsReport that = (GeneratedPartitionsReport) o;
    return Objects.equals(taskId, that.taskId) &&
           Objects.equals(partitionStats, that.partitionStats);
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(taskId, partitionStats);
  }

  public static class PartitionStat
  {
    private final String taskExecutorHost;
    private final int taskExecutorPort;
    private final Interval interval;
    private final int partitionId;
    @Nullable
    private final Integer numRows;
    @Nullable
    private final Long sizeBytes;

    @JsonCreator
    public PartitionStat(
        @JsonProperty("taskExecutorHost") String taskExecutorHost,
        @JsonProperty("taskExecutorPort") int taskExecutorPort,
        @JsonProperty("interval") Interval interval,
        @JsonProperty("partitionId") int partitionId,
        @JsonProperty("numRows") @Nullable Integer numRows,
        @JsonProperty("sizeBytes") @Nullable Long sizeBytes
    )
    {
      this.taskExecutorHost = taskExecutorHost;
      this.taskExecutorPort = taskExecutorPort;
      this.interval = interval;
      this.partitionId = partitionId;
      this.numRows = numRows == null ? 0 : numRows;
      this.sizeBytes = sizeBytes == null ? 0 : sizeBytes;
    }

    @JsonProperty
    public String getTaskExecutorHost()
    {
      return taskExecutorHost;
    }

    @JsonProperty
    public int getTaskExecutorPort()
    {
      return taskExecutorPort;
    }

    @JsonProperty
    public Interval getInterval()
    {
      return interval;
    }

    @JsonProperty
    public int getPartitionId()
    {
      return partitionId;
    }

    @Nullable
    @JsonProperty
    public Integer getNumRows()
    {
      return numRows;
    }

    @Nullable
    @JsonProperty
    public Long getSizeBytes()
    {
      return sizeBytes;
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
      PartitionStat that = (PartitionStat) o;
      return taskExecutorPort == that.taskExecutorPort &&
             partitionId == that.partitionId &&
             Objects.equals(taskExecutorHost, that.taskExecutorHost) &&
             Objects.equals(interval, that.interval) &&
             Objects.equals(numRows, that.numRows) &&
             Objects.equals(sizeBytes, that.sizeBytes);
    }

    @Override
    public int hashCode()
    {
      return Objects.hash(taskExecutorHost, taskExecutorPort, interval, partitionId, numRows, sizeBytes);
    }
  }
}