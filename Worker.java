package com.xxx.blm.game.core.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * 游戏中的任务执行者,一个Worker实列一个线程,同时提供线程安全的普通任务schedule、cron任务schedule
 *
 * @author Alex
 * @date 2017年10月31日 下午8:33:27
 */
public class Worker extends AbstractExecutorService implements Runnable, ScheduledExecutorService {

	private static final Logger LOG = LoggerFactory.getLogger(Worker.class);Concurrent
	
	private static final AtomicLong sequencer = new AtomicLong();
	
    /**
     *   NEW: 不接受新的任务，不处理队列的任务,工作线程未启动
     *   RUNNING:  接受新的任务，处理队列的任务
     *   SHUTDOWN: 不接受新的任务，但是处理队列的任务
     *   STOP:     不接受新的任务，不处理队列的任务，中断正在处理的任务
     *   TERMINATED:  所有任务结束, 线程运行结束
     *
     * NEW -> RUNNING
     *    start被调用
     * RUNNING -> SHUTDOWN
     *    shutdown或者finalize被调用
     * (RUNNING or SHUTDOWN) -> STOP
     *    shutdownNow被调用
     * SHUTDOWN -> TERMINATED
     *    队列中的任务处理结束, 线程运行结束
     * STOP -> TERMINATED
     *    线程运行结束
     */
	private static final int NEW = 0;
	private static final int RUNNING = 1;
	private static final int SHUTDOWN = 2;
	private static final int STOP = 3;
	private static final int TERMINATED = 4;
	
	// 普通任务队列
	private final Queue<Runnable> workQueue = new LinkedList<>();
	// 调度任务优先级队列
	private final PriorityQueue<ScheduledFutureTask<?>> scheduledWorkQueue = new PriorityQueue<>(1024);
	// 工作线程
	private final Thread thread;
	// 工作线程状态
	private volatile int state = NEW;
	// 保证workQueue和scheduledWorkQueue等的线程安全
	private final ReentrantLock lock = new ReentrantLock();
	private final Condition available = lock.newCondition();
	// 用于支持awaitTermination
    private final Condition termination = lock.newCondition();
	// 当任务被拒绝执行时的处理策略
	private final RejectedExecutionHandler rejectedExecutionHandler;

	public Worker(String name, RejectedExecutionHandler rejectedExecutionHandler) {
		if (rejectedExecutionHandler == null || name == null)
			throw new NullPointerException();
		this.rejectedExecutionHandler = rejectedExecutionHandler;
		this.thread = new Thread(this, name);
	}


	public Worker(String name) {
		this(name, (r, w) -> r.run());
	}
	
	/**
	 * 创建Worker
	 * @param name
	 * @return
	 */
	public static Worker createWorker(String name) {
		return new Worker(name);
	}
	
	@Override
	public void run() {
		Thread t = Thread.currentThread();
		Runnable task = null;
		try {
			while ((task = getTask()) != null) {
				// 如果worker被stop, 保证线程是中断状态, 因为用户提交的任务可能会响应中断
				if (state >= STOP && !t.isInterrupted()) {
					t.interrupt();
				}
				
				try {
		            task.run();
		        } catch (Throwable e) {
		            LOG.error("A task raised an exception", e);
		        }
			}
		} finally {// 线程运行彻底结束
			final ReentrantLock lock = this.lock;
			lock.lock();
			try {
				this.state = TERMINATED;
				termination.signalAll();
			} finally {
				lock.unlock();
			}
		}
	}

	/**
	 * 获取执行的任务,忽略由于非Worker导致takeTask的InterruptedException
	 * 
	 * @return
	 */
	private Runnable getTask() {
		for (;;) {
			try {
				return takeTask();
			} catch (InterruptedException retry) {
				// Ignore
			}
		}
	}

