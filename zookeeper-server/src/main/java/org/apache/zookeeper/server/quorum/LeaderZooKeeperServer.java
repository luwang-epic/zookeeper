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

import org.apache.zookeeper.KeeperException.SessionExpiredException;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.server.ContainerManager;
import org.apache.zookeeper.server.DataTreeBean;
import org.apache.zookeeper.server.FinalRequestProcessor;
import org.apache.zookeeper.server.PrepRequestProcessor;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 *
 * Just like the standard ZooKeeperServer. We just replace the request
 * processors: PrepRequestProcessor -> ProposalRequestProcessor ->
 * CommitProcessor -> Leader.ToBeAppliedRequestProcessor ->
 * FinalRequestProcessor
 */
public class LeaderZooKeeperServer extends QuorumZooKeeperServer {
    private ContainerManager containerManager;  // guarded by sync


    CommitProcessor commitProcessor;

    PrepRequestProcessor prepRequestProcessor;

    /**
     * @param port
     * @param dataDir
     * @throws IOException
     */
    LeaderZooKeeperServer(FileTxnSnapLog logFactory, QuorumPeer self, ZKDatabase zkDb) throws IOException {
        super(logFactory, self.tickTime, self.minSessionTimeout, self.maxSessionTimeout, zkDb, self);
    }

    public Leader getLeader(){
        return self.leader;
    }

    @Override
    protected void setupRequestProcessors() {
        // 这个处理器负责把已经commit的写操作应用到本机，对于读操作则从本机中读取数据并返回给client
        RequestProcessor finalProcessor = new FinalRequestProcessor(this);

        // 将请求交给下一个处理器，然后从Leader.toBeApplied集合中移除已经提交的提案
        RequestProcessor toBeAppliedProcessor = new Leader.ToBeAppliedRequestProcessor(finalProcessor, getLeader());

        // 不是事务请求，直接交给下一个处理器，
        // 对于事务请求，进行commit确认，收到commit信息后再交给下一个处理器，否则阻塞
        commitProcessor = new CommitProcessor(toBeAppliedProcessor,
                Long.toString(getServerId()), false,
                getZooKeeperServerListener());
        commitProcessor.start();

        // 如果是非事务操作，直接交给下一个处理器，否则除了交给下一个处理器，还需要发起一个事务提案
        ProposalRequestProcessor proposalProcessor = new ProposalRequestProcessor(this,
                commitProcessor);
        // ProposalRequestProcessor这个处理器中内置了SyncRequestProcessor，AckRequestProcessor
        // 并在构造函数中将AckRequestProcessor处理器设置为SyncRequestProcessor处理器的下一个处理器
        // 在initialize方法中，开启SyncRequestProcessor处理器，来写自身的事务日志和对事务的ack操作
        proposalProcessor.initialize();

        // 会对请求做一些预处理工作，比如：检查路径，检查权限，开启事务等
        prepRequestProcessor = new PrepRequestProcessor(this, proposalProcessor);
        prepRequestProcessor.start();

        // 对特殊的操作，记住session的升级；否则一般情况都是直接交给下一个处理器
        // follower等发送过来的消息，是找follower节点内部已经验证过的消息了，因此不需要经过这个处理器
        firstProcessor = new LeaderRequestProcessor(this, prepRequestProcessor);

        // 初始化ContainerManager类，方便后续启动这个线程
        setupContainerManager();
    }

    private synchronized void setupContainerManager() {
        containerManager = new ContainerManager(getZKDatabase(), prepRequestProcessor,
                Integer.getInteger("znode.container.checkIntervalMs", (int) TimeUnit.MINUTES.toMillis(1)),
                Integer.getInteger("znode.container.maxPerMinute", 10000)
                );
    }

    @Override
    public synchronized void startup() {
        super.startup();
        if (containerManager != null) {
            containerManager.start();
        }
    }

    @Override
    public synchronized void shutdown() {
        if (containerManager != null) {
            containerManager.stop();
        }
        super.shutdown();
    }

