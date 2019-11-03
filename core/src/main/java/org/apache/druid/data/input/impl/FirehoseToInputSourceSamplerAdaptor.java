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

package org.apache.druid.data.input.impl;

import org.apache.druid.data.input.Firehose;
import org.apache.druid.data.input.InputRowPlusRaw;
import org.apache.druid.data.input.InputSourceSampler;
import org.apache.druid.java.util.common.parsers.CloseableIterator;

import java.io.IOException;

public class FirehoseToInputSourceSamplerAdaptor implements InputSourceSampler
{
  private final Firehose firehose;

  public FirehoseToInputSourceSamplerAdaptor(Firehose firehose)
  {
    this.firehose = firehose;
  }

  @Override
  public CloseableIterator<InputRowPlusRaw> sample()
  {
    return new CloseableIterator<InputRowPlusRaw>()
    {
      @Override
      public boolean hasNext()
      {
        try {
          return firehose.hasMore();
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      }

      @Override
      public InputRowPlusRaw next()
      {
        try {
          return firehose.nextRowWithRaw();
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      }

      @Override
      public void close() throws IOException
      {
        firehose.close();
      }
    };
  }
}