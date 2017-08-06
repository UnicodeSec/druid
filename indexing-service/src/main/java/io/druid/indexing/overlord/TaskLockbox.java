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

package io.druid.indexing.overlord;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.metamx.emitter.EmittingLogger;
import io.druid.common.utils.JodaUtils;
import io.druid.indexing.common.TaskLock;
import io.druid.indexing.common.TaskLockType;
import io.druid.indexing.common.task.Task;
import io.druid.java.util.common.ISE;
import io.druid.java.util.common.Pair;
import io.druid.java.util.common.guava.Comparators;
import io.druid.server.initialization.ServerConfig;
import org.joda.time.DateTime;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Remembers which activeTasks have locked which intervals. Tasks are permitted to lock an interval if no other task
 * outside their group has locked an overlapping interval for the same datasource. When a task locks an interval,
 * it is assigned a version string that it can use to publish segments.
 */
public class TaskLockbox
{
  // Datasource -> Interval -> list of (Tasks + TaskLock)
  // Multiple shared locks can be acquired for the same dataSource and interval.
  // Note that revoked locks are also maintained in this map to notify that those locks are revoked to the callers when
  // they acquire the same locks again or request lock upgrade/downgrade.
  private final Map<String, NavigableMap<Interval, List<TaskLockPosse>>> running = Maps.newHashMap();
  private final TaskStorage taskStorage;
  private final ReentrantLock giant = new ReentrantLock(true);
  private final Condition lockReleaseCondition = giant.newCondition();
  protected final long lockTimeoutMillis;

  private static final EmittingLogger log = new EmittingLogger(TaskLockbox.class);

  // Stores List of Active Tasks. TaskLockbox will only grant locks to active activeTasks.
  // this set should be accessed under the giant lock.
  private final Set<String> activeTasks = Sets.newHashSet();

  @Inject
  public TaskLockbox(
      TaskStorage taskStorage,
      ServerConfig serverConfig
  )
  {
    this.taskStorage = taskStorage;
    this.lockTimeoutMillis = serverConfig.getMaxIdleTime().getMillis();
  }

  public TaskLockbox(
      TaskStorage taskStorage,
      long lockTimeoutMillis
  )
  {
    this.taskStorage = taskStorage;
    this.lockTimeoutMillis = lockTimeoutMillis;
  }

