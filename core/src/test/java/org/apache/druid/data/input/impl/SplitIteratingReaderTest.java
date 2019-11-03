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

import com.google.common.collect.ImmutableList;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.parsers.CloseableIterator;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class SplitIteratingReaderTest
{
  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void test() throws IOException
  {
    final List<File> files = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      final File file = temporaryFolder.newFile("test_" + i);
      files.add(file);
      try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
        writer.write(StringUtils.format("%d,%s,%d\n", 20190101 + i, "name_" + i, i));
        writer.write(StringUtils.format("%d,%s,%d", 20190102 + i, "name_" + (i + 1), i + 1));
      }
    }
    final SplitIteratingReader<File> firehose = new SplitIteratingReader<>(
        new TimestampSpec("time", null, null),
        new DimensionsSpec(
            DimensionsSpec.getDefaultSchemas(ImmutableList.of("time", "name", "score"))
        ),
        new CSVInputFormat(
            ImmutableList.of("time", "name", "score"),
            null,
            false,
            0
        ),
        files.stream().flatMap(file -> {
          try {
            return ImmutableList.of(new FileSource(file, 0, 18), new FileSource(file, 18, 34)).stream();
          }
          catch (IOException e) {
            throw new RuntimeException(e);
          }
        }),
        temporaryFolder.newFolder()
    );

    try (CloseableIterator<InputRow> iterator = firehose.read()) {
      while (iterator.hasNext()) {
        System.out.println(iterator.next());
      }
    }
  }
}