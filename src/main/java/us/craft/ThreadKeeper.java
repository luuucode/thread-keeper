package us.craft;

import jdk.nashorn.internal.objects.NativeUint8Array;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;


public class ThreadKeeper extends AbstractExecutorService {


    private static final AtomicInteger ctl = new AtomicInteger(ctlOf(ThreadKeeper.RUNNING,0));

    private static final int COUNT_BITS = Integer.SIZE - 3;

    private static final int CAPACITY = (1 << COUNT_BITS) - 1;

    /**
     //32位运算
     00000000000000000000000000000001   =  1
     00100000000000000000000000000000   = 2^29 = 1 << 29
     00011111111111111111111111111111   = 2^29-1 = capacity 低28位

     10100000000000000000000000000000   = -2^29  RUNNING
     00000000000000000000000000000000   =  0     SHUTDOWN
     00100000000000000000000000000000   = 2^30
     00110000000000000000000000000000   = 2^30 + 2^29

     //ctl = rs | wc
     // -1 << 29 | 0
     10100000000000000000000000000000   =  -2^29 | 0  = c


     // get runState  c &  ~ capacity
     10100000000000000000000000000000   =   c
     11100000000000000000000000000000   =   ~capacity
     10100000000000000000000000000000   =   -1 * 2^29

     // get worker count  c & capacity
     10100000000000000000000000000000   =   c
     00011111111111111111111111111111   =   2^29-1 = capacity
     00000000000000000000000000000000   =   0
     */


    // runState is stored in the high-order bits
    private static final int RUNNING = -1 << COUNT_BITS;
    private static final int SHUTDOWN = 0 << COUNT_BITS;
    private static final int STOP =     1 << COUNT_BITS;
    private static final int TIDYING =  2 << COUNT_BITS;
    private static final int TERMINATED = 3 << COUNT_BITS;

    // Packing and unpacking ctl
    /**
     * 定义线程池的生命周期，这里直接使用线程池的状态定义
     * RUNNING 进行中
     * SHUTDOWN 线程池关闭，不接收新的任务，但是在缓存队列中任务将继续进行
     * STOP  线程池停止，不接收新任务，不接收缓存队列中的任务，中断正在进行中的处理线程
     * TIDYING 所有任务都终止了，有效线程数为0
     * TERMINATED 线程池终止，调用terminated()方法，进入该状态
     */
    private static  int runStateOf(int c){
        return c & ~CAPACITY;
    }


    private static int workerCountOf(int c){
        return c & CAPACITY;
    }
    /**
     * 构造当前ctl
     * rs : run state
     * wc : work count
     */
    private static int ctlOf(int rs, int wc){
        return rs | wc;
    }


    /**
     *定义核心线程数
     */
    private transient Integer corePoolSize;

    /**
     * 定义最大线程数
     */
    private transient Integer maximumPoolSize;

    /**
     * 定义任务缓存队列
     */
    private BlockingQueue<Runnable> workQueue;

    /**
     * Lock held on access to workers set and related bookkeeping.
     * While we could use a concurrent set of some sort, it turns out
     * to be generally preferable to use a lock. Among the reasons is
     * that this serializes interruptIdleWorkers, which avoids
     * unnecessary interrupt storms, especially during shutdown.
     * Otherwise exiting threads would concurrently interrupt those
     * that have not yet interrupted. It also simplifies some of the
     * associated statistics bookkeeping of largestPoolSize etc. We
     * also hold mainLock on shutdown and shutdownNow, for the sake of
     * ensuring workers set is stable while separately checking
     * permission to interrupt and actually interrupting.
     */
    private final ReentrantLock mainLock = new ReentrantLock();

    /**
     * Wait condition to support awaitTermination
     */
    private final Condition termination = mainLock.newCondition();


    /**
     * 线程等待时间
     */
    private Long keepAliveTime;

    /**
     * 允许核心线程超时
     * false 闲置(idle)状态，core thread stay alive
     * true 闲置(idle)状态 core thread 使用keepAliveTime等待任务超时
     */
    private volatile boolean allowCoreThreadTimeOut;


    /**
     *线程等待时间单位
     */
    private TimeUnit timeUnit;