  /**
   * Wipe out our current in-memory state and resync it from our bundled {@link TaskStorage}.
   */
  public void syncFromStorage()
  {
    giant.lock();

    try {
      // Load stuff from taskStorage first. If this fails, we don't want to lose all our locks.
      final Set<String> storedActiveTasks = Sets.newHashSet();
      final List<Pair<Task, TaskLock>> storedLocks = Lists.newArrayList();
      for (final Task task : taskStorage.getActiveTasks()) {
        storedActiveTasks.add(task.getId());
        for (final TaskLock taskLock : taskStorage.getLocks(task.getId())) {
          storedLocks.add(Pair.of(task, taskLock));
        }
      }
      // Sort locks by version, so we add them back in the order they were acquired.
      final Ordering<Pair<Task, TaskLock>> byVersionOrdering = new Ordering<Pair<Task, TaskLock>>()
      {
        @Override
        public int compare(Pair<Task, TaskLock> left, Pair<Task, TaskLock> right)
        {
          // The second compare shouldn't be necessary, but, whatever.
          return ComparisonChain.start()
                                .compare(left.rhs.getVersion(), right.rhs.getVersion())
                                .compare(left.lhs.getId(), right.lhs.getId())
                                .result();
        }
      };
      running.clear();
      activeTasks.clear();
      activeTasks.addAll(storedActiveTasks);
      // Bookkeeping for a log message at the end
      int taskLockCount = 0;
      for (final Pair<Task, TaskLock> taskAndLock : byVersionOrdering.sortedCopy(storedLocks)) {
        final Task task = taskAndLock.lhs;
        final TaskLock savedTaskLock = taskAndLock.rhs;
        if (savedTaskLock.getInterval().toDurationMillis() <= 0) {
          // "Impossible", but you never know what crazy stuff can be restored from storage.
          log.warn("WTF?! Got lock with empty interval for task: %s", task.getId());
          continue;
        }

        final TaskLockPosse taskLockPosse = createOrFindLockPosse(
            task,
            savedTaskLock.getInterval(),
            savedTaskLock.getVersion(),
            savedTaskLock.getType()
        );
        if (taskLockPosse != null) {
          taskLockPosse.addTask(task);

          final TaskLock taskLock = taskLockPosse.getTaskLock();

          if (savedTaskLock.getVersion().equals(taskLock.getVersion())) {
            taskLockCount++;
            log.info(
                "Reacquired lock on interval[%s] version[%s] for task: %s",
                savedTaskLock.getInterval(),
                savedTaskLock.getVersion(),
                task.getId()
            );
          } else {
            taskLockCount++;
            log.info(
                "Could not reacquire lock on interval[%s] version[%s] (got version[%s] instead) for task: %s",
                savedTaskLock.getInterval(),
                savedTaskLock.getVersion(),
                taskLock.getVersion(),
                task.getId()
            );
          }
        } else {
          throw new ISE(
              "Could not reacquire lock on interval[%s] version[%s] for task: %s",
              savedTaskLock.getInterval(),
              savedTaskLock.getVersion(),
              task.getId()
          );
        }
      }
      log.info(
          "Synced %,d locks for %,d activeTasks from storage (%,d locks ignored).",
          taskLockCount,
          activeTasks.size(),
          storedLocks.size() - taskLockCount
      );
    }
    finally {
      giant.unlock();
    }
  }

  /**
   * Acquires a lock on behalf of a task. Blocks until the lock is acquired. Throws an exception if the lock
   * cannot be acquired.
   *
   * @param task task to acquire lock for
   * @param interval interval to lock
   * @return acquired TaskLock
   *
   * @throws InterruptedException if the lock cannot be acquired
   */
  public LockResult lock(
      final TaskLockType lockType,
      final Task task,
      final Interval interval
  ) throws InterruptedException
  {
    long timeout = lockTimeoutMillis;
    giant.lockInterruptibly();
    try {
      LockResult lockResult;
      while (!(lockResult = tryLock(lockType, task, interval)).isOk()) {
        long startTime = System.currentTimeMillis();
        if (lockTimeoutMillis == 0) {
          lockReleaseCondition.await();
        } else {
          lockReleaseCondition.await(timeout, TimeUnit.MILLISECONDS);
        }
        long timeDelta = System.currentTimeMillis() - startTime;
        if (timeDelta >= timeout) {
          log.info(
              "Task [%s] can not acquire lock for interval [%s] within [%s] ms",
              task.getId(),
              interval,
              lockTimeoutMillis
          );

          return lockResult;
        } else {
          timeout -= timeDelta;
        }
      }

      return lockResult;
    }
    finally {
      giant.unlock();
    }
  }

