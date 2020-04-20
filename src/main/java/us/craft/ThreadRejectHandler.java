package us.craft;



public interface ThreadRejectHandler {

    void rejectedExecution(Runnable r, ThreadKeeper executor);
}