    /**
     * 定义线程名称规则
     */
    private volatile ThreadFactory threadFactory;

    /**
     * 定义任务拒绝策略
     * Handler called when saturated or shutdown in execute
     */
    private volatile ThreadRejectHandler handler;


    /**
     * 默认拒绝策略
     */
    private static final ThreadRejectHandler defaultHandler =
            new AbortPolicy();


    /**
     * Counter for completed tasks.
     * Updated only on termination of worker threads.
     * Accessed only under mainLock.
     */
    private long completedTaskCount;

    /**
     * Set containing all worker threads in pool.
     * Accessed only when holding mainLock.
     */
    private final HashSet<ThreadKeeper.Worker> workers = new HashSet<ThreadKeeper.Worker>();

    /**
     * Tracks largest attained pool size. Accessed only under
     * mainLock.
     */
    private int largestPoolSize;


    private static final boolean ONLY_ONE = true;

    /**
     * Common form of interruptIdleWorkers, to avoid having to
     * remember what the boolean argument means.
     */
    private void interruptIdleWorkers() {
        interruptIdleWorkers(false);
    }


    private static final RuntimePermission shutdownPerm =
            new RuntimePermission("modifyThread");


    public ThreadKeeper(Integer coreKeepSize, Integer maximumKeepSize, BlockingQueue blockingQueue,
                        Long keepAliveTime, TimeUnit timeUnit, ThreadFactory threadFactory,
                        ThreadRejectHandler handler) {
        this.corePoolSize = coreKeepSize;
        this.maximumPoolSize = maximumKeepSize;
        this.workQueue = blockingQueue;
        this.keepAliveTime = keepAliveTime;
        this.timeUnit = timeUnit;
        this.threadFactory = threadFactory;
        this.handler = handler;
    }

    public ThreadKeeper(Integer coreKeepSize, Integer maximumKeepSize, BlockingQueue blockingQueue,
                        Long keepAliveTime, TimeUnit timeUnit, ThreadFactory threadFactory) {
        this(coreKeepSize,maximumKeepSize,blockingQueue,
                keepAliveTime,timeUnit,threadFactory,defaultHandler);
    }

    public ThreadKeeper(Integer coreKeepSize, Integer maximumKeepSize, BlockingQueue blockingQueue,
                        Long keepAliveTime, TimeUnit timeUnit) {
        this(coreKeepSize,maximumKeepSize,blockingQueue,
                keepAliveTime,timeUnit,new ThreadKeeper.DefaultThreadDefineFactory());
    }



    /**
     * execute 提交任务入口
     */
    public void execute(Runnable command) {

        if(command == null){
            throw new NullPointerException();
        }
        //判断当前thread keeper 状态
        //当前状态非RUNNING状态
        int c = ctl.get();
        if(workerCountOf(c) < corePoolSize){
            if(addWorker(command,true)){
                return;
            }
            c = ctl.get();
        }
        if(isRunning(c) && workQueue.offer(command)){
            int recheck = ctl.get();
            if(! isRunning(recheck) && remove(command)){
                reject(command);
            }else if(workerCountOf(recheck) == 0){
                addWorker(null,false);
            }
        }else if(!addWorker(command,false)){
            reject(command);
        }

    }