  /**
   * Attempt to acquire a lock for a task, without removing it from the queue. Can safely be called multiple times on
   * the same task until the lock is preempted.
   *
   * @param task     task that wants a lock
   * @param interval interval to lock
   * @param lockType type of lock to be acquired
   *
   * @return lock version if lock was acquired, absent otherwise
   * @throws IllegalStateException if the task is not a valid active task
   */
  public LockResult tryLock(
      final TaskLockType lockType,
      final Task task,
      final Interval interval
  )
  {
    giant.lock();

    try {
      if (!activeTasks.contains(task.getId())) {
        throw new ISE("Unable to grant lock to inactive Task [%s]", task.getId());
      }
      Preconditions.checkArgument(interval.toDurationMillis() > 0, "interval empty");

      final TaskLockPosse posseToUse = createOrFindLockPosse(task, interval, lockType);
      if (posseToUse != null && !posseToUse.getTaskLock().isRevoked()) {
        // Add to existing TaskLockPosse, if necessary
        if (posseToUse.addTask(task)) {
          log.info("Added task[%s] to TaskLock[%s]", task.getId(), posseToUse.getTaskLock().getGroupId());

          // Update task storage facility. If it fails, revoke the lock.
          try {
            taskStorage.addLock(task.getId(), posseToUse.getTaskLock());
            return LockResult.ok(posseToUse.getTaskLock());
          }
          catch (Exception e) {
            log.makeAlert("Failed to persist lock in storage")
               .addData("task", task.getId())
               .addData("dataSource", posseToUse.getTaskLock().getDataSource())
               .addData("interval", posseToUse.getTaskLock().getInterval())
               .addData("version", posseToUse.getTaskLock().getVersion())
               .emit();
            unlock(task, interval);
            return LockResult.fail(false);
          }
        } else {
          log.info("Task[%s] already present in TaskLock[%s]", task.getId(), posseToUse.getTaskLock().getGroupId());
          return LockResult.ok(posseToUse.getTaskLock());
        }
      } else {
        final boolean lockRevoked = posseToUse != null && posseToUse.getTaskLock().isRevoked();
        return LockResult.fail(lockRevoked);
      }
    }
    finally {
      giant.unlock();
    }
  }

  private TaskLockPosse createOrFindLockPosse(
      final Task task,
      final Interval interval,
      final TaskLockType lockType
  )
  {
    return createOrFindLockPosse(task, interval, null, lockType);
  }

  /**
   * Create a new {@link TaskLockPosse} or find an existing one for the given task and interval.
   *
   * @param task             task acquiring a lock
   * @param interval         interval to be locked
   * @param preferredVersion a preferred version string
   * @param lockType         type of lock to be acquired
   *
   * @return a lock posse
   *
   * @see #createNewTaskLockPosse(TaskLockType, String, String, Interval, String, int)
   */
  private TaskLockPosse createOrFindLockPosse(
      final Task task,
      final Interval interval,
      @Nullable final String preferredVersion,
      final TaskLockType lockType
  )
  {
    giant.lock();

    try {
      final String dataSource = task.getDataSource();
      final int priority = task.getPriority();
      final List<TaskLockPosse> foundPosses = findLockPossesOverlapsInterval(dataSource, interval);

      // If we have some locks for dataSource and interval, check they can be reused.
      // If they can't be reused, check lock priority and revoke existing locks if possible.
      if (foundPosses.size() > 0) {

        if (lockType.equals(TaskLockType.SHARED) && isAllSharedLocks(foundPosses)) {
          final List<TaskLockPosse> possesForReusableLocks = foundPosses
              .stream()
              .filter(lockPosse -> matchGroupIdAndContainInterval(lockPosse.getTaskLock(), task, interval))
              .collect(Collectors.toList());

          if (possesForReusableLocks.size() == 0) {
            // Any number of shared locks can be acquired for the same dataSource and interval.
            return createNewTaskLockPosse(
                lockType,
                task.getGroupId(),
                dataSource,
                interval,
                preferredVersion,
                priority
            );
          } else if (possesForReusableLocks.size() == 1) {
            return possesForReusableLocks.get(0);
          } else {
            throw new ISE(
                "Task group[%s] has multiple locks for the same interval[%s]?",
                task.getGroupId(),
                interval
            );
          }
        } else {
          if (foundPosses.size() == 1 &&
              matchGroupIdAndContainInterval(foundPosses.get(0).taskLock, task, interval)) {
            final TaskLockPosse foundPosse = foundPosses.get(0);
            if (lockType.equals(foundPosse.getTaskLock().getType())) {
              return foundPosse;
            } else {
              throw new ISE(
                  "Task[%s] already acquired a lock for interval[%s] but different type[%s]",
                  task.getId(),
                  interval,
                  foundPosse.getTaskLock().getType()
              );
            }
          } else if (isAllRevocable(foundPosses, priority)) {
            // Revoke all existing locks
            foundPosses.forEach(this::revokeLock);

            return createNewTaskLockPosse(
                lockType,
                task.getGroupId(),
                dataSource,
                interval,
                preferredVersion,
                priority
            );
          } else {
            log.info("Cannot create a new taskLockPosse because some locks of higher priorities exist");
            return null;
          }
        }
      } else {
        // We don't have any locks for dataSource and interval.
        // Let's make a new one.
        return createNewTaskLockPosse(
            lockType,
            task.getGroupId(),
            dataSource,
            interval,
            preferredVersion,
            priority
        );
      }
    }
    finally {
      giant.unlock();
    }
  }

