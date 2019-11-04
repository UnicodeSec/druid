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

import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import org.apache.druid.data.input.InputSplit;
import org.apache.druid.data.input.SplitSource;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.metadata.PasswordProvider;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.util.Base64;

public class HttpSource implements SplitSource<URI>
{
  private final InputSplit<URI> split;
  @Nullable
  private final String httpAuthenticationUsername;
  @Nullable
  private final PasswordProvider httpAuthenticationPasswordProvider;

  HttpSource(
      InputSplit<URI> split,
      @Nullable String httpAuthenticationUsername,
      @Nullable PasswordProvider httpAuthenticationPasswordProvider
  )
  {
    this.split = split;
    this.httpAuthenticationUsername = httpAuthenticationUsername;
    this.httpAuthenticationPasswordProvider = httpAuthenticationPasswordProvider;
  }

  @Override
  public InputSplit<URI> getSplit()
  {
    return split;
  }

  @Override
  public InputStream open() throws IOException
  {
    return openURLConnection(split.get()).getInputStream();
  }

  @Override
  public Predicate<Throwable> getRetryCondition()
  {
    return t -> t instanceof IOException;
  }

  private URLConnection openURLConnection(URI object) throws IOException
  {
    URLConnection urlConnection = object.toURL().openConnection();
    if (!Strings.isNullOrEmpty(httpAuthenticationUsername) && httpAuthenticationPasswordProvider != null) {
      String userPass = httpAuthenticationUsername + ":" + httpAuthenticationPasswordProvider.getPassword();
      String basicAuthString = "Basic " + Base64.getEncoder().encodeToString(StringUtils.toUtf8(userPass));
      urlConnection.setRequestProperty("Authorization", basicAuthString);
    }
    return urlConnection;
  }
}