    public void shutdown() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            checkShutdownAccess();
            //推进线程池状态
            advanceRunState(SHUTDOWN);
            interruptIdleWorkers();
            onShutdown();
        } finally {
            mainLock.unlock();
        }
        tryTerminate();
    }

    public List<Runnable> shutdownNow() {
        List<Runnable> tasks;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.unlock();
        try {
            checkShutdownAccess();
            advanceRunState(STOP);
            interruptIdleWorkers();
            tasks = drainQueue();
        } finally {
            mainLock.unlock();
        }
        tryTerminate();
        return tasks;
    }

    public boolean isShutdown() {
        return runStateAtLeast(ctl.get(),SHUTDOWN);
    }

    public boolean isTerminating() {
        int c = ctl.get();
        return ! isRunning(c) && runStateLessThan(c, TERMINATED);
    }

    public boolean isTerminated() {
        return runStateAtLeast(ctl.get(),TERMINATED);
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return false;
    }

    protected void terminated() { }


    /**
     * If there is a security manager, makes sure caller has
     * permission to shut down threads in general (see shutdownPerm).
     * If this passes, additionally makes sure the caller is allowed
     * to interrupt each worker thread. This might not be true even if
     * first check passed, if the SecurityManager treats some threads
     * specially.
     */
    private void checkShutdownAccess() {
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkPermission(shutdownPerm);
            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                for (Worker w : workers)
                    security.checkAccess(w.thread);
            } finally {
                mainLock.unlock();
            }
        }
    }

    /**
     * Drains the task queue into a new list, normally using
     * drainTo. But if the queue is a DelayQueue or any other kind of
     * queue for which poll or drainTo may fail to remove some
     * elements, it deletes them one by one.
     */
    private List<Runnable> drainQueue() {
        BlockingQueue<Runnable> queue = this.workQueue;
        ArrayList<Runnable> taskList = new ArrayList<Runnable>();
        queue.drainTo(taskList);
        if(!queue.isEmpty()){
            for (Runnable r : queue.toArray(new Runnable[0])) {
                if(queue.remove(r)){
                    taskList.add(r);
                }
            }
        }
        return taskList;
    }

    /**
     * Transitions runState to given target, or leaves it alone if
     * already at least the given target.
     *
     * @param targetState the desired state, either SHUTDOWN or STOP
     *        (but not TIDYING or TERMINATED -- use tryTerminate for that)
     */
    private void advanceRunState(int targetState) {
        for (;;) {
            int c = ctl.get();
            //判断当前状态为目标状态 || 尝试设置当前状态
            if (runStateAtLeast(c, targetState) ||
                    ctl.compareAndSet(c, ctlOf(targetState, workerCountOf(c))))
                break;
        }
    }

    /**
     *  暂时实现直接拒绝策略
     */
    private static class AbortPolicy implements ThreadRejectHandler{

        AbortPolicy() {
        }

        public void rejectedExecution(Runnable r, ThreadKeeper e) {
            throw new RejectedExecutionException("Task " + r.toString() +
                    " rejected from " +
                    e.toString());
        }
    }

    final void reject(Runnable command) {
        handler.rejectedExecution(command, this);
    }

    void onShutdown() {
    }

    /**
     * Removes this task from the executor's internal queue if it is
     * present, thus causing it not to be run if it has not already
     * started.
     *
     * <p>This method may be useful as one part of a cancellation
     * scheme.  It may fail to remove tasks that have been converted
     * into other forms before being placed on the internal queue. For
     * example, a task entered using {@code submit} might be
     * converted into a form that maintains {@code Future} status.
     * However, in such cases, method {@link #purge} may be used to
     * remove those Futures that have been cancelled.
     *
     * @param task the task to remove
     * @return {@code true} if the task was removed
     */
    public boolean remove(Runnable task) {
        boolean removed = workQueue.remove(task);
        tryTerminate(); // In case SHUTDOWN and now empty
        return removed;
    }

    /**
     * Tries to remove from the work queue all {@link Future}
     * tasks that have been cancelled. This method can be useful as a
     * storage reclamation operation, that has no other impact on
     * functionality. Cancelled tasks are never executed, but may
     * accumulate in work queues until worker threads can actively
     * remove them. Invoking this method instead tries to remove them now.
     * However, this method may fail to remove tasks in
     * the presence of interference by other threads.
     */
    public void purge() {
        final BlockingQueue<Runnable> q = workQueue;
        try {
            Iterator<Runnable> it = q.iterator();
            while (it.hasNext()) {
                Runnable r = it.next();
                if (r instanceof Future<?> && ((Future<?>)r).isCancelled())
                    it.remove();
            }
        } catch (ConcurrentModificationException fallThrough) {
            // Take slow path if we encounter interference during traversal.
            // Make copy for traversal and call remove for cancelled entries.
            // The slow path is more likely to be O(N*N).
            for (Object r : q.toArray())
                if (r instanceof Future<?> && ((Future<?>)r).isCancelled())
                    q.remove(r);
        }

        tryTerminate(); // In case SHUTDOWN and now empty
    }



    /**
     * 定义线程管理者  管理对象 firstTask
     */
    private final class Worker
            extends AbstractQueuedSynchronizer
            implements Runnable {

        final Thread thread;

        Runnable firstTask;

        volatile long completedTasks;

        Worker(Runnable firstTask) {
            setState(-1); // inhibit interrupts until runWorker
            this.firstTask = firstTask;
            this.thread = getThreadFactory().newThread(this);
        }

        @Override
        public void run() {
            runWorker(this);
        }

        // Lock methods
        //
        // The value 0 represents the unlocked state.
        // The value 1 represents the locked state.

        @Override
        protected boolean isHeldExclusively() {
            return getState() != 0;
        }

        @Override
        protected boolean tryAcquire(int unused) {
            if (compareAndSetState(0, 1)) {
                super.setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }

        @Override
        protected boolean tryRelease(int unused) {
            setExclusiveOwnerThread(null);
            setState(0);
            return true;
        }

        public void lock()        { acquire(1); }
        public boolean tryLock()  { return tryAcquire(1); }
        public void unlock()      { release(1); }
        public boolean isLocked() { return isHeldExclusively(); }

        void interruptIfStarted() {
            Thread t;
            if (getState() >= 0 && (t = thread) != null && !t.isInterrupted()) {
                try {
                    t.interrupt();
                } catch (SecurityException ignore) {
                }
            }
        }
    }


    /**
     *worker->执行任务过程
     */
    final void runWorker(ThreadKeeper.Worker w) {

        Thread wt = Thread.currentThread();
        Runnable task = w.firstTask;
        // gc
        w.firstTask = null;
        w.unlock(); // unlock allow interrupts
        boolean completedAbruptly = true;
        try {
            //run get task or  get task from queue not null
            while (task != null || (task = getTask()) != null) {
                w.lock(); // why lock ?
                // If pool is stopping, ensure thread is interrupted;
                // if not, ensure thread is not interrupted.
                // This requires a recheck in second case to deal with
                // shutdownNow race while clearing interrupt
                boolean interrupt =(runStateAtLeast(ctl.get(), STOP) ||
                        (Thread.interrupted() && runStateAtLeast(ctl.get(), STOP)))
                        && !wt.isInterrupted();
                if (interrupt){
                    wt.interrupt();
                }
                try {
                    beforeExecute(wt, task);
                    Throwable thrown = null;
                    try {
                        task.run();
                    } catch (RuntimeException x) {
                        thrown = x; throw x;
                    } catch (Error x) {
                        thrown = x; throw x;
                    } catch (Throwable x) {
                        thrown = x; throw new Error(x);
                    } finally {
                        afterExecute(task, thrown);
                    }
                } finally {
                    task = null;
                    w.completedTasks++;
                    w.unlock();
                }
            }
            completedAbruptly = false;
        } finally {
            processWorkerExit(w, completedAbruptly);
        }
    }


    private void processWorkerExit(Worker worker,boolean completedAbruptly){
        // completedAbruptly if the worker died due to user exception
        if(completedAbruptly){
            decrementWorkerCount();
        }
        final ReentrantLock mainLock = new ReentrantLock();
        mainLock.lock();
        try {
            completedTaskCount += worker.completedTasks;
            workers.remove(worker);
        } finally {
            mainLock.unlock();
        }
        tryTerminate();

        int c = ctl.get();
        //如果当前线程 >= 停止状态
        if(runStateAtLeast(c,STOP)){
            //用户并未终止情况
            if(!completedAbruptly){
                int min = allowCoreThreadTimeOut? 0 : corePoolSize;
                //如果发现work queue不为空，并且核心线程数为 0, 保留min为1
                if(min == 0 && ! workQueue.isEmpty()){
                    min = 1;
                }
                //当前线程数 >= 最少保留线程数
                if(workerCountOf(c) >= min){
                    return;
                }
                //如果线程数为0,新增worker
                addWorker(null, false);
            }
        }
    }

    //判断至少是s状态
    private static boolean runStateAtLeast(int c, int s) {
        return c >= s;
    }

    private boolean isRunning(int c){
        return c < SHUTDOWN;
    }
    /*
     * Bit field accessors that don't require unpacking ctl.
     * These depend on the bit layout and on workerCount being never negative.
     */

    private static boolean runStateLessThan(int c, int s) {
        return c < s;
    }

    /**
     * 扩展方法
     */
    protected void beforeExecute(Thread t, Runnable r) { }



    protected void afterExecute(Runnable r, Throwable t) { }




    private Runnable getTask() {
        boolean timedOut = false; // Did the last poll() time out?
        //自旋判断
        for (;;) {
            int c = ctl.get();
            int rs = runStateOf(c); // get cur state

            // Check if queue empty only if necessary.
            // rs >= shutdown && workQueue.isEmpty() || rs >= STOP
            // -> stop状态就不会考虑队列，只考虑当前工作线程是否为0
            if (rs >= SHUTDOWN && (rs >= STOP || workQueue.isEmpty())) {
                decrementWorkerCount();
                return null;
            }
            //RUNNING状态下，获取当前线程数
            int wc = workerCountOf(c);

            // Are workers subject to culling?
            //判断当前worker count 可以递减
            boolean timed = allowCoreThreadTimeOut || wc > corePoolSize;

            //判断当前线程是否超出最大值
            if ((wc > maximumPoolSize || (timed && timedOut))
                    && (wc > 1 || workQueue.isEmpty())) {
                if (compareAndDecrementWorkerCount(c))
                    return null;
                continue;
            }

            //
            try {
                //timed
                // true 从任务队列头取出任务,等待时间为keepAliveTime
                //false 从任务队列头等待取出任务
                Runnable r = timed ?
                        workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS) :
                        workQueue.take();
                if (r != null)
                    return r;
                timedOut = true;
            } catch (InterruptedException retry) {
                timedOut = false;
            }
        }
    }


    /**
     *
     * @param firstTask the task the new thread should run first (or
     * null if none). Workers are created with an initial first task
     * (in method execute()) to bypass queuing when there are fewer
     * than corePoolSize threads (in which case we always start one),
     * or when the queue is full (in which case we must bypass queue).
     * Initially idle threads are usually created via
     * prestartCoreThread or to replace other dying workers.
     * @param core
     * @return
     */
    private boolean addWorker(Runnable firstTask, boolean core){

        //retry: 跳出两层层循环标志位
        retry:
        for (;;){
            int c = ctl.get();
            int rs = runStateOf(c);
            //todo
            if(rs >= SHUTDOWN && !(rs == SHUTDOWN && firstTask == null && ! workQueue.isEmpty())){
                return false;
            }
            for (;;){
                int wc = workerCountOf(c);
                if(wc >= CAPACITY || wc >= (core? corePoolSize : maximumPoolSize)){
                    return false;
                }
                if(compareAndIncrementWorkerCount(c)){
                    //新增线程数成功 CAS成功，跳出双层循环
                    break retry;
                }
                c = ctl.get();
                // reread ctl 用于判断如果当前状态变更，重现走一遍外部循环,判断run state状态
                if(runStateOf(c) != rs){
                    continue retry;

                }
            }
        }
        boolean workerStarted = false;
        boolean workerAdded = false;
        Worker w = null;
        try {
            w = new Worker(firstTask);
            final Thread t = w.thread;
            if(t != null){
                final ReentrantLock mainLock = new ReentrantLock();
                mainLock.lock();
                try {
                    int rs = runStateOf(ctl.get());
                    if(rs < SHUTDOWN || (rs == SHUTDOWN && firstTask == null)){
                        if(t.isAlive()){
                            // precheck that t is startable
                            throw new IllegalThreadStateException();
                        }
                        workers.add(w);
                        int s = workers.size();
                        if(s > largestPoolSize){
                            largestPoolSize = s;
                        }
                        workerAdded = true;
                    }
                } finally {
                    mainLock.unlock();
                }
                if(workerAdded){
                    t.start();
                    workerStarted = true;
                }
            }

        } finally {
            if(! workerStarted){
                addWorkerFailed(w);
            }
        }
        return workerStarted;
    }

    private void addWorkerFailed(Worker w){
        ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            if(w != null){
                workers.remove(w);
            }
            decrementWorkerCount();
            tryTerminate();
        } finally {
            mainLock.unlock();
        }
    }


    /**
     * Transitions to TERMINATED state if either (SHUTDOWN and pool
     * and queue empty) or (STOP and pool empty).  If otherwise
     * eligible to terminate but workerCount is nonzero, interrupts an
     * idle worker to ensure that shutdown signals propagate. This
     * method must be called following any action that might make
     * termination possible -- reducing worker count or removing tasks
     * from the queue during shutdown. The method is non-private to
     * allow access from ScheduledThreadPoolExecutor.
     */
    final void tryTerminate() {
        for (;;) {
            int c = ctl.get();
            if (isRunning(c) ||
                    runStateAtLeast(c, TIDYING) ||
                    (runStateOf(c) == SHUTDOWN && ! workQueue.isEmpty()))
                return;
            if (workerCountOf(c) != 0) { // Eligible to terminate
                interruptIdleWorkers(ONLY_ONE);
                return;
            }

            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                //尝试更新当前ctl状态 线程池状态TIDYING  workerCount 0
                if (ctl.compareAndSet(c, ctlOf(TIDYING, 0))) {
                    try {
                        terminated();
                    } finally {
                        ctl.set(ctlOf(TERMINATED, 0));
                        //todo 了解一下
                        termination.signalAll();
                    }
                    return;
                }
            } finally {
                mainLock.unlock();
            }
            // else retry on failed CAS
        }
    }

     private void interruptIdleWorkers(boolean onlyOne) {
         ReentrantLock mainLock = this.mainLock;
         mainLock.unlock();
         try {
             for (Worker w : workers) {
                 Thread t = w.thread;
                 if(!t.isInterrupted() && w.tryLock()){
                     try {
                         t.interrupt();
                     } catch (SecurityException ignore) {
                     } finally {
                         w.unlock();
                     }
                 }
                 if(onlyOne){
                     break;
                 }
             }
         } finally {
            mainLock.unlock();
         }
     }



    private boolean compareAndIncrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect + 1);
    }

    private boolean compareAndDecrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect - 1);
    }

    private void decrementWorkerCount() {
        //自旋处理 decrease worker count
        do {} while (! compareAndDecrementWorkerCount(ctl.get()));
    }


    /**
     *默认线程定义工程
     */
    static class DefaultThreadDefineFactory implements ThreadFactory{

        private static final AtomicInteger poolNumber = new AtomicInteger(1);

        private static final AtomicInteger threadNumber = new AtomicInteger(1);


        private final String namePrefix;

        public DefaultThreadDefineFactory() {

            //前缀规则
            namePrefix ="pool-" +
                        poolNumber.getAndIncrement() +
                        "-thread-";
        }

        @Override
        public Thread newThread(Runnable r) {

            // Thread
            // ThreadGroup g, Runnable runnable, String name,long stackSize
            Thread t = new Thread(r,
                    namePrefix + threadNumber.getAndIncrement());
            //daemon 守护进程,设置线程为非守护线程
            if(t.isDaemon()){
                t.setDaemon(false);
            }
            //设置线程
            if(t.getPriority() != Thread.NORM_PRIORITY){
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;
        }
    }


    public ThreadFactory getThreadFactory() {
        return threadFactory;
    }

    public void setThreadFactory(ThreadFactory threadFactory) {
        this.threadFactory = threadFactory;
    }


    @Override
    public String toString() {
        long ncompleted;
        int nworkers,nactive;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            ncompleted = this.completedTaskCount;
            nactive = 0;
            nworkers = workers.size();
            for (Worker w : workers) {
                  ncompleted += w.completedTasks;
                  if(w.isLocked()){
                      ++nactive;
                  }
            }
        } finally {
            mainLock.unlock();
        }
        int c = ctl.get();
        String rs = (runStateLessThan(c, SHUTDOWN)? "Running" :
                (runStateAtLeast(c,TERMINATED)? "Terminated": "Shutting down"));
        return super.toString() +
                "[" + rs +
                ", pool size = " + nworkers +
                ", active threads = " + nactive +
                ", queued tasks = " + workQueue.size() +
                ", completed tasks = " + ncompleted +
                "]";
    }
}
