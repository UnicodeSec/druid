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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.InputRowSchema;
import org.apache.druid.data.input.TextReader;
import org.apache.druid.java.util.common.parsers.JSONFlattenerMaker;
import org.apache.druid.java.util.common.parsers.JSONPathSpec;
import org.apache.druid.java.util.common.parsers.ObjectFlattener;
import org.apache.druid.java.util.common.parsers.ObjectFlatteners;
import org.apache.druid.java.util.common.parsers.ParseException;

import java.io.IOException;
import java.util.Map;

public class JsonReader extends TextReader
{
  private final ObjectFlattener<JsonNode> flattener;
  private final ObjectMapper mapper;

  public JsonReader(
      InputRowSchema inputRowSchema,
      JSONPathSpec flattenSpec,
      ObjectMapper mapper
  )
  {
    super(inputRowSchema);
    this.flattener = ObjectFlatteners.create(flattenSpec, new JSONFlattenerMaker());
    this.mapper = mapper;
  }

  @Override
  public InputRow readLine(String line) throws IOException, ParseException
  {
    final JsonNode document = mapper.readValue(line, JsonNode.class);
    final Map<String, Object> flattened = flattener.flatten(document);
    return MapInputRowParser.parse(
        getInputRowSchema().getTimestampSpec(),
        getInputRowSchema().getDimensionsSpec(),
        flattened
    );
  }

  @Override
  public int getNumHeaderLinesToSkip()
  {
    return 0;
  }

  @Override
  public boolean needsToProcessHeaderLine()
  {
    return false;
  }

  @Override
  public void processHeaderLine(String line)
  {
    // do nothing
  }
}