  /**
   * Create a new {@link TaskLockPosse} for a new {@link TaskLock}. This method will attempt to assign version strings
   * that obey the invariant that every version string is lexicographically greater than any other version string
   * previously assigned to the same interval. This invariant is only mostly guaranteed, however; we assume clock
   * monotonicity and that callers specifying {@code preferredVersion} are doing the right thing.
   *
   * @param lockType         lock type
   * @param groupId          group id of task
   * @param dataSource       data source of task
   * @param interval         interval to be locked
   * @param preferredVersion preferred version string
   * @param priority         lock priority
   *
   * @return a new {@link TaskLockPosse}
   */
  private TaskLockPosse createNewTaskLockPosse(
      TaskLockType lockType,
      String groupId,
      String dataSource,
      Interval interval,
      @Nullable String preferredVersion,
      int priority
  )
  {
    giant.lock();
    try {
      // Create new TaskLock and assign it a version.
      // Assumption: We'll choose a version that is greater than any previously-chosen version for our interval. (This
      // may not always be true, unfortunately. See below.)

      final String version;

      if (preferredVersion != null) {
        // We have a preferred version. We'll trust our caller to not break our ordering assumptions and just use it.
        version = preferredVersion;
      } else {
        // We are running under an interval lock right now, so just using the current time works as long as we can trust
        // our clock to be monotonic and have enough resolution since the last time we created a TaskLock for the same
        // interval. This may not always be true; to assure it we would need to use some method of timekeeping other
        // than the wall clock.
        version = new DateTime().toString();
      }

      final TaskLockPosse posseToUse = new TaskLockPosse(
          new TaskLock(lockType, groupId, dataSource, interval, version, priority)
      );
      running.computeIfAbsent(dataSource, k -> new TreeMap<>(Comparators.intervalsByStartThenEnd()))
             .computeIfAbsent(interval, k -> new ArrayList<>())
             .add(posseToUse);

      return posseToUse;
    }
    finally {
      giant.unlock();
    }
  }

  /**
   * Upgrade a lock for the given task and interval if possible. Shared locks and revoked locks cannot be upgraded.
   *
   * @return a succeeded {@link LockResult} if the lock is upgraded. If an upgrade for a revoked lock is requested, a
   * failed {@link LockResult} is returned.
   *
   * @throws IllegalStateException if an upgrade for a shared lock is requested
   */
  public LockResult upgrade(Task task, Interval interval)
  {
    giant.lock();

    try {
      final TaskLockPosse taskLockPosseToUpdate = getOnlyTaskLockPosse(
          task,
          interval,
          findLockPossesContainingInterval(task.getDataSource(), interval)
      );

      final TaskLock lock = taskLockPosseToUpdate.getTaskLock();

      Preconditions.checkState(
          lock.getType().equals(TaskLockType.EXCLUSIVE),
          "Shared lock cannot be upgraded"
      );

      if (lock.isRevoked()) {
        return LockResult.fail(true);
      } else {
        log.info("Upgrading taskLock[%s] for task[%s] and interval[%s]", lock, task.getId(), interval);

        if (lock.isUpgraded()) {
          log.warn("TaskLock[%s] is already upgraded", lock);
          return LockResult.ok(lock);
        } else {
          final TaskLock upgradedLock = lock.upgrade();
          taskStorage.replaceLock(task.getId(), lock, upgradedLock);

          // Possible exceptions are already checked above
          final List<TaskLockPosse> possesHolder = running.get(task.getDataSource()).get(lock.getInterval());

          possesHolder.remove(taskLockPosseToUpdate);
          possesHolder.add(taskLockPosseToUpdate.withTaskLock(upgradedLock));
          log.info("Upgraded taskLock[%s]");
          return LockResult.ok(upgradedLock);
        }
      }
    }
    finally {
      giant.unlock();
    }
  }

