import us.craft.ThreadKeeper;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Description: ThreadKeeperTest
 * PackageName: PACKAGE_NAME
 *
 * @author: chenglulu
 * @version: 1.0
 * Filename:    ThreadKeeperTest.java
 * Create at:  2020/5/6
 * Copyright:   Copyright (c)2020
 * Company:     songxiaocai
 * Modification History:
 * Date              Author      Version     Description
 * ------------------------------------------------------------------
 * 2020/5/6     chenglulu    1.0         1.0 Version
 */
public class ThreadKeeperTest {

    public static final ThreadKeeper keeper = new ThreadKeeper(1,10,
            new LinkedBlockingQueue(),1000L, TimeUnit.MILLISECONDS);

    public static void main(String[] args) {

        Runnable runnable = () -> {
            System.out.println("run test!");
        };
        keeper.execute(runnable);
    }
}