	/**
	 * 从工作队列和调度队列中阻塞获取获取任务，因为worker是单线程获取任务，无需在finall代码块中available.signal();
	 * 
	 * @return
	 * @throws InterruptedException
	 */
	private Runnable takeTask() throws InterruptedException {
		final ReentrantLock lock = this.lock;
		lock.lockInterruptibly();
		try {
			for (;;) {
				if (state >= SHUTDOWN && (state >= STOP || workQueue.isEmpty())) {// 不用判断scheduledWorkQueue为空
	                return null;
	            }
				
				ScheduledFutureTask<?> scheduledTask = scheduledWorkQueue.peek();
				Runnable task = workQueue.peek();

				if (scheduledTask == null) {
					if (task != null) {
						return workQueue.poll();
					} else {
						available.await();
					}
				} else {
					long delay = scheduledTask.getDelay(NANOSECONDS);
					if (delay > 0) {
						if (task != null) {
							return workQueue.poll();
						} else {
							available.awaitNanos(delay);
						}
					} else {
						return scheduledWorkQueue.poll();
					}
				}
			}
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void shutdown() {
		final ReentrantLock lock = this.lock;
		lock.lock();
		try {
			this.state = SHUTDOWN;
			cancellScheduledTasks();
			this.available.signal();
		} finally {
			lock.unlock();
		}
	}

	@Override
	public List<Runnable> shutdownNow() {
		List<Runnable> tasks;
		final ReentrantLock lock = this.lock;
		lock.lock();
		try {
			this.state = STOP;
			this.thread.interrupt();
			cancellScheduledTasks();
			tasks = drainQueue();
			this.available.signal();
		} finally {
			lock.unlock();
		}
		
		return tasks;
	}
	
	/**
	 * 取消调度任务
	 */
	private void cancellScheduledTasks() {
		Iterator<ScheduledFutureTask<?>> it = this.scheduledWorkQueue.iterator();
		while (it.hasNext()) {
			ScheduledFutureTask<?> t = it.next();
			t.cancel(false);
			it.remove();
		}
	}
	
    /**
     * 将未被执行的队列中的任务放到List中，并清空队列
     * 
     * @return
     */
    private List<Runnable> drainQueue() {
        Queue<Runnable> q = this.workQueue;
        ArrayList<Runnable> taskList = new ArrayList<>(q);
        q.clear();
        return taskList;
    }

	@Override
	public boolean isShutdown() {
		return state > RUNNING;
	}

	@Override
	public boolean isTerminated() {
		return state == TERMINATED;
	}
	
	public boolean isRunning() {
		return state == RUNNING;
	}

	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
		if (unit == null) {
            throw new NullPointerException("unit");
        }

        if (Thread.currentThread() == thread) {
            throw new IllegalStateException("cannot await termination of the current thread");
        }

        long nanos = unit.toNanos(timeout);
        lock.lock();
        try {
            for (;;) {
                if (isTerminated())
                    return true;
                if (nanos <= 0)
                    return false;
                nanos = termination.awaitNanos(nanos);
            }
        } finally {
            lock.unlock();
        }
	}

	@Override
	public void execute(Runnable command) {
		if (command == null)
			throw new NullPointerException();
		lock.lock();
		try {
			if (state == NEW) {
				start();
			}
			
			if (isRunning() && workQueue.offer(command)) {
				available.signal();
			} else {
				reject(command);
			}
		} finally {
			lock.unlock();
		}
	}
	
	/**
	 * 启动worker
	 */
	private void start() {
		this.state = RUNNING;
		this.thread.start();
	}

	/**
	 * 任务被拒绝执行
	 * 
	 * @param command
	 */
	final void reject(Runnable command) {
		rejectedExecutionHandler.rejectedExecution(command, this);
	}

	/**
	 * 如果Worker被回收则关闭
	 */
	protected void finalize() {
		shutdown();
	}
	
	/**
	 * 获取待执行的任务数量
	 * 
	 * @return
	 */
	public int getTaskCount() {
		final ReentrantLock lock = this.lock;
		lock.lock();
		try {
			return workQueue.size();
		} finally {
			lock.unlock();
		}
	}
	
	/**
	 * 获取待执行的Schedule任务数量
	 * 
	 * @return
	 */
	public int getScheduleTaskCount() {
		final ReentrantLock lock = this.lock;
		lock.lock();
		try {
			return scheduledWorkQueue.size();
		} finally {
			lock.unlock();
		}
	}
	
	/**
	 * 采用cron表达式schedule任务
	 * 
	 * @param command
	 * @param cron
	 * @return
	 */
	public ScheduledFuture<?> schedule(Runnable command, String cron) {
		if (command == null || cron == null)
			throw new NullPointerException();
		CronExpression cronExp = new CronExpression(cron);
		Date now = new Date();
		// 下一次运行时间
		Date nextTime = cronExp.getTimeAfter(now);
		if (nextTime == null) {
			throw new RuntimeException("cron express is not able, never run");
		}
		
		long triggerTime = triggerTime(nextTime.getTime() - now.getTime(), MILLISECONDS);
		CronScheduledFutureTask<Void> t = new CronScheduledFutureTask<Void>(command, null, triggerTime, cronExp, nextTime);
		delayedExecute(t);
		return t;
	}
	
	@Override
	public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
		if (command == null || unit == null)
			throw new NullPointerException();
		ScheduledFutureTask<Void> t = new ScheduledFutureTask<Void>(command, null, triggerTime(delay, unit));
		delayedExecute(t);
		return t;
	}
    
