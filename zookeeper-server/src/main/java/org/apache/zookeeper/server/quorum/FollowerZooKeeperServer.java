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

package org.apache.zookeeper.server.quorum;

import org.apache.jute.Record;
import org.apache.zookeeper.server.FinalRequestProcessor;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.txn.TxnHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Just like the standard ZooKeeperServer. We just replace the request
 * processors: FollowerRequestProcessor -> CommitProcessor ->
 * FinalRequestProcessor
 *
 * A SyncRequestProcessor is also spawned off to log proposals from the leader.
 */
public class FollowerZooKeeperServer extends LearnerZooKeeperServer {
    private static final Logger LOG =
        LoggerFactory.getLogger(FollowerZooKeeperServer.class);

    /*
     * Pending sync requests
     */
    ConcurrentLinkedQueue<Request> pendingSyncs;

    /**
     * @param port
     * @param dataDir
     * @throws IOException
     */
    FollowerZooKeeperServer(FileTxnSnapLog logFactory,QuorumPeer self,
            ZKDatabase zkDb) throws IOException {
        super(logFactory, self.tickTime, self.minSessionTimeout,
                self.maxSessionTimeout, zkDb, self);
        this.pendingSyncs = new ConcurrentLinkedQueue<Request>();
    }

    public Follower getFollower(){
        return self.follower;
    }

    @Override
    protected void setupRequestProcessors() {
        // 这个处理器负责把已经commit的写操作应用到本机，对于读操作则从本机中读取数据并返回给client
        RequestProcessor finalProcessor = new FinalRequestProcessor(this);

        // 不是事务请求，直接交给下一个处理器，
        // 对于事务请求，进行commit确认，收到commit信息后再交给下一个处理器，否则阻塞
        commitProcessor = new CommitProcessor(finalProcessor,
                Long.toString(getServerId()), true, getZooKeeperServerListener());
        commitProcessor.start();

        // 对于非事务操作，直接交给下一个处理器，对于事务操作，转发请求给leader
        // 如果是来自leader的事务请求，则直接交给commit处理，不经过FollowerRequestProcessor处理器
        firstProcessor = new FollowerRequestProcessor(this, commitProcessor);
        ((FollowerRequestProcessor) firstProcessor).start();

        // 记录事务日志
        syncProcessor = new SyncRequestProcessor(this,
                // SendAckRequestProcessor处理器没有下一个处理器，只是发送ack信息到leader
                // 根据zab协议，就是说当节点记录了事务日志后，说明操作成功了，发送ack到leader，
                new SendAckRequestProcessor((Learner)getFollower()));
        syncProcessor.start();
    }

    LinkedBlockingQueue<Request> pendingTxns = new LinkedBlockingQueue<Request>();

    public void logRequest(TxnHeader hdr, Record txn) {
        Request request = new Request(hdr.getClientId(), hdr.getCxid(), hdr.getType(), hdr, txn, hdr.getZxid());
        if ((request.zxid & 0xffffffffL) != 0) {
            // 将请求加入到pendingTxns中，等待commitProcessor处理
            pendingTxns.add(request);
        }
        syncProcessor.processRequest(request);
    }

    /**
     * When a COMMIT message is received, eventually this method is called,
     * which matches up the zxid from the COMMIT with (hopefully) the head of
     * the pendingTxns queue and hands it to the commitProcessor to commit.
     * @param zxid - must correspond to the head of pendingTxns if it exists
     */
    public void commit(long zxid) {
        if (pendingTxns.size() == 0) {
            LOG.warn("Committing " + Long.toHexString(zxid)
                    + " without seeing txn");
            return;
        }
        long firstElementZxid = pendingTxns.element().zxid;
        if (firstElementZxid != zxid) {
            LOG.error("Committing zxid 0x" + Long.toHexString(zxid)
                    + " but next pending txn 0x"
                    + Long.toHexString(firstElementZxid));
            System.exit(12);
        }
        Request request = pendingTxns.remove();
        commitProcessor.commit(request);
    }

    synchronized public void sync(){
        if(pendingSyncs.size() ==0){
            LOG.warn("Not expecting a sync.");
            return;
        }

        Request r = pendingSyncs.remove();
		commitProcessor.commit(r);
    }

    @Override
    public int getGlobalOutstandingLimit() {
        int divisor = self.getQuorumSize() > 2 ? self.getQuorumSize() - 1 : 1;
        return super.getGlobalOutstandingLimit() / divisor;
    }

    @Override
    public String getState() {
        return "follower";
    }

    @Override
    public Learner getLearner() {
        return getFollower();
    }
}
