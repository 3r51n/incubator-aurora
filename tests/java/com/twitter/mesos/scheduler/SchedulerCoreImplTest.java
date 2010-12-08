package com.twitter.mesos.scheduler;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.twitter.common.base.Closure;
import com.twitter.common.testing.EasyMockTest;
import com.twitter.mesos.Tasks;
import com.twitter.mesos.gen.CronCollisionPolicy;
import com.twitter.mesos.gen.ExecutorStatus;
import com.twitter.mesos.gen.JobConfiguration;
import com.twitter.mesos.gen.LiveTaskInfo;
import com.twitter.mesos.gen.NonVolatileSchedulerState;
import com.twitter.mesos.gen.RegisteredTaskUpdate;
import com.twitter.mesos.gen.ResourceConsumption;
import com.twitter.mesos.gen.ScheduleStatus;
import com.twitter.mesos.gen.ScheduledTask;
import com.twitter.mesos.gen.TaskQuery;
import com.twitter.mesos.gen.TwitterTaskInfo;
import com.twitter.mesos.scheduler.JobManager.JobUpdateResult;
import com.twitter.mesos.scheduler.TaskStore.TaskState;
import com.twitter.mesos.scheduler.configuration.ConfigurationManager;
import com.twitter.mesos.scheduler.configuration.ConfigurationManager.TaskDescriptionException;
import com.twitter.mesos.scheduler.persistence.NoPersistence;
import org.easymock.Capture;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import static com.twitter.mesos.gen.ScheduleStatus.*;
import static org.easymock.EasyMock.*;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

/**
 * Unit test for the SchedulerCore.
 *
 * TODO(wfarner): Revamp this class to make use of mocks.
 *
 * TODO(wfarner): Test all the different cases for setTaskStaus:
 *    - Killed tasks get removed.
 *    - Failed tasks have failed count incremented.
 *    - Tasks above maxTaskFailures have _all_ tasks in the job removed.
 *    - Daemon tasks are rescheduled.
 *
 * @author wfarner
 */
public class SchedulerCoreImplTest extends EasyMockTest {

  private WorkQueue workQueue;
  private SchedulingFilter schedulingFilter;
  private Driver driver;
  private SchedulerCore scheduler;
  private CronJobManager cron;
  private JobUpdateLauncher updateLauncher;

  private static final String OWNER_A = "Test_Owner_A";
  private static final String JOB_A = "Test_Job_A";
  private static final String JOB_A_KEY = Tasks.jobKey(OWNER_A, JOB_A);
  private static final TwitterTaskInfo DEFAULT_TASK = defaultTask();

  private static final String OWNER_B = "Test_Owner_B";
  private static final String JOB_B = "Test_Job_B";

  private static final String SLAVE_ID = "SlaveId";
  private static final String SLAVE_HOST_1 = "SlaveHost1";
  private static final String SLAVE_HOST_2 = "SlaveHost2";

  @Before
  public void setUp() {
    workQueue = createMock(WorkQueue.class);
    schedulingFilter = createMock(SchedulingFilter.class);
    driver = createMock(Driver.class);
    updateLauncher = createMock(JobUpdateLauncher.class);

    cron = new CronJobManager();
    ImmediateJobManager immediateJobManager = new ImmediateJobManager();
    scheduler = new SchedulerCoreImpl(cron, immediateJobManager,
        new NoPersistence<NonVolatileSchedulerState>(), new ExecutorTracker() {
          @Override public void start(Closure<String> restartCallback) {
            // No op.
          }
          @Override public void addStatus(ExecutorStatus status) {
            // No-op.
          }
        }, workQueue, schedulingFilter, updateLauncher);
    cron.schedulerCore = scheduler;
    immediateJobManager.schedulerCore = scheduler;
  }

  @Test
  public void testCreateJob() throws Exception {
    control.replay();

    int numTasks = 10;
    JobConfiguration job = makeJob(OWNER_A, JOB_A, DEFAULT_TASK, numTasks);
    scheduler.createJob(job);
    assertTaskCount(numTasks);

    Set<TaskState> tasks = scheduler.getTasks(queryJob(OWNER_A, JOB_A));
    assertThat(tasks.size(), is(numTasks));
    for (TaskState state : tasks) {
      assertThat(state.task.getStatus(), is(PENDING));
      assertThat(state.task.getAssignedTask().isSetTaskId(), is(true));
      assertThat(state.task.getAssignedTask().isSetSlaveId(), is(false));
      // Need to clear shard ID since that was assigned in our makeJob function.
      assertThat(state.task.getAssignedTask().getTask().setShardId(0),
          is(ConfigurationManager.populateFields(job, new TwitterTaskInfo(DEFAULT_TASK))));
    }
  }

  @Test
  public void testIncrementingTaskIds() throws Exception {
    control.replay();

    for (int i = 0; i < 10; i++) {
      scheduler.createJob(makeJob(OWNER_A + i, JOB_A, DEFAULT_TASK, 1));
      assertThat(Iterables.getOnlyElement(
          getTasksOwnedBy(OWNER_A + i)).task.getAssignedTask().getTaskId(), is(i + 1));
    }
  }

  @Test(expected = ScheduleException.class)
  public void testCreateDuplicateJob() throws Exception {
    control.replay();

    scheduler.createJob(makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 1));
    assertTaskCount(1);

