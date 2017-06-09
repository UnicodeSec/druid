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

package io.druid.segment.realtime;


import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.inject.Inject;
import com.metamx.emitter.EmittingLogger;
import io.druid.concurrent.Execs;
import io.druid.data.input.Committer;
import io.druid.data.input.Firehose;
import io.druid.data.input.FirehoseV2;
import io.druid.data.input.InputRow;
import io.druid.java.util.common.io.Closer;
import io.druid.java.util.common.lifecycle.LifecycleStart;
import io.druid.java.util.common.lifecycle.LifecycleStop;
import io.druid.query.FinalizeResultsQueryRunner;
import io.druid.query.NoopQueryRunner;
import io.druid.query.Query;
import io.druid.query.QueryRunner;
import io.druid.query.QueryRunnerFactory;
import io.druid.query.QueryRunnerFactoryConglomerate;
import io.druid.query.QuerySegmentWalker;
import io.druid.query.QueryToolChest;
import io.druid.query.SegmentDescriptor;
import io.druid.query.spec.SpecificSegmentSpec;
import io.druid.segment.indexing.DataSchema;
import io.druid.segment.indexing.RealtimeTuningConfig;
import io.druid.segment.realtime.plumber.Committers;
import io.druid.segment.realtime.plumber.Plumber;
import io.druid.segment.realtime.plumber.Plumbers;
import io.druid.server.coordination.DataSegmentServerAnnouncer;
import org.joda.time.Interval;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 */
public class RealtimeManager implements QuerySegmentWalker
{
  private static final EmittingLogger log = new EmittingLogger(RealtimeManager.class);

  private final List<FireDepartment> fireDepartments;
  private final QueryRunnerFactoryConglomerate conglomerate;
  private final DataSegmentServerAnnouncer serverAnnouncer;

  /**
   * key=data source name,value=mappings of partition number to FireChief
   */
  private final Map<String, Map<Integer, FireChief>> chiefs;
  private ExecutorService fireChiefExecutor;

  @Inject
  public RealtimeManager(
      List<FireDepartment> fireDepartments,
      QueryRunnerFactoryConglomerate conglomerate,
      DataSegmentServerAnnouncer serverAnnouncer
  )
  {
    this(fireDepartments, conglomerate, serverAnnouncer, Maps.newHashMap());
  }

  @VisibleForTesting
  RealtimeManager(
      List<FireDepartment> fireDepartments,
      QueryRunnerFactoryConglomerate conglomerate,
      DataSegmentServerAnnouncer serverAnnouncer,
      Map<String, Map<Integer, FireChief>> chiefs
  )
  {
    this.fireDepartments = fireDepartments;
    this.conglomerate = conglomerate;
    this.serverAnnouncer = serverAnnouncer;
    this.chiefs = chiefs == null ? Maps.newHashMap() : Maps.newHashMap(chiefs);
  }

  @LifecycleStart
  public void start() throws IOException
  {
    serverAnnouncer.announce();

    fireChiefExecutor = Execs.multiThreaded(fireDepartments.size(), "chief-%d");

    for (final FireDepartment fireDepartment : fireDepartments) {
      final DataSchema schema = fireDepartment.getDataSchema();

      final FireChief chief = new FireChief(fireDepartment, conglomerate);
      chiefs.computeIfAbsent(schema.getDataSource(), k -> new HashMap<>())
            .put(fireDepartment.getTuningConfig().getShardSpec().getPartitionNum(), chief);

      fireChiefExecutor.submit(chief);
    }
  }

  @LifecycleStop
  public void stop()
  {
    if (fireChiefExecutor != null) {
      fireChiefExecutor.shutdownNow();
    }
    serverAnnouncer.unannounce();
  }

  public FireDepartmentMetrics getMetrics(String datasource)
  {
    Map<Integer, FireChief> chiefs = this.chiefs.get(datasource);
    if (chiefs == null) {
      return null;
    }
    FireDepartmentMetrics snapshot = null;
    for (FireChief chief : chiefs.values()) {
      if (snapshot == null) {
        snapshot = chief.getMetrics().snapshot();
      } else {
        snapshot.merge(chief.getMetrics());
      }
    }
    return snapshot;
  }

  @Override
  public <T> QueryRunner<T> getQueryRunnerForIntervals(final Query<T> query, Iterable<Interval> intervals)
  {
    final QueryRunnerFactory<T, Query<T>> factory = conglomerate.findFactory(query);
    final Map<Integer, FireChief> partitionChiefs = chiefs.get(Iterables.getOnlyElement(query.getDataSource()
                                                                                             .getNames()));

    return partitionChiefs == null ? new NoopQueryRunner<T>() : factory.getToolchest().mergeResults(
        factory.mergeRunners(
            MoreExecutors.sameThreadExecutor(),
            // Chaining query runners which wait on submitted chain query runners can make executor pools deadlock
            Iterables.transform(
                partitionChiefs.values(), new Function<FireChief, QueryRunner<T>>()
                {
                  @Override
                  public QueryRunner<T> apply(FireChief fireChief)
                  {
                    return fireChief.getQueryRunner(query);
                  }
                }
            )
        )
    );
  }