  /**
   * Downgrade a lock for the given task and interval if possible. Shared locks cannot be downgraded.
   *
   * @return a downgraded lock if succeeded.
   *
   * @throws IllegalStateException if a downgrade for a shared lock is requested
   */
  public TaskLock downgrade(Task task, Interval interval)
  {
    giant.lock();

    try {
      final TaskLockPosse taskLockPosseToUpdate = getOnlyTaskLockPosse(
          task,
          interval,
          findLockPossesContainingInterval(task.getDataSource(), interval)
      );
      final TaskLock lock = taskLockPosseToUpdate.taskLock;

      Preconditions.checkState(
          lock.getType().equals(TaskLockType.EXCLUSIVE),
          "Shared lock cannot be downgraded"
      );

      // Upgraded locks cannot be revoked, so ignore the check to verify the lock is revoked.
      log.info("Downgrading taskLock[%s] for task[%s] and interval[%s]", lock, task.getId(), interval);

      if (!lock.isUpgraded()) {
        log.warn("TaskLock[%s] is already downgraded", lock);
        return lock;
      } else {
        final TaskLock downgradedLock = lock.downgrade();
        taskStorage.replaceLock(task.getId(), lock, downgradedLock);

        // Possible exceptions are already checked above
        final List<TaskLockPosse> possesHolder = running.get(task.getDataSource()).get(lock.getInterval());

        possesHolder.remove(taskLockPosseToUpdate);
        possesHolder.add(taskLockPosseToUpdate.withTaskLock(downgradedLock));
        log.info("Downgraded taskLock[%s]");
        return downgradedLock;
      }
    }
    finally {
      giant.unlock();
    }
  }

  private void revokeLock(TaskLockPosse lockPosse)
  {
    giant.lock();

    try {
      lockPosse.forEachTask(taskId -> revokeLock(taskId, lockPosse.getTaskLock()));
    }
    finally {
      giant.unlock();
    }
  }

  /**
   * Mark the lock as revoked. Note that revoked locks are NOT removed. Instead, they are maintained in {@link #running}
   * and {@link #taskStorage} as the normal locks do. This is to check locks are revoked when they are requested to be
   * acquired or upgraded and notify to the callers if revoked. Revoked locks are removed by calling
   * {@link #unlock(Task, Interval)}.
   *
   * @param taskId an id of the task holding the lock
   * @param lock   lock to be revoked
   */
  private void revokeLock(String taskId, TaskLock lock)
  {
    giant.lock();

    try {
      if (!activeTasks.contains(taskId)) {
        throw new ISE("Cannot revoke lock for inactive task[%s]", taskId);
      }

      final Task task = taskStorage.getTask(taskId).orNull();
      if (task == null) {
        throw new ISE("Cannot revoke lock for unknown task[%s]", taskId);
      }

      log.info("Revoking task lock[%s] for task[%s]", lock, taskId);

      if (lock.isRevoked()) {
        log.warn("TaskLock[%s] is already revoked", lock);
      } else {
        final TaskLock revokedLock = lock.revoke();
        taskStorage.replaceLock(taskId, lock, revokedLock);

        final List<TaskLockPosse> possesHolder = running.get(task.getDataSource()).get(lock.getInterval());
        final TaskLockPosse foundPosse = possesHolder.stream()
                                                     .filter(posse -> posse.getTaskLock().equals(lock))
                                                     .findFirst()
                                                     .orElseThrow(
                                                         () -> new ISE("Failed to find lock posse for lock[%s]", lock)
                                                     );
        possesHolder.remove(foundPosse);
        possesHolder.add(foundPosse.withTaskLock(revokedLock));
        log.info("Revoked taskLock[%s]", lock);
      }
    }
    finally {
      giant.unlock();
    }
  }

