/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Flushable;
import java.io.IOException;
import java.util.LinkedList;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * This RequestProcessor logs requests to disk. It batches the requests to do
 * the io efficiently. The request is not passed to the next RequestProcessor
 * until its log has been synced to disk.
 *
 * SyncRequestProcessor is used in 3 different cases
 * 1. Leader - Sync request to disk and forward it to AckRequestProcessor which
 *             send ack back to itself.
 * 2. Follower - Sync request to disk and forward request to
 *             SendAckRequestProcessor which send the packets to leader.
 *             SendAckRequestProcessor is flushable which allow us to force
 *             push packets to leader.
 * 3. Observer - Sync committed request to disk (received as INFORM packet).
 *             It never send ack back to the leader, so the nextProcessor will
 *             be null. This change the semantic of txnlog on the observer
 *             since it only contains committed txns.
 */
public class SyncRequestProcessor extends ZooKeeperCriticalThread implements
        RequestProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(SyncRequestProcessor.class);
    private final ZooKeeperServer zks;
    private final LinkedBlockingQueue<Request> queuedRequests =
        new LinkedBlockingQueue<Request>();
    private final RequestProcessor nextProcessor;

    private Thread snapInProcess = null;
    volatile private boolean running;

    /**
     * Transactions that have been written and are waiting to be flushed to
     * disk. Basically this is the list of SyncItems whose callbacks will be
     * invoked after flush returns successfully.
     */
    private final LinkedList<Request> toFlush = new LinkedList<Request>();
    private final Random r = new Random();
    /**
     * The number of log entries to log before starting a snapshot
     */
    private static int snapCount = ZooKeeperServer.getSnapCount();

    private final Request requestOfDeath = Request.requestOfDeath;

    public SyncRequestProcessor(ZooKeeperServer zks,
            RequestProcessor nextProcessor) {
        super("SyncThread:" + zks.getServerId(), zks
                .getZooKeeperServerListener());
        this.zks = zks;
        this.nextProcessor = nextProcessor;
        running = true;
    }

    /**
     * used by tests to check for changing
     * snapcounts
     * @param count
     */
    public static void setSnapCount(int count) {
        snapCount = count;
    }

    /**
     * used by tests to get the snapcount
     * @return the snapcount
     */
    public static int getSnapCount() {
        return snapCount;
    }

    @Override
    public void run() {
        try {
            int logCount = 0;

            // we do this in an attempt to ensure that not all of the servers
            // in the ensemble take a snapshot at the same time
            int randRoll = r.nextInt(snapCount/2);
            while (true) {
                Request si = null;
                // 这里判断toFlush是否为空，如果空说明当前zk中没有这个处理需要处理的任务
                // 那么应该调用take()方法阻塞的等待下一个request过来处理
                // 否则，如果toFlush不为空，直接从队列中获取请求处理，不应该阻塞等待
                // 如果队列中没有请求，进行flush操作，将toFlush队列中的请求处理完成
                if (toFlush.isEmpty()) {
                    si = queuedRequests.take();
                } else {
                    // 一般情况下不可能一直可以取到request来处理，但是系统非常繁忙，极端情况下如果出现了一直可以取到request的情况下
                    // 会导致toFlush队列中的数据越来越多，而没有时间去处理
                    // 因此在下面的代码中判断了：如果大于1000将会先处理toFlush队列，再从request队列中拉取请求处理
                    si = queuedRequests.poll();
                    if (si == null) {
                        flush(toFlush);
                        continue;
                    }
                }
                if (si == requestOfDeath) {
                    break;
                }
                if (si != null) {
                    /*
                    如果是事务请求，就必须把请求写入log，这个写入未必能保证真的同步到磁盘，但如果每次写请求都同步，
                    性能会有问题，所以在这个类中增加了toFlush队列来保证刷盘，考虑选择了两个时机来做这件事情：
                        如果没有请求的时候（即较空闲的时候）
                        toFlush队列到了一定数量（1000），就会批量同步
                    由于后续的处理需要保证刷盘成功了再进行，所以需要在flush之后调用后续的处理器进行处理
                    由于while (true)是一只在运行的，所以flush操作会立马执行（除非系统的并发请求太大了，这时达到1000才会阻塞请求，处理flush）
                     */

                    // track the number of records written to the log
                    // 请求先在本地写入到日志文件
                    if (zks.getZKDatabase().append(si)) {
                        logCount++;
                        if (logCount > (snapCount / 2 + randRoll)) {
                            randRoll = r.nextInt(snapCount/2);
                            // roll the log
                            // 将写入流置空，从而下次写入的时候创建新的日志文件，达到滚动日志文件的目的
                            // 因为是追加内容到文件，所以即使下一个request是同一个事务id
                            // 重新打开了上次写入的log文件也不会有影响
                            zks.getZKDatabase().rollLog();
                            // take a snapshot
                            if (snapInProcess != null && snapInProcess.isAlive()) {
                                LOG.warn("Too busy to snap, skipping");
                            } else {
                                // 开启新的线程来生成snapshot文件
                                snapInProcess = new ZooKeeperThread("Snapshot Thread") {
                                        public void run() {
                                            try {
                                                zks.takeSnapshot();
                                            } catch(Exception e) {
                                                LOG.warn("Unexpected exception", e);
                                            }
                                        }
                                    };
                                snapInProcess.start();
                            }
                            logCount = 0;
                        }
                    // 如果不是事务请求，将会返回false，进入这个else if判断
                    } else if (toFlush.isEmpty()) {
                        // optimization for read heavy workloads
                        // iff this is a read, and there are no pending
                        // flushes (writes), then just pass this to the next
                        // processor
                        if (nextProcessor != null) {
                            nextProcessor.processRequest(si);
                            // 单节点环境：下一个处理器是FinalRequestProcessor，下面这个不会执行
                            // 分布式环境下：SendAckRequestProcessor实现了该接口，需要调用flush()方法
                            if (nextProcessor instanceof Flushable) {
                                ((Flushable)nextProcessor).flush();
                            }
                        }
                        continue;
                    }
                    // 事务请求加入到flush队列中，队列将不为空，
                    // 如果有读请求过来，说明支持写操作比较多，系统比较繁忙，会直接加入队列，等待前面的请求处理完成后再处理
                    toFlush.add(si);

                    // 加入这个判断的原因，见上面的queuedRequests.poll()这个行代码的解释
                    if (toFlush.size() > 1000) {
                        flush(toFlush);
                    }
                }
            }
        } catch (Throwable t) {
            handleException(this.getName(), t);
        } finally{
            running = false;
        }
        LOG.info("SyncRequestProcessor exited!");
    }

    private void flush(LinkedList<Request> toFlush)
        throws IOException, RequestProcessorException
    {
        if (toFlush.isEmpty())
            return;

        // 提交事务日志，确保日志都持久化到磁盘
        zks.getZKDatabase().commit();
        while (!toFlush.isEmpty()) {
            Request i = toFlush.remove();
            if (nextProcessor != null) {
                nextProcessor.processRequest(i);
            }
        }
        if (nextProcessor != null && nextProcessor instanceof Flushable) {
            ((Flushable)nextProcessor).flush();
        }
    }

    public void shutdown() {
        LOG.info("Shutting down");
        queuedRequests.add(requestOfDeath);
        try {
            if(running){
                this.join();
            }
            if (!toFlush.isEmpty()) {
                flush(toFlush);
            }
        } catch(InterruptedException e) {
            LOG.warn("Interrupted while wating for " + this + " to finish");
        } catch (IOException e) {
            LOG.warn("Got IO exception during shutdown");
        } catch (RequestProcessorException e) {
            LOG.warn("Got request processor exception during shutdown");
        }
        if (nextProcessor != null) {
            nextProcessor.shutdown();
        }
    }

    public void processRequest(Request request) {
        // request.addRQRec(">sync");
        queuedRequests.add(request);
    }

}