  @Override
  public <T> QueryRunner<T> getQueryRunnerForSegments(final Query<T> query, final Iterable<SegmentDescriptor> specs)
  {
    final QueryRunnerFactory<T, Query<T>> factory = conglomerate.findFactory(query);
    final Map<Integer, FireChief> partitionChiefs = chiefs.get(Iterables.getOnlyElement(query.getDataSource()
                                                                                             .getNames()));

    return partitionChiefs == null
           ? new NoopQueryRunner<T>()
           : factory.getToolchest().mergeResults(
               factory.mergeRunners(
                   MoreExecutors.sameThreadExecutor(),
                   Iterables.transform(
                       specs,
                       new Function<SegmentDescriptor, QueryRunner<T>>()
                       {
                         @Override
                         public QueryRunner<T> apply(SegmentDescriptor spec)
                         {
                           final FireChief retVal = partitionChiefs.get(spec.getPartitionNumber());
                           return retVal == null
                                  ? new NoopQueryRunner<T>()
                                  : retVal.getQueryRunner(query.withQuerySegmentSpec(new SpecificSegmentSpec(spec)));
                         }
                       }
                   )
               )
           );
  }

  static class FireChief implements Runnable
  {
    private final FireDepartment fireDepartment;
    private final FireDepartmentMetrics metrics;
    private final RealtimeTuningConfig config;
    private final QueryRunnerFactoryConglomerate conglomerate;

    private Plumber plumber;

    FireChief(FireDepartment fireDepartment, QueryRunnerFactoryConglomerate conglomerate)
    {
      this.fireDepartment = fireDepartment;
      this.conglomerate = conglomerate;
      this.config = fireDepartment.getTuningConfig();
      this.metrics = fireDepartment.getMetrics();
    }

    private Firehose initFirehose()
    {
      try {
        log.info("Calling the FireDepartment and getting a Firehose.");
        return fireDepartment.connect();
      }
      catch (IOException e) {
        throw Throwables.propagate(e);
      }
    }

    private FirehoseV2 initFirehoseV2(Object metaData)
    {
      try {
        log.info("Calling the FireDepartment and getting a FirehoseV2.");
        return fireDepartment.connect(metaData);
      }
      catch (IOException e) {
        throw Throwables.propagate(e);
      }
    }

    void initPlumber()
    {
      log.info("Someone get us a plumber!");
      plumber = fireDepartment.findPlumber();
    }

    public FireDepartmentMetrics getMetrics()
    {
      return metrics;
    }

    @Override
    public void run()
    {
      initPlumber();

      try (Closer closer = Closer.create()) {
        Object metadata = plumber.startJob();

        Firehose firehose;
        FirehoseV2 firehoseV2;
        if (fireDepartment.checkFirehoseV2()) {
          firehoseV2 = initFirehoseV2(metadata);
          log.info("firehose2 connected");
          closer.register(firehoseV2);
          runFirehoseV2(firehoseV2);
        } else {
          firehose = initFirehose();
          log.info("firehose connected");
          closer.register(firehose);
          runFirehose(firehose);
        }
        // pluber.finishJob() is called only when every processing is successfully finished.
        closer.register(() -> plumber.finishJob());
        log.info("normalExit[%s]", true);
      }
      catch (Exception e) {
        log.makeAlert(
            e,
            "[%s] aborted realtime processing[%s]",
            e.getClass().getSimpleName(),
            fireDepartment.getDataSchema().getDataSource()
        ).emit();
        throw Throwables.propagate(e);
      }
      catch (Error e) {
        log.makeAlert(e, "Error aborted realtime processing[%s]", fireDepartment.getDataSchema().getDataSource())
           .emit();
        throw e;
      }
    }

    private void runFirehoseV2(FirehoseV2 firehose)
    {
      try {
        firehose.start();
      }
      catch (Exception e) {
        log.error(e, "Failed to start firehoseV2");
        return;
      }

      log.info("FirehoseV2 started");
      final Supplier<Committer> committerSupplier = Committers.supplierFromFirehoseV2(firehose);
      boolean haveRow = true;
      while (haveRow) {
        InputRow inputRow = null;
        int numRows = 0;
        try {
          inputRow = firehose.currRow();
          if (inputRow != null) {
            numRows = plumber.add(inputRow, committerSupplier);
            if (numRows < 0) {
              metrics.incrementThrownAway();
              log.debug("Throwing away event[%s]", inputRow);
            } else {
              metrics.incrementProcessed();
            }
          } else {
            log.debug("thrown away null input row, considering unparseable");
            metrics.incrementUnparseable();
          }
        }
        catch (Exception e) {
          log.makeAlert(e, "Unknown exception, Ignoring and continuing.")
             .addData("inputRow", inputRow);
        }

        try {
          haveRow = firehose.advance();
        }
        catch (Exception e) {
          log.debug(e, "exception in firehose.advance(), considering unparseable row");
          metrics.incrementUnparseable();
        }
      }
    }

    private void runFirehose(Firehose firehose)
    {
      final Supplier<Committer> committerSupplier = Committers.supplierFromFirehose(firehose);
      while (firehose.hasMore()) {
        Plumbers.addNextRow(committerSupplier, firehose, plumber, config.isReportParseExceptions(), metrics);
      }
    }

    public <T> QueryRunner<T> getQueryRunner(Query<T> query)
    {
      QueryRunnerFactory<T, Query<T>> factory = conglomerate.findFactory(query);
      QueryToolChest<T, Query<T>> toolChest = factory.getToolchest();

      return new FinalizeResultsQueryRunner<T>(plumber.getQueryRunner(query), toolChest);
    }
  }
}
