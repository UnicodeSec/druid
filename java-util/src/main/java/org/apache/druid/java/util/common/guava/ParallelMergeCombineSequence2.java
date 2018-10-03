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
package org.apache.druid.java.util.common.guava;

import com.google.common.collect.Ordering;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.guava.BaseSequence.IteratorMaker;
import org.apache.druid.java.util.common.guava.nary.BinaryFn;
import org.apache.druid.java.util.common.logger.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public class ParallelMergeCombineSequence2<T> extends YieldingSequenceBase<T>
{
  private static final Logger log = new Logger(ParallelMergeCombineSequence2.class);

  private final ExecutorService exec;
  private final List<? extends Sequence<T>> baseSequences;
  private final Ordering<T> ordering;
  private final BinaryFn<T, T, T> mergeFn;
  private final int batchSize;
  private final int queueSize;

  public ParallelMergeCombineSequence2(
      ExecutorService exec,
      List<? extends Sequence<? extends T>> baseSequences,
      Ordering<T> ordering,
      BinaryFn<T, T, T> mergeFn,
      int batchSize,
      int queueSize
  )
  {
    this.exec = exec;
    this.baseSequences = (List<? extends Sequence<T>>) baseSequences;
    this.ordering = ordering;
    this.mergeFn = mergeFn;
    this.batchSize = batchSize;
    this.queueSize = queueSize;
  }

  @Override
  public <OutType> Yielder<OutType> toYielder(
      Supplier<OutType> initValueSupplier, YieldingAccumulator<OutType, T> statefulAccumulator, Supplier<YieldingAccumulator<OutType, T>> yieldingAccumulatorSupplier
  )
  {
    final List<Sequence<OutType>> finalSequences = new ArrayList<>();

    for (int i = 0; i < baseSequences.size(); i += batchSize) {
      final Sequence<? extends Sequence<T>> subSequences = Sequences.simple(
          baseSequences.subList(i, Math.min(i + batchSize, baseSequences.size()))
      );
      final CombiningSequence<T> combiningSequence = CombiningSequence.create(new MergeSequence<>(ordering, subSequences), ordering, mergeFn);

      final BlockingQueue<OutType> queue = new ArrayBlockingQueue<>(queueSize);
      final OutType sentinel = (OutType) new Object();

      Future future = exec.submit(() -> {
        combiningSequence.accumulate(
            () -> queue,
            (theQueue, v) -> {
              try {
                if (!theQueue.offer((OutType) v, 5, TimeUnit.SECONDS)) {
                  throw new RuntimeException(new TimeoutException(StringUtils.format("Can't off to the queue[%s] in 5 sec", System.identityHashCode(queue))));
                }
              }
              catch (InterruptedException e) {
                throw new RuntimeException(e);
              }
              return theQueue;
            }
        );
        try {
          if (!queue.offer(sentinel, 5, TimeUnit.SECONDS)) {
            throw new RuntimeException(new TimeoutException(StringUtils.format("Can't offer to the queue[%s] in 5 sec", System.identityHashCode(queue))));
          }
        }
        catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      });

//      queue.addAll(combiningSequence.toList().stream().map(v -> (OutType) v).collect(Collectors.toList()));

//      combiningSequence.accumulate(
//          () -> queue,
//          (theQueue, v) -> {
//            theQueue.add((OutType) v);
//            return theQueue;
//          }
//      );

//      queue.add((OutType) sentinel);

      finalSequences.add(
          new BaseSequence<>(
              new IteratorMaker<OutType, Iterator<OutType>>()
              {
                @Override
                public Iterator<OutType> make()
                {
                  return new Iterator<OutType>()
                  {
                    private OutType nextVal;

                    @Override
                    public boolean hasNext()
                    {
                      try {
                        nextVal = queue.poll(5, TimeUnit.SECONDS);
                        if (nextVal == null) {
                          throw new RuntimeException(new TimeoutException(StringUtils.format("Can't poll from the queue[%s] in 5 sec", System.identityHashCode(queue))));
                        }
                        return nextVal != sentinel;
                      }
                      catch (InterruptedException e) {
                        throw new RuntimeException(e);
                      }
                    }

                    @Override
                    public OutType next()
                    {
                      return nextVal;
                    }
                  };
                }

                @Override
                public void cleanup(Iterator<OutType> iterFromMake)
                {
                  try {
                    future.get();
                  }
                  catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e);
                  }
                }
              }
          )
      );
    }

    return CombiningSequence.create(
        new MergeSequence<>(ordering, Sequences.fromStream(finalSequences.stream().map(seq -> (Sequence<T>) seq))),
        ordering,
        mergeFn
    ).toYielder(initValueSupplier, statefulAccumulator);

//    return new MergeSequence<>(ordering, Sequences.fromStream(finalSequences.stream().map(seq -> (Sequence<T>) seq)))
//        .toYielder(initValueSupplier, statefulAccumulator);

//    return new MergeSequence<>(ordering, Sequences.simple(baseSequences)).toYielder(initValueSupplier, statefulAccumulator, yieldingAccumulatorSupplier);
  }
}
