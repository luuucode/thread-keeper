package us.craft;


public class ThreadNameDefineFactory {


    private String defineThreadName;

    public static ThreadNameDefineFactory newInstance(){
        return new ThreadNameDefineFactory();
    }

    public ThreadNameDefineFactory defineName(String threadName){
        this.defineThreadName = threadName;
        return this;
    }

    public ThreadNameDefineFactory build(){
        return this;
    }
}