    scheduler.createJob(makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 1));
  }

  @Test(expected = ScheduleException.class)
  public void testCreateDuplicateCronJob() throws Exception {
    control.replay();

    // Cron jobs are scheduled on a delay, so this job's tasks will not be scheduled immediately,
    // but duplicate jobs should still be rejected.
    scheduler.createJob(makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 1)
        .setCronSchedule("1 1 1 1 1"));
    assertTaskCount(0);

    scheduler.createJob(makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 1));
  }

  @Test(expected = TaskDescriptionException.class)
  public void testCreateEmptyJob() throws Exception {
    control.replay();

    scheduler.createJob(new JobConfiguration().setOwner(OWNER_A).setName(JOB_A));
  }

  @Test(expected = TaskDescriptionException.class)
  public void testCreateJobMissingShardIds() throws Exception {
    control.replay();

    scheduler.createJob(new JobConfiguration().setOwner(OWNER_A).setName(JOB_A).setTaskConfigs(
        ImmutableSet.of(new TwitterTaskInfo(DEFAULT_TASK))));
  }

  @Test(expected = TaskDescriptionException.class)
  public void testCreateJobDuplicateShardIds() throws Exception {
    control.replay();

    scheduler.createJob(new JobConfiguration().setOwner(OWNER_A).setName(JOB_A).setTaskConfigs(
        ImmutableSet.of(
            new TwitterTaskInfo(DEFAULT_TASK).setShardId(0).setStartCommand("foo"),
            new TwitterTaskInfo(DEFAULT_TASK).setShardId(0).setStartCommand("bar"))));
  }

  @Test(expected = TaskDescriptionException.class)
  public void testCreateJobShardIdHole() throws Exception {
    control.replay();

    scheduler.createJob(new JobConfiguration().setOwner(OWNER_A).setName(JOB_A).setTaskConfigs(
        ImmutableSet.of(
            new TwitterTaskInfo(DEFAULT_TASK).setShardId(0),
            new TwitterTaskInfo(DEFAULT_TASK).setShardId(2))));
  }

  @Test
  public void testHonorsScheduleFilter() throws Exception {
    expectOffer(false);
    expectOffer(false);
    expectOffer(false);

    control.replay();

    scheduler.createJob(makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 10));

    assertTaskCount(10);

    Map<String, String> slaveOffer = ImmutableMap.<String, String>builder()
        .put("cpus", "4")
        .put("mem", "4096")
        .build();
    assertNull(scheduler.offer(SLAVE_ID, SLAVE_HOST_1, slaveOffer));
    assertNull(scheduler.offer(SLAVE_ID, SLAVE_HOST_1, slaveOffer));
    assertNull(scheduler.offer(SLAVE_ID, SLAVE_HOST_1, slaveOffer));

    // No tasks should have moved out of the pending state.
    assertThat(getTasksByStatus(PENDING).size(), is(10));
  }

  @Test
  public void testRestartTask() throws Exception {
    Capture<Callable<Boolean>> workCapture = new Capture<Callable<Boolean>>();
    workQueue.doWork(capture(workCapture));

    expect(driver.killTask(1)).andReturn(0);

    control.replay();

    scheduler.registered(driver, "");
    scheduler.createJob(makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 1));
    changeStatus(queryByOwner(OWNER_A), STARTING);
    changeStatus(queryByOwner(OWNER_A), RUNNING);

    int taskId = getOnlyTask(queryByOwner(OWNER_A)).task.getAssignedTask().getTaskId();

    Set<Integer> restartRequest = Sets.newHashSet(taskId);
    int restarted = Iterables.getOnlyElement(scheduler.restartTasks(restartRequest));

    assertThat(restarted, is(taskId));

    workCapture.getValue().call();

    // Mimick the master notifying the scheduler of a task state change.
    changeStatus(query(restartRequest), KILLED);

    TaskState restartedTask = getOnlyTask(Query.byStatus(KILLED_BY_CLIENT));
    assertThat(restartedTask.task.getAssignedTask().getTaskId(), is(restarted));

    TaskState newTask = getOnlyTask(Query.byStatus(PENDING));
    assertThat(newTask.task.getAncestorId(), is(restarted));
    assertThat(newTask.task.getAssignedTask().getTask().getShardId(),
        is(restartedTask.task.getAssignedTask().getTask().getShardId()));
  }

  @Test
  public void testRestartUnknownTask() throws Exception {
    control.replay();

    scheduler.createJob(makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 1));
    changeStatus(queryByOwner(OWNER_A), STARTING);
    changeStatus(queryByOwner(OWNER_A), RUNNING);

    int taskId = getOnlyTask(queryByOwner(OWNER_A)).task.getAssignedTask().getTaskId();

    Set<Integer> restartRequest = Sets.newHashSet(taskId + 1);
    Set<Integer> restarted = scheduler.restartTasks(restartRequest);

    assertThat(restarted.isEmpty(), is(true));
  }

  @Test
  public void testRestartInactiveTask() throws Exception {
    control.replay();

    scheduler.createJob(makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 1));
    changeStatus(queryByOwner(OWNER_A), STARTING);
    changeStatus(queryByOwner(OWNER_A), RUNNING);
    changeStatus(queryByOwner(OWNER_A), FINISHED);

    int taskId = Iterables.getOnlyElement(Iterables.transform(
        scheduler.getTasks(queryByOwner(OWNER_A)), Tasks.STATE_TO_ID));

    Set<Integer> restartRequest = Sets.newHashSet(taskId);
    Set<Integer> restarted = scheduler.restartTasks(restartRequest);

    assertThat(restarted.isEmpty(), is(true));
  }

  @Test
  public void testRestartMixedTasks() throws Exception {
    Capture<Callable<Boolean>> workCapture = new Capture<Callable<Boolean>>();
    workQueue.doWork(capture(workCapture));

    expect(driver.killTask(1)).andReturn(0);

    control.replay();

    scheduler.registered(driver, "");
    scheduler.createJob(makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 1));
    scheduler.createJob(makeJob(OWNER_B, JOB_B, DEFAULT_TASK, 1));
    changeStatus(queryByOwner(OWNER_A), STARTING);
    changeStatus(queryByOwner(OWNER_A), RUNNING);

    changeStatus(queryByOwner(OWNER_B), STARTING);
    changeStatus(queryByOwner(OWNER_B), RUNNING);
    changeStatus(queryByOwner(OWNER_B), FINISHED);

    int activeTaskId = getOnlyTask(queryByOwner(OWNER_A)).task.getAssignedTask().getTaskId();
    int inactiveTaskId = getOnlyTask(queryByOwner(OWNER_B)).task.getAssignedTask().getTaskId();

    Set<Integer> restartRequest = Sets.newHashSet(activeTaskId, inactiveTaskId, 100000);
    Set<Integer> restarted = scheduler.restartTasks(restartRequest);

    Set<Integer> expectedRestart = Sets.newHashSet(activeTaskId);

    assertThat(restarted, is(expectedRestart));

    workCapture.getValue().call();

    // Mimick the master notifying the scheduler of a task state change.
    changeStatus(query(expectedRestart), KILLED);

    assertThat(scheduler.getTasks(new Query(new TaskQuery().setTaskIds(expectedRestart)
        .setStatuses(Sets.newHashSet(KILLED_BY_CLIENT)))).size(),
        is(1));

    assertThat(scheduler.getTasks(new Query(new TaskQuery().setOwner(OWNER_A)
        .setStatuses(Sets.newHashSet(PENDING)))).size(),
        is(1));
  }

  @Test
  public void testDaemonTasksRescheduled() throws Exception {
    control.replay();

    // Schedule 5 daemon and 5 non-daemon tasks.
    scheduler.createJob(makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 5));
    TwitterTaskInfo task = new TwitterTaskInfo(DEFAULT_TASK);
    task.putToConfiguration("daemon", "true");
    scheduler.createJob(makeJob(OWNER_A, JOB_A + "daemon", task, 5));

    assertThat(getTasksByStatus(PENDING).size(), is(10));

    changeStatus(queryByOwner(OWNER_A), STARTING);
    assertThat(getTasksByStatus(STARTING).size(), is(10));

    changeStatus(queryByOwner(OWNER_A), RUNNING);
    assertThat(getTasksByStatus(RUNNING).size(), is(10));

    // Daemon tasks will move back into PENDING state after finishing.
    changeStatus(queryByOwner(OWNER_A), FINISHED);
    Set<TaskState> newTasks = getTasksByStatus(PENDING);
    assertThat(newTasks.size(), is(5));
    for (TaskState state : newTasks) {
      assertThat(state.task.getAssignedTask().getTask().getShardId(),
          is(getTask(state.task.getAncestorId()).task.getAssignedTask().getTask().getShardId()));
    }

    assertThat(getTasksByStatus(FINISHED).size(), is(10));
  }

  @Test
  public void testNoTransitionFromTerminalState() throws Exception {
    Capture<Callable<Boolean>> workCapture = new Capture<Callable<Boolean>>();
    workQueue.doWork(capture(workCapture));

    expect(driver.killTask(1)).andReturn(0);

    control.replay();

    scheduler.registered(driver, "");
    scheduler.createJob(makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 1));
    changeStatus(queryByOwner(OWNER_A), STARTING);
    changeStatus(queryByOwner(OWNER_A), RUNNING);
    scheduler.killTasks(queryByOwner(OWNER_A));
    workCapture.getValue().call();

    int taskId = getOnlyTask(queryByOwner(OWNER_A)).task.getAssignedTask().getTaskId();

    // This transition should be rejected.
    changeStatus(queryByOwner(OWNER_A), LOST);

    assertThat(getTask(taskId).task.getStatus(), is(KILLED_BY_CLIENT));
  }

  @Test
  public void testFailedTaskIncrementsFailureCount() throws Exception {
    control.replay();

    int maxFailures = 5;
    TwitterTaskInfo task = new TwitterTaskInfo(DEFAULT_TASK);
    task.putToConfiguration("max_task_failures", String.valueOf(maxFailures));
    JobConfiguration job = makeJob(OWNER_A, JOB_A, task, 1);
    scheduler.createJob(job);
    assertTaskCount(1);

    Set<TaskState> tasks = scheduler.getTasks(queryJob(OWNER_A, JOB_A));
    assertThat(tasks.size(), is(1));

    for (int i = 0; i < maxFailures - 1; i++) {
      changeStatus(i + 1, RUNNING);
      assertThat(getTask(i + 1).task.getFailureCount(), is(i));
      changeStatus(i + 1, FAILED);

      assertTaskCount(i + 2);

      TaskState rescheduled = getOnlyTask(Query.byStatus(PENDING));
      assertThat(rescheduled.task.getFailureCount(), is(i + 1));
    }

    changeStatus(Query.byStatus(PENDING), FAILED);
    assertThat(getTasksByStatus(FAILED).size(), is(maxFailures));
    assertThat(getTasksByStatus(PENDING).size(), is(0));
  }

  @Test
  public void testCronJobLifeCycle() throws Exception {
    control.replay();

    JobConfiguration job = makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 10);
    job.setCronSchedule("1 1 1 1 1");
    scheduler.createJob(job);
    assertTaskCount(0);
    assertThat(cron.hasJob(JOB_A_KEY), is(true));

    // Simulate a triggering of the cron job.
    cron.cronTriggered(JOB_A_KEY);
    assertTaskCount(10);
    assertThat(getTasks(new Query(new TaskQuery()
        .setOwner(OWNER_A).setJobName(JOB_A).setStatuses(Sets.newHashSet(PENDING)))).size(),
        is(10));

    assertTaskCount(10);

    changeStatus(queryByOwner(OWNER_A), STARTING);
    assertTaskCount(10);
    changeStatus(queryByOwner(OWNER_A), RUNNING);
    assertTaskCount(10);
    changeStatus(queryByOwner(OWNER_A), FINISHED);
  }

  @Test
  public void testCronNoSuicide() throws Exception {
    control.replay();

    JobConfiguration job = makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 10);
    job.setCronSchedule("1 1 1 1 1")
        .setCronCollisionPolicy(CronCollisionPolicy.KILL_EXISTING);
    scheduler.createJob(job);
    assertTaskCount(0);

    try {
      scheduler.createJob(job);
      fail();
    } catch (ScheduleException e) {
      // Expected.
    }
    assertThat(cron.hasJob(JOB_A_KEY), is(true));

    // Simulate a triggering of the cron job.
    cron.cronTriggered(JOB_A_KEY);
    assertTaskCount(10);

    // Simulate a triggering of the cron job.
    cron.cronTriggered(JOB_A_KEY);
    assertTaskCount(10);

    try {
      scheduler.createJob(job);
      fail();
    } catch (ScheduleException e) {
      // Expected.
    }
    assertThat(cron.hasJob(JOB_A_KEY), is(true));
  }

  @Test
  public void testKillPendingTask() throws Exception {
    control.replay();

    JobConfiguration job = makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 1);
    scheduler.createJob(job);
    assertTaskCount(1);

    Set<TaskState> tasks = scheduler.getTasks(queryJob(OWNER_A, JOB_A));
    assertThat(tasks.size(), is(1));

    int taskId = Iterables.get(tasks, 0).task.getAssignedTask().getTaskId();

    scheduler.killTasks(Query.byId(taskId));
    assertTaskCount(0);
  }

  @Test
  public void testKillRunningTask() throws Exception {
    Capture<Callable<Boolean>> workCapture = new Capture<Callable<Boolean>>();
    workQueue.doWork(capture(workCapture));
    expect(driver.killTask(1)).andReturn(0);

    control.replay();

    scheduler.registered(driver, "");
    scheduler.createJob(makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 1));
    int taskId = getOnlyTask(queryByOwner(OWNER_A)).task.getAssignedTask().getTaskId();
    changeStatus(query(taskId), STARTING);
    changeStatus(query(taskId), RUNNING);
    scheduler.killTasks(query(taskId));
    workCapture.getValue().call();
    assertThat(getTask(taskId).task.getStatus(), is(KILLED_BY_CLIENT));
    assertThat(getTasks(queryByOwner(OWNER_A)).size(), is(1));
  }

  @Test
  public void testKillCronTask() throws Exception {
    control.replay();

    JobConfiguration job = makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 1);
    job.setCronSchedule("1 1 1 1 1");
    scheduler.createJob(job);

    // This will fail if the cron task could not be found.
    scheduler.killTasks(queryJob(OWNER_A, JOB_A));
  }

  @Test
  public void testLostTaskRescheduled() throws Exception {
    control.replay();

    int maxFailures = 5;
    TwitterTaskInfo task = new TwitterTaskInfo(DEFAULT_TASK);
    task.putToConfiguration("max_task_failures", String.valueOf(maxFailures));
    JobConfiguration job = makeJob(OWNER_A, JOB_A, task, 1);
    scheduler.createJob(job);
    assertTaskCount(1);

    Set<TaskState> tasks = scheduler.getTasks(queryJob(OWNER_A, JOB_A));
    assertThat(tasks.size(), is(1));

    Query pendingQuery = Query.byStatus(PENDING);
    changeStatus(pendingQuery, LOST);
    assertThat(getOnlyTask(pendingQuery).task.getStatus(), is(PENDING));
    assertTaskCount(2);
    assertThat(scheduler.getTasks(pendingQuery).size(), is(1));

    changeStatus(pendingQuery, LOST);
    assertThat(getOnlyTask(pendingQuery).task.getStatus(), is(PENDING));
    assertTaskCount(3);
    assertThat(scheduler.getTasks(pendingQuery).size(), is(1));
  }

  @Test
  public void testKillJob() throws Exception {
    control.replay();

    scheduler.createJob(makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 10));
    assertTaskCount(10);

    scheduler.killTasks(queryJob(OWNER_A, JOB_A));
    assertTaskCount(0);
  }

  @Test
  public void testKillJob2() throws Exception {
    control.replay();

    scheduler.createJob(makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 10));
    assertTaskCount(10);

    scheduler.createJob(makeJob(OWNER_A, JOB_A + "2", DEFAULT_TASK, 10));
    assertTaskCount(20);

    scheduler.killTasks(queryJob(OWNER_A, JOB_A + "2"));
    assertTaskCount(10);

    for (TaskState state : scheduler.getTasks(Query.GET_ALL)) {
      assertThat(state.task.getAssignedTask().getTask().getJobName(), is(JOB_A));
    }
  }

  @Test
  public void testResourceUpdate() throws Exception {
    expectOffer(true);

    control.replay();

    scheduler.createJob(makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 1));
    Map<String, String> slaveOffer = ImmutableMap.<String, String>builder()
        .put("cpus", "4")
        .put("mem", "4096")
        .build();
    scheduler.offer(SLAVE_ID, SLAVE_HOST_1, slaveOffer);

    int taskId = getOnlyTask(queryByOwner(OWNER_A)).task.getAssignedTask().getTaskId();

    changeStatus(taskId, RUNNING);

    assertNull(Iterables.getOnlyElement(scheduler.getTasks(query(taskId))).volatileState.resources);

    RegisteredTaskUpdate update = new RegisteredTaskUpdate()
        .setSlaveHost(SLAVE_HOST_1);
    ResourceConsumption resources = new ResourceConsumption()
        .setDiskUsedMb(100)
        .setMemUsedMb(10)
        .setCpusUsed(4)
        .setLeasedPorts(ImmutableMap.<String, Integer>of("health", 50000))
        .setNiceLevel(5);
    update.addToTaskInfos(new LiveTaskInfo()
        .setTaskId(taskId)
        .setStatus(RUNNING)
        .setResources(resources));
    scheduler.updateRegisteredTasks(update);

    assertThat(Iterables.getOnlyElement(scheduler.getTasks(query(taskId))).volatileState.resources,
        is(resources));
  }

  @Test
  public void testSlaveAdjustsSchedulerTaskState() throws Exception {
    expectOffer(true);

    control.replay();

    scheduler.createJob(makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 1));
    Map<String, String> slaveOffer = ImmutableMap.<String, String>builder()
        .put("cpus", "4")
        .put("mem", "4096")
        .build();
    scheduler.offer(SLAVE_ID, SLAVE_HOST_1, slaveOffer);

    int taskId = getOnlyTask(queryByOwner(OWNER_A)).task.getAssignedTask().getTaskId();

    changeStatus(taskId, RUNNING);

    // Simulate state update from the executor telling the scheduler that the task is dead.
    // This can happen if the entire cluster goes down - the scheduler has persisted state
    // listing the task as running, and the executor reads the task state in and marks it as KILLED.
    RegisteredTaskUpdate update = new RegisteredTaskUpdate()
        .setSlaveHost(SLAVE_HOST_1);
    update.addToTaskInfos(new LiveTaskInfo().setTaskId(taskId).setStatus(KILLED));
    scheduler.updateRegisteredTasks(update);

    // The expected outcome is that the task is rescheduled, and the old task is moved into the
    // KILLED state.
    assertTaskCount(2);
    TaskState killedTask = getOnlyTask(new Query(new TaskQuery().setOwner(OWNER_A)
        .setTaskIds(Sets.newHashSet(taskId))));
    assertThat(killedTask.task.getStatus(), is(KILLED));

    TaskState rescheduled = Iterables.getOnlyElement(getTasksByStatus(PENDING));
    assertThat(rescheduled.task.getAncestorId(), is(taskId));
  }

  @Test
  public void testSlaveCannotModifyTasksForOtherSlave() throws Exception {
    expectOffer(true);
    expectOffer(true);

    control.replay();

    Map<String, String> slaveOffer = ImmutableMap.<String, String>builder()
        .put("cpus", "4")
        .put("mem", "4096")
        .build();

    // Offer resources for the scheduler to accept.
    scheduler.createJob(makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 1));
    scheduler.offer(SLAVE_ID, SLAVE_HOST_1, slaveOffer);

    scheduler.createJob(makeJob(OWNER_B, JOB_B, DEFAULT_TASK, 1));
    scheduler.offer(SLAVE_ID, SLAVE_HOST_2, slaveOffer);

    int taskIdA = Iterables.get(getTasksOwnedBy(OWNER_A), 0).task.getAssignedTask().getTaskId();
    int taskIdB = Iterables.get(getTasksOwnedBy(OWNER_B), 0).task.getAssignedTask().getTaskId();

    changeStatus(taskIdA, RUNNING);
    changeStatus(taskIdB, RUNNING);

    assertThat(getTask(taskIdA).task.getAssignedTask().getSlaveHost(), is(SLAVE_HOST_1));

    scheduler.updateRegisteredTasks(new RegisteredTaskUpdate().setSlaveHost(SLAVE_HOST_2)
        .setTaskInfos(Arrays.asList(
          new LiveTaskInfo().setTaskId(taskIdA).setStatus(FAILED),
          new LiveTaskInfo().setTaskId(taskIdB).setStatus(RUNNING))));

    assertThat(getTasksByStatus(RUNNING).size(), is(2));
    assertTaskCount(2);
  }

  @Test
  public void testSlaveStopsReportingRunningTask() throws Exception {
    expectOffer(true);
    expectOffer(true);
    expectOffer(true);
    expectOffer(true);

    control.replay();

    Map<String, String> slaveOffer = ImmutableMap.<String, String>builder()
        .put("cpus", "4")
        .put("mem", "4096")
        .build();

    // Offer resources for the scheduler to accept.
    TwitterTaskInfo daemonTask = new TwitterTaskInfo(DEFAULT_TASK);
    daemonTask.putToConfiguration("daemon", "true");

    scheduler.createJob(makeJob(OWNER_A, JOB_A, daemonTask, 2));
    scheduler.offer(SLAVE_ID, SLAVE_HOST_1, slaveOffer);
    scheduler.offer(SLAVE_ID, SLAVE_HOST_1, slaveOffer);

    scheduler.createJob(makeJob(OWNER_B, JOB_B, DEFAULT_TASK, 2));
    scheduler.offer(SLAVE_ID, SLAVE_HOST_2, slaveOffer);
    scheduler.offer(SLAVE_ID, SLAVE_HOST_2, slaveOffer);

    int taskIdA = Iterables.get(getTasksOwnedBy(OWNER_A), 0).task.getAssignedTask().getTaskId();
    int taskIdB = Iterables.get(getTasksOwnedBy(OWNER_A), 1).task.getAssignedTask().getTaskId();
    int taskIdC = Iterables.get(getTasksOwnedBy(OWNER_B), 0).task.getAssignedTask().getTaskId();
    int taskIdD = Iterables.get(getTasksOwnedBy(OWNER_B), 1).task.getAssignedTask().getTaskId();

    changeStatus(taskIdA, RUNNING);
    changeStatus(taskIdB, FINISHED);
    assertThat(getTasks(new Query(new TaskQuery().setOwner(OWNER_A).setJobName(JOB_A)
            .setStatuses(EnumSet.of(PENDING)))).size(),
        is(1));

    changeStatus(taskIdC, RUNNING);
    changeStatus(taskIdD, FAILED);

    Function<TaskState, Integer> getAncestorId = new Function<TaskState, Integer>() {
      @Override public Integer apply(TaskState state) { return state.task.getAncestorId(); }
    };

    // Since job A is a daemon, its missing RUNNING task should be rescheduled.
    scheduler.updateRegisteredTasks(new RegisteredTaskUpdate().setSlaveHost(SLAVE_HOST_1)
        .setTaskInfos(Arrays.<LiveTaskInfo>asList()));
    Set<TaskState> rescheduledTasks = getTasks(new Query(new TaskQuery()
        .setOwner(OWNER_A).setJobName(JOB_A).setStatuses(EnumSet.of(PENDING))));
    assertThat(rescheduledTasks.size(), is(2));
    Set<Integer> rescheduledTaskAncestors = Sets.newHashSet(Iterables.transform(rescheduledTasks,
        getAncestorId));
    assertThat(rescheduledTaskAncestors, is((Set<Integer>) Sets.newHashSet(taskIdA, taskIdB)));

    // Send an update from host 2 that does not include the FAILED task.
    scheduler.updateRegisteredTasks(new RegisteredTaskUpdate().setSlaveHost(SLAVE_HOST_2)
        .setTaskInfos(Arrays.<LiveTaskInfo>asList()));
    rescheduledTasks = getTasks(new Query(new TaskQuery()
        .setOwner(OWNER_B).setJobName(JOB_B).setStatuses(EnumSet.of(PENDING))));
    assertThat(rescheduledTasks.size(), is(1));
    rescheduledTaskAncestors = Sets.newHashSet(Iterables.transform(rescheduledTasks,
        getAncestorId));
    assertThat(rescheduledTaskAncestors, is((Set<Integer>) Sets.newHashSet(taskIdC)));

    // This task is not yet removed because we have not met the grace period.
    assertThat(Iterables.isEmpty(getTasks(taskIdD)), is(false));
  }

  @Test
  public void testUpdateJob() throws Exception {
    TwitterTaskInfo task = new TwitterTaskInfo(DEFAULT_TASK);
    task.putToConfiguration("start_command", "echo 'hello'");
    JobConfiguration job = makeJob(OWNER_A, JOB_A, task, 1);

    TwitterTaskInfo updatedTask = new TwitterTaskInfo(DEFAULT_TASK);
    updatedTask.putToConfiguration("start_command", "echo 'hi'");
    JobConfiguration updatedJob = makeJob(OWNER_A, JOB_A, updatedTask, 1);

    updateLauncher.launchUpdater((JobConfiguration) anyObject());

    control.replay();

    scheduler.createJob(job);

    assertThat(scheduler.updateJob(updatedJob), is(JobUpdateResult.UPDATER_LAUNCHED));
  }

  @Test
  public void testUpdateJobUnchanged() throws Exception {
    control.replay();

    scheduler.createJob(makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 1));

    assertThat(scheduler.updateJob(makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 1)),
        is(JobUpdateResult.JOB_UNCHANGED));
  }

  @Test(expected = ScheduleException.class)
  public void testUpdateNonExistentJob() throws Exception {
    control.replay();

    scheduler.createJob(makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 1));
    scheduler.updateJob(makeJob(OWNER_B, JOB_A, DEFAULT_TASK, 1));
  }

  @Test
  public void testUpdateJobChangePriority() throws Exception {
    control.replay();

    JobConfiguration job = makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 1);
    scheduler.createJob(job);

    int taskId = getOnlyTask(queryByOwner(OWNER_A)).task.getAssignedTask().getTaskId();

    changeStatus(taskId, RUNNING);

    TwitterTaskInfo updatedTask = new TwitterTaskInfo(DEFAULT_TASK);
    updatedTask.putToConfiguration("priority", "100");
    assertThat(scheduler.updateJob(makeJob(OWNER_A, JOB_A, updatedTask, 1)),
        is(JobUpdateResult.COMPLETED));

    ScheduledTask task = getTask(taskId).task;
    assertThat(task.getStatus(), is(RUNNING));

    // Need to mimick the configuration parsing.
    TwitterTaskInfo expectedResult = new TwitterTaskInfo(updatedTask);
    ConfigurationManager.populateFields(job, expectedResult);
    assertThat(task.getAssignedTask().getTask(), is(expectedResult));
  }

  private Query byPriority(final int priority) {
    return new Query(new TaskQuery(), new Predicate<TaskState>() {
      @Override
      public boolean apply(TaskState state) {
        return state.task.getAssignedTask().getTask().getPriority() == priority;
      }
    });
  }

  @Test
  public void testUpdateJobRemoveTasks() throws Exception {
    Capture<Callable<Boolean>> workCapture1 = new Capture<Callable<Boolean>>();
    Capture<Callable<Boolean>> workCapture2 = new Capture<Callable<Boolean>>();
    workQueue.doWork(capture(workCapture1));
    workQueue.doWork(capture(workCapture2));

    expect(driver.killTask(anyInt())).andReturn(0);
    expect(driver.killTask(anyInt())).andReturn(0);

    control.replay();

    scheduler.registered(driver, "");

    TwitterTaskInfo pending1 = new TwitterTaskInfo(DEFAULT_TASK);
    pending1.putToConfiguration("priority", "1");
    TwitterTaskInfo starting1 = new TwitterTaskInfo(DEFAULT_TASK);
    starting1.putToConfiguration("priority", "2");
    TwitterTaskInfo running1 = new TwitterTaskInfo(DEFAULT_TASK);
    running1.putToConfiguration("priority", "3");
    TwitterTaskInfo finished1 = new TwitterTaskInfo(DEFAULT_TASK);
    finished1.putToConfiguration("priority", "4");
    TwitterTaskInfo killed1 = new TwitterTaskInfo(DEFAULT_TASK);
    killed1.putToConfiguration("priority", "5");
    TwitterTaskInfo pending2 = new TwitterTaskInfo(DEFAULT_TASK);
    pending2.putToConfiguration("priority", "6");
    TwitterTaskInfo starting2 = new TwitterTaskInfo(DEFAULT_TASK);
    starting2.putToConfiguration("priority", "7");
    TwitterTaskInfo running2 = new TwitterTaskInfo(DEFAULT_TASK);
    running2.putToConfiguration("priority", "8");
    TwitterTaskInfo finished2 = new TwitterTaskInfo(DEFAULT_TASK);
    finished2.putToConfiguration("priority", "9");
    TwitterTaskInfo killed2 = new TwitterTaskInfo(DEFAULT_TASK);
    killed2.putToConfiguration("priority", "10");

    JobConfiguration job = makeJob(OWNER_A, JOB_A,
        Arrays.asList(pending1, starting1, running1, finished1, killed1,
            pending2, starting2, running2, finished2, killed2));
    scheduler.createJob(job);

    int pendingId1 = Tasks.STATE_TO_ID.apply(getOnlyTask(byPriority(1)));
    int startingId1 = Tasks.STATE_TO_ID.apply(getOnlyTask(byPriority(2)));
    changeStatus(startingId1, STARTING);
    int runningId1 = Tasks.STATE_TO_ID.apply(getOnlyTask(byPriority(3)));
    changeStatus(runningId1, RUNNING);
    int finishedId1 = Tasks.STATE_TO_ID.apply(getOnlyTask(byPriority(4)));
    changeStatus(finishedId1, FINISHED);
    int killedId1 = Tasks.STATE_TO_ID.apply(getOnlyTask(byPriority(5)));
    scheduler.killTasks(Query.byId(killedId1));
    int pendingId2 = Tasks.STATE_TO_ID.apply(getOnlyTask(byPriority(6)));
    int startingId2 = Tasks.STATE_TO_ID.apply(getOnlyTask(byPriority(7)));
    changeStatus(startingId2, STARTING);
    int runningId2 = Tasks.STATE_TO_ID.apply(getOnlyTask(byPriority(8)));
    changeStatus(runningId2, RUNNING);
    int finishedId2 = Tasks.STATE_TO_ID.apply(getOnlyTask(byPriority(9)));
    changeStatus(finishedId2, FINISHED);
    int killedId2 = Tasks.STATE_TO_ID.apply(getOnlyTask(byPriority(10)));
    scheduler.killTasks(Query.byId(killedId2));

    assertThat(scheduler.updateJob(makeJob(OWNER_A, JOB_A,
        Arrays.asList(pending1, starting1, running1, finished1, killed1))),
        is(JobUpdateResult.COMPLETED));

    workCapture1.getValue().call();
    workCapture2.getValue().call();

    // Check that all the tasks ended up in the right states.
    assertThat(getTask(pendingId1).task.getStatus(), is(PENDING));
    assertThat(getTask(startingId1).task.getStatus(), is(STARTING));
    assertThat(getTask(runningId1).task.getStatus(), is(RUNNING));
    assertThat(getTask(finishedId1).task.getStatus(), is(FINISHED));
    assertThat(getTasks(Query.byId(killedId1)).isEmpty(), is(true));
    assertThat(getTasks(Query.byId(pendingId2)).isEmpty(), is(true));
    assertThat(getTask(startingId2).task.getStatus(), is(KILLED_BY_CLIENT));
    assertThat(getTask(runningId2).task.getStatus(), is(KILLED_BY_CLIENT));
    assertThat(getTask(finishedId2).task.getStatus(), is(FINISHED));
    assertThat(getTasks(Query.byId(killedId2)).isEmpty(), is(true));

    // We have 5 active tasks: pending1, starting1, running1, as well as finished1 and killed1
    // which were reincarnated by the update.
    assertThat(scheduler.getTasks(Query.activeQuery(job)).size(), is(5));
  }

  @Test
  public void tetUpdateJobAddTasks() throws Exception {
    control.replay();

    TwitterTaskInfo pending1 = new TwitterTaskInfo(DEFAULT_TASK);
    pending1.putToConfiguration("priority", "1");
    TwitterTaskInfo starting1 = new TwitterTaskInfo(DEFAULT_TASK);
    starting1.putToConfiguration("priority", "2");
    TwitterTaskInfo running1 = new TwitterTaskInfo(DEFAULT_TASK);
    running1.putToConfiguration("priority", "3");
    TwitterTaskInfo finished1 = new TwitterTaskInfo(DEFAULT_TASK);
    finished1.putToConfiguration("priority", "4");
    TwitterTaskInfo killed1 = new TwitterTaskInfo(DEFAULT_TASK);
    killed1.putToConfiguration("priority", "5");
    TwitterTaskInfo pending2 = new TwitterTaskInfo(DEFAULT_TASK);
    pending2.putToConfiguration("priority", "6");

    List<TwitterTaskInfo> tasks = Arrays.asList(pending1, starting1, running1, finished1, killed1);
    JobConfiguration job = makeJob(OWNER_A, JOB_A, tasks);
    scheduler.createJob(job);

    int pendingId1 = Tasks.STATE_TO_ID.apply(getOnlyTask(byPriority(1)));
    int startingId1 = Tasks.STATE_TO_ID.apply(getOnlyTask(byPriority(2)));
    changeStatus(startingId1, STARTING);
    int runningId1 = Tasks.STATE_TO_ID.apply(getOnlyTask(byPriority(3)));
    changeStatus(runningId1, RUNNING);
    int finishedId1 = Tasks.STATE_TO_ID.apply(getOnlyTask(byPriority(4)));
    changeStatus(finishedId1, FINISHED);
    int killedId1 = Tasks.STATE_TO_ID.apply(getOnlyTask(byPriority(5)));
    scheduler.killTasks(Query.byId(killedId1));

    List<TwitterTaskInfo> newTasks = Lists.newArrayList(pending2, pending2, pending2);
    newTasks.addAll(tasks);
    assertThat(scheduler.updateJob(makeJob(OWNER_A, JOB_A, newTasks)),
        is(JobUpdateResult.COMPLETED));

    // Check that all the tasks ended up in the right states.
    assertThat(getTask(pendingId1).task.getStatus(), is(PENDING));
    assertThat(getTask(startingId1).task.getStatus(), is(STARTING));
    assertThat(getTask(runningId1).task.getStatus(), is(RUNNING));
    assertThat(getTask(finishedId1).task.getStatus(), is(FINISHED));
    assertThat(getTasks(Query.byId(killedId1)).isEmpty(), is(true));

    // We have 5 active tasks: pending1, starting1, running1, as well as the newly-pending tasks
    assertThat(scheduler.getTasks(Query.activeQuery(job)).size(), is(8));
  }

  @Test
  public void testUpdateCronJob() throws Exception {
    control.replay();

    String oldCronSchedule = "1 1 1 1 1";
    scheduler.createJob(makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 1).setCronSchedule(oldCronSchedule));

    JobConfiguration originalJob = Iterables.getOnlyElement(cron.getJobs());

    String newCronSchedule = "* * * * 1";
    assertThat(scheduler.updateJob(
        makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 1).setCronSchedule(newCronSchedule)),
        is(JobUpdateResult.COMPLETED));

    JobConfiguration updatedJob = Iterables.getOnlyElement(cron.getJobs());
    assertThat(updatedJob.getCronSchedule(), is(newCronSchedule));
    assertThat(updatedJob.setCronSchedule(oldCronSchedule), is(originalJob));
  }

  @Test
  public void testUpdateCronJobUnchanged() throws Exception {
    control.replay();

    scheduler.createJob(makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 1).setCronSchedule("1 1 1 1 1"));

    JobConfiguration original = Iterables.getOnlyElement(cron.getJobs());

    assertThat(scheduler.updateJob(makeJob(OWNER_A, JOB_A, DEFAULT_TASK, 1)
        .setCronSchedule("1 1 1 1 1")), is(JobUpdateResult.JOB_UNCHANGED));

    JobConfiguration updatedJob = Iterables.getOnlyElement(cron.getJobs());
    assertThat(updatedJob, is(original));
  }

  private void assertTaskCount(int numTasks) {
    assertThat(scheduler.getTasks(Query.GET_ALL).size(), is(numTasks));
  }

  private static JobConfiguration makeJob(String owner, String jobName, TwitterTaskInfo task,
      int numTasks) {
    List<TwitterTaskInfo> tasks = Lists.newArrayList();
    for (int i = 0; i < numTasks; i++) tasks.add(new TwitterTaskInfo(task));

    return makeJob(owner, jobName, tasks);
  }

  private static JobConfiguration makeJob(String owner, String jobName,
      Iterable<TwitterTaskInfo> tasks) {
    JobConfiguration job = new JobConfiguration();
    job.setOwner(owner)
        .setName(jobName);
    int i = 0;
    for (TwitterTaskInfo task : tasks) {
      job.addToTaskConfigs(new TwitterTaskInfo(task).setShardId(i++));
    }

    return job;
  }

  private static TwitterTaskInfo defaultTask() {
    return new TwitterTaskInfo().setConfiguration(ImmutableMap.<String, String>builder()
        .put("start_command", "date")
        .put("cpus", "1.0")
        .put("ram_mb", "1024")
        .put("hdfs_path", "/fake/path")
        .build());
  }

  private TaskState getTask(int taskId) {
    return getOnlyTask(query(taskId));
  }

  private TaskState getOnlyTask(Query query) {
    return Iterables.getOnlyElement(scheduler.getTasks((query)));
  }

  private Set<TaskState> getTasks(Query query) {
    return scheduler.getTasks(query);
  }

  private Set<TaskState> getTasks(int... taskIds) {
    return scheduler.getTasks(query(taskIds));
  }

  private Set<TaskState> getTasksByStatus(ScheduleStatus... statuses) {
    return scheduler.getTasks(Query.byStatus(statuses));
  }

  private Set<TaskState> getTasksOwnedBy(String owner) {
    return scheduler.getTasks(query(owner, null, null));
  }

  private Query query(Iterable<Integer> taskIds) {
    return query(null, null, taskIds);
  }

  private Query query(int... taskIds) {
    List<Integer> ids = Lists.newArrayList();
    for (int taskId : taskIds) ids.add(taskId);
    return query(null, null, ids);
  }

  private Query queryByOwner(String owner) {
    return query(owner, null, null);
  }

  private Query queryJob(String owner, String jobName) {
    return query(owner, jobName, null);
  }

  private Query query(String owner, String jobName, Iterable<Integer> taskIds) {
    TaskQuery query = new TaskQuery();
    if (owner != null) query.setOwner(owner);
    if (jobName != null) query.setJobName(jobName);
    if (taskIds!= null) query.setTaskIds(Sets.newHashSet(taskIds));

    return new Query(query);
  }

  public void changeStatus(Query query, ScheduleStatus status) {
    scheduler.setTaskStatus(query, status);
  }

  public void changeStatus(int taskId, ScheduleStatus status) {
    scheduler.setTaskStatus(query(Arrays.asList(taskId)), status);
  }

  private void expectOffer(boolean passFilter) {
    expect(schedulingFilter.makeFilter((TwitterTaskInfo) anyObject(), (String) anyObject()))
        .andReturn(passFilter ? Predicates.<TaskState>alwaysTrue()
        : Predicates.<TaskState>alwaysFalse());
  }
}
