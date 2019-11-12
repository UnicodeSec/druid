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

package org.apache.druid.firehose.s3;

import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.S3Object;
import com.google.common.base.Predicate;
import org.apache.druid.data.input.ObjectSource;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.storage.s3.S3Utils;
import org.apache.druid.storage.s3.ServerSideEncryptingAmazonS3;
import org.apache.druid.utils.CompressionUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

public class S3Source implements ObjectSource<URI>
{
  private final ServerSideEncryptingAmazonS3 s3Client;
  private final URI uri;
  private final long start;
  private final long length;

  public S3Source(ServerSideEncryptingAmazonS3 s3Client, URI uri)
  {
    this(s3Client, uri, -1, -1);
  }

  public S3Source(ServerSideEncryptingAmazonS3 s3Client, URI uri, long start, long length)
  {
    this.s3Client = s3Client;
    this.uri = uri;
    this.start = start;
    this.length = length;
  }

  @Override
  public URI getUri()
  {
    return uri;
  }

  public URI getHadoopyUri()
  {
    return URI.create("s3a" + uri.toString().substring(uri.toString().indexOf(':')));
  }

  @Override
  public URI getObject()
  {
    return uri;
  }

  @Override
  public InputStream open() throws IOException
  {
    try {
      // Get data of the given object and open an input stream
      final String bucket = uri.getAuthority();
      final String key = S3Utils.extractS3Key(uri);

      final S3Object s3Object = s3Client.getObject(bucket, key);
      if (s3Object == null) {
        throw new ISE("Failed to get an s3 object for bucket[%s] and key[%s]", bucket, key);
      }
      return CompressionUtils.decompress(s3Object.getObjectContent(), uri.toString());
    }
    catch (AmazonS3Exception e) {
      throw new IOException(e);
    }
  }

  @Override
  public Predicate<Throwable> getRetryCondition()
  {
    return S3Utils.S3RETRY;
  }

  @Override
  public long start()
  {
    return start;
  }

  @Override
  public long length()
  {
    return length;
  }
}