	@Override
	public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
		if (callable == null || unit == null)
			throw new NullPointerException();
		ScheduledFutureTask<V> t = new ScheduledFutureTask<V>(callable, triggerTime(delay, unit));
		delayedExecute(t);
		return t;
	}

	@Override
	public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
		if (command == null || unit == null)
			throw new NullPointerException();
		if (period <= 0)
			throw new IllegalArgumentException();
		ScheduledFutureTask<Void> t = new ScheduledFutureTask<Void>(command, null, triggerTime(initialDelay, unit),
				unit.toNanos(period));
		delayedExecute(t);
		return t;
	}

	@Override
	public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
		if (command == null || unit == null)
			throw new NullPointerException();
		if (delay <= 0)
			throw new IllegalArgumentException();
		ScheduledFutureTask<Void> t = new ScheduledFutureTask<Void>(command, null, triggerTime(initialDelay, unit),
				unit.toNanos(-delay));
		delayedExecute(t);
		return t;
	}
	
	/**
	 * 延时执行任务
	 * 
	 * @param task
	 */
	private void delayedExecute(ScheduledFutureTask<?> task) {
		lock.lock();
		try {
			if (state == NEW) {
				start();
			}
			
			if (isRunning() && scheduledWorkQueue.offer(task)) {
				if (scheduledWorkQueue.peek() == task) {
					available.signal();
				}
			} else {
				reject(task);
			}
		} finally {
			lock.unlock();
		}
	}
	
	/**
	 * 当前jvm nanoseconds时间
	 * 
	 * @return
	 */
	private static final long now() {
        return System.nanoTime();
    }

	/**
	 * 定时任务的触发时间
	 * 
	 * @param delay
	 * @param unit
	 * @return
	 */
	private static final long triggerTime(long delay, TimeUnit unit) {
		return triggerTime(unit.toNanos((delay < 0) ? 0 : delay));
	}

	/**
	 * 定时任务的触发时间
	 * 
	 * @param delay
	 * @return
	 */
	private static final long triggerTime(long delay) {
		if (delay >= Long.MAX_VALUE >> 1) {
			throw new RuntimeException("delay must less than Long.MAX_VALUE >> 1");
		}
		return now() + delay;
	}
    
	/**
	 * 普通调度任务
	 */
	private class ScheduledFutureTask<V> extends FutureTask<V> implements RunnableScheduledFuture<V> {
		// 任务fifo编号
		private final long sequenceNumber;
		// 任务执行时间(基于jvm的毫微秒)
		protected long time;
	    // 任务执行间隔,正数：fixed-rate，负数：fixed-delay,0:non-repeating
		private final long period;

		ScheduledFutureTask(Runnable r, V result, long ns) {
			super(r, result);
			this.time = ns;
			this.period = 0;
			this.sequenceNumber = sequencer.getAndIncrement();
		}

		ScheduledFutureTask(Runnable r, V result, long ns, long period) {
			super(r, result);
			this.time = ns;
			this.period = period;
			this.sequenceNumber = sequencer.getAndIncrement();
		}

		ScheduledFutureTask(Callable<V> callable, long ns) {
			super(callable);
			this.time = ns;
			this.period = 0;
			this.sequenceNumber = sequencer.getAndIncrement();
		}

		public long getDelay(TimeUnit unit) {
			return unit.convert(time - now(), NANOSECONDS);
		}

		public int compareTo(Delayed other) {
			if (other == this) {
				return 0;
			}

			ScheduledFutureTask<?> x = (ScheduledFutureTask<?>) other;
			long diff = time - x.time;
			if (diff < 0)
				return -1;
			else if (diff > 0)
				return 1;
			else if (sequenceNumber < x.sequenceNumber)
				return -1;
			else
				return 1;
		}

		public boolean isPeriodic() {
			return period != 0;
		}

		private void setNextRunTime() {
			long p = period;
			if (p > 0)
				time += p;
			else
				time = triggerTime(-p);
		}

		public void run() {
			boolean periodic = isPeriodic();
			if (!isRunning())
				cancel(false);
			else if (!periodic)
				ScheduledFutureTask.super.run();
			else if (ScheduledFutureTask.super.runAndReset()) {
				setNextRunTime();
				delayedExecute(this);
			}
		}
	}
	
	/**
	 * cron调度任务,cron任务为秒级,基于绝对时间,如果调整系统时间,运行会错乱。
	 */
	private class CronScheduledFutureTask<V> extends ScheduledFutureTask<V> {
		// cron表达式
		private final CronExpression cron;
		// 下次任务执行时间
		private Date nextTime;

		CronScheduledFutureTask(Runnable r, V result, long ns, CronExpression cron, Date nextTime) {
			super(r, result, ns);
			this.cron = cron;
			this.nextTime = nextTime;
		}
		
		@Override
		public void run() {
			if (!isRunning())
				cancel(false);
			else if(CronScheduledFutureTask.super.runAndReset()) {
				Date newNextTime = cron.getTimeAfter(nextTime);
				if (newNextTime != null) {
					time += MILLISECONDS.toNanos(newNextTime.getTime() - nextTime.getTime());
					nextTime = newNextTime;
					delayedExecute(this);
				}
			}
		}

		@Override
		protected void setException(Throwable t) {
			super.setException(t);
			LOG.error(t.getMessage(), t);
		}
	}
    
}
