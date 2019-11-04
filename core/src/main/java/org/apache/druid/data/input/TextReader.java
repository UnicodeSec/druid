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

package org.apache.druid.data.input;

import org.apache.commons.io.LineIterator;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.data.input.impl.TimestampSpec;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.parsers.CloseableIterator;
import org.apache.druid.java.util.common.parsers.ParseException;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

public abstract class TextReader implements SplitReader
{
  private final TimestampSpec timestampSpec;
  private final DimensionsSpec dimensionsSpec;

  public TextReader(TimestampSpec timestampSpec, DimensionsSpec dimensionsSpec)
  {
    this.timestampSpec = timestampSpec;
    this.dimensionsSpec = dimensionsSpec;
  }

  public TimestampSpec getTimestampSpec()
  {
    return timestampSpec;
  }

  public DimensionsSpec getDimensionsSpec()
  {
    return dimensionsSpec;
  }

  @Override
  public CloseableIterator<InputRow> read(SplitSource source, File temporaryDirectory) throws IOException
  {
    return lineIterator(source).map(line -> {
      try {
        return readLine(line);
      }
      catch (IOException e) {
        throw new ParseException(e, "Unable to parse row [%s]", line);
      }
    });
  }

  @Override
  public CloseableIterator<InputRowPlusRaw> sample(SplitSource source, File temporaryDirectory)
      throws IOException
  {
    return lineIterator(source).map(line -> {
      try {
        return InputRowPlusRaw.of(readLine(line), StringUtils.toUtf8(line));
      }
      catch (ParseException e) {
        return InputRowPlusRaw.of(StringUtils.toUtf8(line), e);
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
  }

  private CloseableIterator<String> lineIterator(SplitSource source) throws IOException
  {
    final LineIterator delegate = new LineIterator(
        new InputStreamReader(source.open(), StringUtils.UTF8_STRING)
    );
    final int numHeaderLines = getNumHeaderLines();
    for (int i = 0; i < numHeaderLines && delegate.hasNext(); i++) {
      processHeaderLine(delegate.nextLine());
    }

    return new CloseableIterator<String>()
    {
      @Override
      public boolean hasNext()
      {
        return delegate.hasNext();
      }

      @Override
      public String next()
      {
        return delegate.nextLine();
      }

      @Override
      public void close() throws IOException
      {
        delegate.close();
      }
    };
  }

  public abstract InputRow readLine(String line) throws IOException, ParseException;

  public abstract int getNumHeaderLines();

  public abstract void processHeaderLine(String line) throws IOException;
}
