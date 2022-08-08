package com.atguigu.case2;

import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class DistributedLock {

    private final String connectString = "192.168.31.100:2181,192.168.31.101:2181,192.168.31.102:2181";
    private final int sessionTimeout = 200000;
    private final ZooKeeper zk;

    /**
     * CountDownLatch主要有两个方法：countDown()和await()。countDown()方法用于使计数器减一，
     * 其一般是执行任务的线程调用，await()方法则使调用该方法的线程处于等待状态，其一般是主线程调用。
     * 这里需要注意的是，countDown()方法并没有规定一个线程只能调用一次，当同一个线程调用多次countDown()方法时，
     * 每次都会使计数器减一；另外，await()方法也并没有规定只能有一个线程执行该方法，如果多个线程同时执行await()方法，
     * 那么这几个线程都将处于等待状态，并且以共享模式享有同一个锁。如下是其使用示例：
    * */
    private CountDownLatch connectLatch = new CountDownLatch(1);
    private CountDownLatch waitLatch = new CountDownLatch(1);

    private String waitPath;
    private String currentMode;

    public DistributedLock() throws IOException, InterruptedException, KeeperException {

        // 获取连接
        zk = new ZooKeeper(connectString, sessionTimeout, new Watcher() {
            @Override
            public void process(WatchedEvent watchedEvent) {
                // connectLatch  如果连接上zk  可以释放
                if (watchedEvent.getState() == Event.KeeperState.SyncConnected){
                    connectLatch.countDown();
                }

                // waitLatch  需要释放
                if (watchedEvent.getType()== Event.EventType.NodeDeleted && watchedEvent.getPath().equals(waitPath)){
                    waitLatch.countDown();
                }
            }
        });

        // 等待zk正常连接后，往下走程序
        connectLatch.await();

        // 判断根节点/locks是否存在
        Stat stat = zk.exists("/locks", false);

        if (stat == null) {
            // 创建一下根节点
            zk.create("/locks", "locks".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
    }

    // 对zk加锁
    public void zklock() {
        // 创建对应的临时带序号节点
        try {
            currentMode = zk.create("/locks/" + "seq-", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);

            // wait一小会, 让结果更清晰一些
            Thread.sleep(10);

            // 判断创建的节点是否是最小的序号节点，如果是获取到锁；如果不是，监听他序号前一个节点

            List<String> children = zk.getChildren("/locks", false);

            // 如果children 只有一个值，那就直接获取锁； 如果有多个节点，需要判断，谁最小
            if (children.size() == 1) {
                return;
            } else {
                Collections.sort(children);

                // 获取节点名称 seq-00000000
                String thisNode = currentMode.substring("/locks/".length());
                // 通过seq-00000000获取该节点在children集合的位置
                int index = children.indexOf(thisNode);

                // 判断
                if (index == -1) {
                    System.out.println("数据异常");
                } else if (index == 0) {
                    // 就一个节点，可以获取锁了
                    return;
                } else {
                    // 需要监听  他前一个节点变化
                    waitPath = "/locks/" + children.get(index - 1);
                    zk.getData(waitPath,true,new Stat());

                    // 等待监听
                    waitLatch.await();
                    return;
                }
            }
        } catch (KeeperException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // 解锁
    public void unZkLock() {

        // 删除节点
        try {
            zk.delete(this.currentMode,-1);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (KeeperException e) {
            e.printStackTrace();
        }

    }

}