  /**
   * Return the currently-active locks for some task.
   *
   * @param task task for which to locate locks
   * @return currently-active locks for the given task
   */
  public List<TaskLock> findLocksForTask(final Task task)
  {
    giant.lock();

    try {
      return Lists.transform(
          findLockPossesForTask(task), new Function<TaskLockPosse, TaskLock>()
          {
            @Override
            public TaskLock apply(TaskLockPosse taskLockPosse)
            {
              return taskLockPosse.getTaskLock();
            }
          }
      );
    }
    finally {
      giant.unlock();
    }
  }

  /**
   * Release lock held for a task on a particular interval. Does nothing if the task does not currently
   * hold the mentioned lock.
   *
   * @param task task to unlock
   * @param interval interval to unlock
   */
  public void unlock(final Task task, final Interval interval)
  {
    giant.lock();

    try {
      final String dataSource = task.getDataSource();
      final NavigableMap<Interval, List<TaskLockPosse>> dsRunning = running.get(task.getDataSource());

      if (dsRunning == null || dsRunning.isEmpty()) {
        return;
      }

      final List<TaskLockPosse > possesHolder = dsRunning.get(interval);
      if(possesHolder == null|| possesHolder.isEmpty()) {
        return;
      }

      final List<TaskLockPosse> posses = possesHolder.stream()
                                                     .filter(posse -> posse.containsTask(task))
                                                     .collect(Collectors.toList());

      for (TaskLockPosse taskLockPosse : posses) {
          final TaskLock taskLock = taskLockPosse.getTaskLock();

        // Remove task from live list
        log.info("Removing task[%s] from TaskLock[%s]", task.getId(), taskLock.getGroupId());
        final boolean removed = taskLockPosse.removeTask(task);

        if (taskLockPosse.isTasksEmpty()) {
          log.info("TaskLock is now empty: %s", taskLock);
          possesHolder.remove(taskLockPosse);
        }

        if (possesHolder.size() == 0) {
          dsRunning.remove(interval);
        }

        if (running.get(dataSource).size() == 0) {
          running.remove(dataSource);
        }

        // Wake up blocking-lock waiters
        lockReleaseCondition.signalAll();

        // Remove lock from storage. If it cannot be removed, just ignore the failure.
        try {
          taskStorage.removeLock(task.getId(), taskLock);
        }
          catch (Exception e) {
          log.makeAlert(e, "Failed to clean up lock from storage")
             .addData("task", task.getId())
             .addData("dataSource", taskLock.getDataSource())
             .addData("interval", taskLock.getInterval())
             .addData("version", taskLock.getVersion())
             .emit();
        }

        if (!removed) {
          log.makeAlert("Lock release without acquire")
             .addData("task", task.getId())
             .addData("interval", interval)
             .emit();
        }
      }
    }
    finally {
      giant.unlock();
    }
  }

  /**
   * Release all locks for a task and remove task from set of active tasks. Does nothing if the task is not currently locked or not an active task.
   *
   * @param task task to unlock
   */
  public void remove(final Task task)
  {
    giant.lock();
    try {
      try {
        log.info("Removing task[%s] from activeTasks", task.getId());
        for (final TaskLockPosse taskLockPosse : findLockPossesForTask(task)) {
          unlock(task, taskLockPosse.getTaskLock().getInterval());
        }
      }
      finally {
        activeTasks.remove(task.getId());
      }
    }
    finally {
      giant.unlock();
    }
  }

