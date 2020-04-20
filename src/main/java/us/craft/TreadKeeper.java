package us.craft;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class TreadKeeper extends AbstractExecutorService  {


    /**
     *定义核心线程数
     */
    private transient Integer coreKeepSize;

    /**
     * 定义最大线程数
     */
    private transient Integer maximumKeepSize;

    /**
     * 定义任务缓存队列
     */
    private BlockingQueue blockingQueue;


    /**
     * 定义线程名称规则
     */


    /**
     * 定义任务拒绝策略
     */







    /**
     * 定义线程池的生命周期，这里直接使用线程池的状态定义
     * RUNNING 进行中
     * SHUTDOWN 线程池关闭，不接收新的任务，但是在缓存队列中任务将继续进行
     * STOP  线程池停止，不接收新任务，不接收缓存队列中的任务，中断正在进行中的处理线程
     * TIDYING 所有任务都终止了，有效线程数为0
     * TERMINATED 线程池终止，调用terminated()方法，进入该状态
     */
    private  transient  Integer runState;

    private static final int RUNNING = 1;
    private static final int SHUTDOWN = 2;
    private static final int STOP = 4;
    private static final int TIDYING = 8;
    private static final int TERMINATED = 16;




    public void execute(Runnable command) {
        //execute 提交任务入口
        if(command == null){
            //为空直接返回
            return;
        }
        //判断当前thread keeper 状态



    }

    public void shutdown() {
        //调用shutdown 如何处理,暂时不考虑多线程情况
        if(this.runState == RUNNING){
            this.runState = RUNNING << 1;
        }else{
            throw new IllegalStateException("Can not shutdown thread keeper with error run state: "
                    + this.runState);
        }
    }

    public List<Runnable> shutdownNow() {
        return null;
    }

    public boolean isShutdown() {
        return (this.runState & SHUTDOWN ) == SHUTDOWN;
    }

    public boolean isTerminated() {
        return (this.runState & TERMINATED ) == TERMINATED;
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return false;
    }



}
