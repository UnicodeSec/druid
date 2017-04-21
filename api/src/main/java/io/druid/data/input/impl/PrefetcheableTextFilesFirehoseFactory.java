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

package io.druid.data.input.impl;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.io.CountingOutputStream;
import com.google.common.io.Files;
import io.druid.data.input.Firehose;
import io.druid.java.util.common.logger.Logger;
import org.apache.commons.io.Charsets;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.LineIterator;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PrefetcheableTextFilesFirehoseFactory is an abstract firehose factory for reading text files.  The firehose returned
 * by this class provides three key functionalities.
 *
 * <ul>
 * <li>Caching: for the first call of {@link #connect(StringInputRowParser)}, it caches objects in a local disk
 * up to {@link #maxCacheCapacityBytes}.  These caches are NOT deleted until the process terminates,
 * and thus can be used for future reads.</li>
 * <li>Fetching: when it reads all cached data, it fetches remaining objects into a local disk and reads data from
 * them.  For the performance reason, prefetch technique is used, that is, when the size of remaining cached or
 * fetched data is smaller than {@link #prefetchTriggerBytes}, a background prefetch thread automatically starts to
 * fetch remaining objects.</li>
 * <li>Retry: if an exception occurs while downloading an object, it retries again up to
 * {@link #maxFetchRetry}.</li>
 * </ul>
 *
 * This implementation can be useful when the cost for reading input objects is large as reading from AWS S3 because
 * IndexTask can read the whole data twice for determining partition specs and generating segments if the intervals of
 * GranularitySpec is not specified.
 */
public abstract class PrefetcheableTextFilesFirehoseFactory<ObjectType>
    extends AbstractTextFilesFirehoseFactory<ObjectType>
{
  private static final Logger LOG = new Logger(PrefetcheableTextFilesFirehoseFactory.class);
  private static final long DEFAULT_MAX_CACHE_CAPACITY_BYTES = 1024 * 1024 * 1024; // 1GB
  private static final long DEFAULT_MAX_FETCH_CAPACITY_BYTES = 1024 * 1024 * 1024; // 1GB
  private static final int DEFAULT_MAX_FETCH_RETRY = 3;
  private static final int DEFAULT_FETCH_TIMEOUT = 60_000; // 60 secs

  // The below two variables are roughly the max size of total cached/fetched objects, but the actual cached/fetched
  // size can be larger. The reason is our current client implementations for cloud storages like s3 don't support range
  // scan yet, so we must download the whole file at once. It's still possible for the size of cached/fetched data to
  // not exceed these variables by estimating the after-fetch size, but it makes us consider the case when any files
  // cannot be fetched due to their large size, which makes the implementation complicated.
  private long maxCacheCapacityBytes;
  private final long maxFetchCapacityBytes;

  private final long prefetchTriggerBytes;
  private File baseDir; // Directory for cached and fetched files

  private final List<FetchedFile> cacheFiles;
  private final LinkedBlockingQueue<FetchedFile> fetchFiles;

  // Number of bytes currently fetched files.
  // This is updated when fetch a file is successfully fetched or a fetched file is deleted.
  private final AtomicLong fetchedBytes = new AtomicLong(0);

  // timeout for fetching an object from the remote site
  private final int fetchTimeout;

  // maximum retry for fetching an object from the remote site
  private final int maxFetchRetry;

  private volatile int nextFetchIndex;
  private volatile boolean beingFetched;
  private volatile Exception fetchException;

  public PrefetcheableTextFilesFirehoseFactory(
      Collection<ObjectType> objects,
      Long maxCacheCapacityBytes,
      Long maxFetchCapacityBytes,
      Long prefetchTriggerBytes,
      Integer fetchTimeout,
      Integer maxFetchRetry
  )
  {
    super(objects);
    this.maxCacheCapacityBytes = maxCacheCapacityBytes == null
                                 ? DEFAULT_MAX_CACHE_CAPACITY_BYTES
                                 : maxCacheCapacityBytes;
    this.maxFetchCapacityBytes = maxFetchCapacityBytes == null
                                 ? DEFAULT_MAX_FETCH_CAPACITY_BYTES
                                 : maxFetchCapacityBytes;
    this.prefetchTriggerBytes = prefetchTriggerBytes == null
                                ? this.maxFetchCapacityBytes / 2
                                : prefetchTriggerBytes;
    this.fetchTimeout = fetchTimeout == null ? DEFAULT_FETCH_TIMEOUT : fetchTimeout;
    this.maxFetchRetry = maxFetchRetry == null ? DEFAULT_MAX_FETCH_RETRY : maxFetchRetry;

    cacheFiles = new ArrayList<>();
    fetchFiles = new LinkedBlockingQueue<>();
  }

  @VisibleForTesting
  List<FetchedFile> getCacheFiles()
  {
    return cacheFiles;
  }

  /**
   * Cache objects in a local disk up to {@link #maxCacheCapacityBytes}.
   */
  private void cache()
  {
    double totalFetchedBytes = 0;
    final List<ObjectType> objects = getObjects();
    try {
      for (int i = 0; i < objects.size() && totalFetchedBytes < maxCacheCapacityBytes; i++) {
        final ObjectType object = objects.get(i);
        LOG.info("Caching object[%s]", object);
        final File outFile = File.createTempFile("cache-", null, baseDir);
        totalFetchedBytes += download(object, outFile, 0);
        cacheFiles.add(new FetchedFile(outFile, isGzipped(object)));
        nextFetchIndex++;
      }
    }
    catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  /**
   * Fetch objects to a local disk up to {@link #maxFetchCapacityBytes}.  This method is not thread safe and must be
   * called by a single thread.  Note that even {@link #maxFetchCapacityBytes} is 0, at least 1 file is always fetched.
   * This is for simplifying design, and should be improved when our client implementations for cloud storages like S3
   * support range scan.
   */
  private void fetch()
  {
    if (!beingFetched) {
      beingFetched = true;
      final List<ObjectType> objects = getObjects();
      try {
        for (int i = nextFetchIndex; i < objects.size() && fetchedBytes.get() <= maxFetchCapacityBytes; i++) {
          final ObjectType object = objects.get(i);
          LOG.info("Fetching object[%s]", object);
          final File outFile = File.createTempFile("fetch-", null, baseDir);
          fetchedBytes.addAndGet(download(object, outFile, 0));
          fetchFiles.put(new FetchedFile(outFile, isGzipped(object)));
          nextFetchIndex++;
        }
      }
      catch (Exception e) {
        fetchException = e;
        throw Throwables.propagate(e);
      }
      finally {
        beingFetched = false;
      }
    }
  }

  /**
   * Downloads an object. It retires downloading {@link #maxFetchRetry} times and throws that exception.
   *
   * @param object  an object to be downloaded
   * @param outFile a file which the object data is stored
   * @param retry   current retry count
   *
   * @return number of downloaded bytes
   *
   * @throws IOException
   */
  private long download(ObjectType object, File outFile, int retry) throws IOException
  {
    try (final InputStream is = openStream(object);
         final CountingOutputStream cos = new CountingOutputStream(new FileOutputStream(outFile))) {
      IOUtils.copy(is, cos);
      return cos.getCount();
    }
    catch (IOException e) {
      if (retry < maxFetchRetry) {
        LOG.error(e, "Failed to download object[%s], retrying (%d of %d)", object, retry + 1, maxFetchRetry);
        outFile.delete();
        return download(object, outFile, retry + 1);
      } else {
        LOG.error(e, "Failed to download object[%s], retries exhausted, aborting", object);
        throw e;
      }
    }
  }

  @Override
  public Firehose connect(StringInputRowParser firehoseParser) throws IOException
  {
    if (baseDir == null) {
      baseDir = Files.createTempDir();
      baseDir.deleteOnExit();
      cache();
    } else {
      nextFetchIndex = cacheFiles.size();
    }

    // fetchExecutor is responsible for background data fetching
    final ExecutorService fetchExecutor = Executors.newSingleThreadExecutor();

    return new FileIteratingFirehose(
        new Iterator<LineIterator>()
        {
          final Iterator<FetchedFile> cacheFileIterator = cacheFiles.iterator();
          long remainingCachedBytes = cacheFiles.stream()
                                                .mapToLong(fetchedFile -> fetchedFile.file.length())
                                                .sum();

          {
            fetchIfNeeded(remainingCachedBytes);
          }

          @Override
          public boolean hasNext()
          {
            return cacheFileIterator.hasNext()
                   || !fetchFiles.isEmpty()
                   || nextFetchIndex < getObjects().size();
          }

          private void fetchIfNeeded(long remainingBytes)
          {
            if (!beingFetched && remainingBytes <= prefetchTriggerBytes) {
              fetchExecutor.submit(() -> fetch());
            }
          }

          @Override
          public LineIterator next()
          {
            if (!hasNext()) {
              throw new NoSuchElementException();
            }

            if (fetchException != null) {
              throw Throwables.propagate(fetchException);
            }

            final FetchedFile fetchedFile;
            final Closeable closeable;
            // Check cache first
            if (cacheFileIterator.hasNext()) {
              fetchedFile = cacheFileIterator.next();
              remainingCachedBytes -= fetchedFile.file.length();
              fetchIfNeeded(remainingCachedBytes);
              closeable = () -> {
              };
            } else {
              if (!fetchFiles.isEmpty()) {
                // If there are already fetched files, use them
                fetchedFile = fetchFiles.poll();
                fetchIfNeeded(fetchedBytes.get());
              } else {
                // Otherwise, wait for fetching
                try {
                  fetchIfNeeded(fetchedBytes.get());
                  fetchedFile = fetchFiles.poll(fetchTimeout, TimeUnit.MILLISECONDS);
                  if (fetchedFile == null) {
                    throw new RuntimeException(new TimeoutException());
                  }
                }
                catch (InterruptedException e) {
                  throw Throwables.propagate(e);
                }
              }
              closeable = () -> {
                final long fileSize = fetchedFile.file.length();
                fetchedFile.file.delete();
                fetchedBytes.addAndGet(-fileSize);
              };
            }

            try {
              final InputStream stream = FileUtils.openInputStream(fetchedFile.file);

              return new ResourceCloseableLineIterator(
                  new BufferedReader(
                      new InputStreamReader(wrapIfNeeded(stream, fetchedFile.isGzipped), Charsets.UTF_8)
                  ),
                  closeable
              );
            }
            catch (IOException e) {
              throw Throwables.propagate(e);
            }
          }
        },
        firehoseParser,
        fetchExecutor::shutdown
    );
  }

  static class FetchedFile
  {
    private final File file;
    private final boolean isGzipped;

    FetchedFile(File file, boolean isGzipped)
    {
      this.file = file;
      this.isGzipped = isGzipped;
    }
  }

  /**
   * This class calls the {@link Closeable#close()} method of the resourceCloser when it is closed.
   */
  static class ResourceCloseableLineIterator extends LineIterator
  {
    private final Closeable resourceCloser;

    public ResourceCloseableLineIterator(Reader reader, Closeable resourceCloser) throws IllegalArgumentException
    {
      super(reader);
      this.resourceCloser = resourceCloser;
    }

    @Override
    public void close()
    {
      super.close();
      try {
        resourceCloser.close();
      }
      catch (IOException e) {
        throw Throwables.propagate(e);
      }
    }
  }
}