  /**
   * Return the currently-active lock posses for some task.
   *
   * @param task task for which to locate locks
   */
  private List<TaskLockPosse> findLockPossesForTask(final Task task)
  {
    giant.lock();

    try {
      // Scan through all locks for this datasource
      final NavigableMap<Interval, List<TaskLockPosse>> dsRunning = running.get(task.getDataSource());
      if (dsRunning == null) {
        return ImmutableList.of();
      } else {
        return dsRunning.values().stream()
                        .flatMap(Collection::stream)
                        .filter(taskLockPosse -> taskLockPosse.containsTask(task))
                        .collect(Collectors.toList());
      }
    }
    finally {
      giant.unlock();
    }
  }

  private List<TaskLockPosse> findLockPossesContainingInterval(final String dataSource, final Interval interval)
  {
    giant.lock();

    try {
      final List<TaskLockPosse> intervalOverlapsPosses = findLockPossesOverlapsInterval(dataSource, interval);
      return intervalOverlapsPosses.stream()
                                   .filter(taskLockPosse -> taskLockPosse.taskLock.getInterval().contains(interval))
                                   .collect(Collectors.toList());
    }
    finally {
      giant.unlock();
    }
  }

  /**
   * Return all locks that overlap some search interval.
   */
  private List<TaskLockPosse> findLockPossesOverlapsInterval(final String dataSource, final Interval interval)
  {
    giant.lock();

    try {
      final NavigableMap<Interval, List<TaskLockPosse>> dsRunning = running.get(dataSource);
      if (dsRunning == null) {
        // No locks at all
        return Collections.emptyList();
      } else {
        // Tasks are indexed by locked interval, which are sorted by interval start. Intervals are non-overlapping, so:
        final NavigableSet<Interval> dsLockbox = dsRunning.navigableKeySet();
        final Iterable<Interval> searchIntervals = Iterables.concat(
            // Single interval that starts at or before ours
            Collections.singletonList(dsLockbox.floor(new Interval(interval.getStart(), new DateTime(JodaUtils.MAX_INSTANT)))),

            // All intervals that start somewhere between our start instant (exclusive) and end instant (exclusive)
            dsLockbox.subSet(
                new Interval(interval.getStart(), new DateTime(JodaUtils.MAX_INSTANT)),
                false,
                new Interval(interval.getEnd(), interval.getEnd()),
                false
            )
        );

        return StreamSupport.stream(searchIntervals.spliterator(), false)
                            .filter(searchInterval -> searchInterval != null && searchInterval.overlaps(interval))
                            .flatMap(searchInterval -> dsRunning.get(searchInterval).stream())
                            .collect(Collectors.toList());
      }
    }
    finally {
      giant.unlock();
    }
  }

  public void add(Task task)
  {
    giant.lock();
    try {
      log.info("Adding task[%s] to activeTasks", task.getId());
      activeTasks.add(task.getId());
    }
    finally {
      giant.unlock();
    }
  }

  private static boolean matchGroupIdAndContainInterval(TaskLock existingLock, Task task, Interval interval)
  {
    return existingLock.getInterval().contains(interval) &&
           existingLock.getGroupId().equals(task.getGroupId());
  }

  private static boolean isAllSharedLocks(List<TaskLockPosse> lockPosses)
  {
    return lockPosses.stream()
                     .allMatch(taskLockPosse -> taskLockPosse.getTaskLock().getType().equals(TaskLockType.SHARED));
  }

  private static boolean isAllRevocable(List<TaskLockPosse> lockPosses, int tryLockPriority)
  {
    return lockPosses.stream().allMatch(taskLockPosse -> isRevocable(taskLockPosse, tryLockPriority));
  }

  private static boolean isRevocable(TaskLockPosse lockPosse, int tryLockPriority)
  {
    final TaskLock existingLock = lockPosse.getTaskLock();
    return existingLock.getPriority() < tryLockPriority && !existingLock.isUpgraded();
  }