    @Override
    public int getGlobalOutstandingLimit() {
        int divisor = self.getQuorumSize() > 2 ? self.getQuorumSize() - 1 : 1;
        return super.getGlobalOutstandingLimit() / divisor;
    }

    @Override
    public void createSessionTracker() {
        sessionTracker = new LeaderSessionTracker(
                this, getZKDatabase().getSessionWithTimeOuts(),
                tickTime, self.getId(), self.areLocalSessionsEnabled(), 
                getZooKeeperServerListener());
    }

    public boolean touch(long sess, int to) {
        return sessionTracker.touchSession(sess, to);
    }

    public boolean checkIfValidGlobalSession(long sess, int to) {
        if (self.areLocalSessionsEnabled() &&
            !upgradeableSessionTracker.isGlobalSession(sess)) {
            return false;
        }
        return sessionTracker.touchSession(sess, to);
    }

    /**
     * Requests coming from the learner should go directly to
     * PrepRequestProcessor
     *
     * @param request
     */
    public void submitLearnerRequest(Request request) {
        /*
         * Requests coming from the learner should have gone through
         * submitRequest() on each server which already perform some request
         * validation, so we don't need to do it again.
         *
         * Additionally, LearnerHandler should start submitting requests into
         * the leader's pipeline only when the leader's server is started, so we
         * can submit the request directly into PrepRequestProcessor.
         *
         * This is done so that requests from learners won't go through
         * LeaderRequestProcessor which perform local session upgrade.
         */
        prepRequestProcessor.processRequest(request);
    }

    @Override
    protected void registerJMX() {
        // register with JMX
        try {
            jmxDataTreeBean = new DataTreeBean(getZKDatabase().getDataTree());
            MBeanRegistry.getInstance().register(jmxDataTreeBean, jmxServerBean);
        } catch (Exception e) {
            LOG.warn("Failed to register with JMX", e);
            jmxDataTreeBean = null;
        }
    }

    public void registerJMX(LeaderBean leaderBean,
            LocalPeerBean localPeerBean)
    {
        // register with JMX
        if (self.jmxLeaderElectionBean != null) {
            try {
                MBeanRegistry.getInstance().unregister(self.jmxLeaderElectionBean);
            } catch (Exception e) {
                LOG.warn("Failed to register with JMX", e);
            }
            self.jmxLeaderElectionBean = null;
        }

        try {
            jmxServerBean = leaderBean;
            MBeanRegistry.getInstance().register(leaderBean, localPeerBean);
        } catch (Exception e) {
            LOG.warn("Failed to register with JMX", e);
            jmxServerBean = null;
        }
    }

    @Override
    protected void unregisterJMX() {
        // unregister from JMX
        try {
            if (jmxDataTreeBean != null) {
                MBeanRegistry.getInstance().unregister(jmxDataTreeBean);
            }
        } catch (Exception e) {
            LOG.warn("Failed to unregister with JMX", e);
        }
        jmxDataTreeBean = null;
    }

    protected void unregisterJMX(Leader leader) {
        // unregister from JMX
        try {
            if (jmxServerBean != null) {
                MBeanRegistry.getInstance().unregister(jmxServerBean);
            }
        } catch (Exception e) {
            LOG.warn("Failed to unregister with JMX", e);
        }
        jmxServerBean = null;
    }

    @Override
    public String getState() {
        return "leader";
    }

    /**
     * Returns the id of the associated QuorumPeer, which will do for a unique
     * id of this server.
     */
    @Override
    public long getServerId() {
        return self.getId();
    }

    @Override
    protected void revalidateSession(ServerCnxn cnxn, long sessionId,
        int sessionTimeout) throws IOException {
        super.revalidateSession(cnxn, sessionId, sessionTimeout);
        try {
            // setowner as the leader itself, unless updated
            // via the follower handlers
            setOwner(sessionId, ServerCnxn.me);
        } catch (SessionExpiredException e) {
            // this is ok, it just means that the session revalidation failed.
        }
    }
}