  private static TaskLockPosse getOnlyTaskLockPosse(Task task, Interval interval, List<TaskLockPosse> lockPosses)
  {
    final List<TaskLockPosse> filteredPosses = lockPosses.stream()
                                                         .filter(lockPosse -> lockPosse.containsTask(task))
                                                         .collect(Collectors.toList());
    if (filteredPosses.isEmpty()) {
      throw new ISE("Cannot find locks for task[%s] and interval[%s]", task.getId(), interval);
    } else if (filteredPosses.size() > 1) {
      throw new ISE("There are multiple lockPosses for task[%s] and interval[%s]?", task.getId(), interval);
    } else {
      final TaskLockPosse foundPosse = filteredPosses.get(0);
      if (foundPosse.containsTask(task)) {
        return foundPosse;
      } else {
        throw new ISE("Cannot find locks for task[%s] and interval[%s]", task.getId(), interval);
      }
    }
  }

  /**
   * Find a {@link TaskLockPosse} for a task and an exact interval.
   */
  private List<TaskLockPosse> findLockPosse(Task task, Interval interval)
  {
    final NavigableMap<Interval, List<TaskLockPosse>> dsRunning = running.get(task.getDataSource());

    if (dsRunning == null || dsRunning.isEmpty()) {
      throw new ISE("Cannot find lock for dataSource[%s]", task.getDataSource());
    }

    final List<TaskLockPosse> possesHolder = dsRunning.get(interval);
    if (possesHolder == null || possesHolder.isEmpty()) {
      throw new ISE("Not found lock for task[%s] and interval[%s]", task.getId(), interval);
    }

    return possesHolder.stream()
                       .filter(posse -> posse.containsTask(task))
                       .collect(Collectors.toList());
  }

  @VisibleForTesting
  Set<String> getActiveTasks()
  {
    return activeTasks;
  }

  @VisibleForTesting
  public Map<String, NavigableMap<Interval, List<TaskLockPosse>>> getAllLocks()
  {
    return running;
  }

  static class TaskLockPosse
  {
    final private TaskLock taskLock;
    final private Set<String> taskIds;

    TaskLockPosse(TaskLock taskLock)
    {
      this.taskLock = taskLock;
      this.taskIds = new HashSet<>();
    }

    private TaskLockPosse(TaskLock taskLock, Set<String> taskIds)
    {
      this.taskLock = taskLock;
      this.taskIds = new HashSet<>(taskIds);
    }

    TaskLockPosse withTaskLock(TaskLock taskLock)
    {
      return new TaskLockPosse(taskLock, taskIds);
    }

    TaskLock getTaskLock()
    {
      return taskLock;
    }

    boolean addTask(Task task)
    {
      Preconditions.checkArgument(taskLock.getGroupId().equals(task.getGroupId()));
      Preconditions.checkArgument(taskLock.getPriority() == task.getPriority());
      return taskIds.add(task.getId());
    }

    boolean containsTask(Task task)
    {
      Preconditions.checkNotNull(task, "task");
      return taskIds.contains(task.getId());
    }

    boolean removeTask(Task task)
    {
      Preconditions.checkNotNull(task, "task");
      return taskIds.remove(task.getId());
    }

    boolean isTasksEmpty()
    {
      return taskIds.isEmpty();
    }

    void forEachTask(Consumer<String> action)
    {
      Preconditions.checkNotNull(action);
      taskIds.forEach(action);
    }

    @Override
    public boolean equals(Object o)
    {
      if (this == o) {
        return true;
      }

      if (!getClass().equals(o.getClass())) {
        return false;
      }

      final TaskLockPosse that = (TaskLockPosse) o;
      if (!taskLock.equals(that.taskLock)) {
        return false;
      }

      return taskIds.equals(that.taskIds);
    }

    @Override
    public int hashCode()
    {
      return Objects.hashCode(taskLock, taskIds);
    }

    @Override
    public String toString()
    {
      return Objects.toStringHelper(this)
                    .add("taskLock", taskLock)
                    .add("taskIds", taskIds)
                    .toString();
    }
  }
}
