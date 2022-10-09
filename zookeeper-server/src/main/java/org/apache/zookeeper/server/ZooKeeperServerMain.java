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

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.jmx.ManagedUtil;
import org.apache.zookeeper.server.admin.AdminServer;
import org.apache.zookeeper.server.admin.AdminServer.AdminServerException;
import org.apache.zookeeper.server.admin.AdminServerFactory;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog.DatadirException;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.JMException;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * This class starts and runs a standalone ZooKeeperServer.
 */
@InterfaceAudience.Public
public class ZooKeeperServerMain {
    private static final Logger LOG =
        LoggerFactory.getLogger(ZooKeeperServerMain.class);

    private static final String USAGE =
        "Usage: ZooKeeperServerMain configfile | port datadir [ticktime] [maxcnxns]";

    // ZooKeeper server supports two kinds of connection: unencrypted and encrypted.
    private ServerCnxnFactory cnxnFactory;
    private ServerCnxnFactory secureCnxnFactory;
    private ContainerManager containerManager;

    private AdminServer adminServer;

    /*
     * Start up the ZooKeeper server.
     *
     * @param args the configfile or the port datadir [ticktime]
     */
    public static void main(String[] args) {
        ZooKeeperServerMain main = new ZooKeeperServerMain();
        try {
            main.initializeAndRun(args);
        } catch (IllegalArgumentException e) {
            LOG.error("Invalid arguments, exiting abnormally", e);
            LOG.info(USAGE);
            System.err.println(USAGE);
            System.exit(2);
        } catch (ConfigException e) {
            LOG.error("Invalid config, exiting abnormally", e);
            System.err.println("Invalid config, exiting abnormally");
            System.exit(2);
        } catch (DatadirException e) {
            LOG.error("Unable to access datadir, exiting abnormally", e);
            System.err.println("Unable to access datadir, exiting abnormally");
            System.exit(3);
        } catch (AdminServerException e) {
            LOG.error("Unable to start AdminServer, exiting abnormally", e);
            System.err.println("Unable to start AdminServer, exiting abnormally");
            System.exit(4);
        } catch (Exception e) {
            LOG.error("Unexpected exception, exiting abnormally", e);
            System.exit(1);
        }
        LOG.info("Exiting normally");
        System.exit(0);
    }

    protected void initializeAndRun(String[] args)
        throws ConfigException, IOException, AdminServerException
    {
        try {
            ManagedUtil.registerLog4jMBeans();
        } catch (JMException e) {
            LOG.warn("Unable to register log4j JMX control", e);
        }

        ServerConfig config = new ServerConfig();
        if (args.length == 1) {
            config.parse(args[0]);
        } else {
            config.parse(args);
        }

        runFromConfig(config);
    }

    /**
     * Run from a ServerConfig.
     * @param config ServerConfig to use.
     * @throws IOException
     * @throws AdminServerException
     */
    public void runFromConfig(ServerConfig config)
            throws IOException, AdminServerException {
        LOG.info("Starting server");
        FileTxnSnapLog txnLog = null;
        try {
            // Note that this thread isn't going to be doing anything else,
            // so rather than spawning another thread, we will just call
            // run() in this thread.
            // create a file logger url from the command line args
            txnLog = new FileTxnSnapLog(config.dataLogDir, config.dataDir);
            final ZooKeeperServer zkServer = new ZooKeeperServer(txnLog,
                    config.tickTime, config.minSessionTimeout, config.maxSessionTimeout, null);
            txnLog.setServerStats(zkServer.serverStats());

            // Registers shutdown handler which will be used to know the
            // server error or shutdown state changes.
            final CountDownLatch shutdownLatch = new CountDownLatch(1);
            zkServer.registerServerShutdownHandler(
                    new ZooKeeperServerShutdownHandler(shutdownLatch));

            // AdminServer主要作用是启动一个默认在8080端口的JettyAdminServer服务，
            // 可以查看一些信息，包括集群信息，log日志，snapshot等，具体看：JettyAdminServer类
            // Start Admin server
            adminServer = AdminServerFactory.createAdminServer();
            adminServer.setZooKeeperServer(zkServer);
            adminServer.start();

            boolean needStartZKServer = true;
            if (config.getClientPortAddress() != null) {
                cnxnFactory = ServerCnxnFactory.createFactory();
                // 配置ServerCnxnFactory，即初始化各成员变量，包括selectorThreads和acceptThread以及numWorkerThreads等
                cnxnFactory.configure(config.getClientPortAddress(), config.getMaxClientCnxns(), false);
                cnxnFactory.startup(zkServer);
                // zkServer has been started. So we don't need to start it again in secureCnxnFactory.
                needStartZKServer = false;
            }
            if (config.getSecureClientPortAddress() != null) {
                secureCnxnFactory = ServerCnxnFactory.createFactory();
                secureCnxnFactory.configure(config.getSecureClientPortAddress(), config.getMaxClientCnxns(), true);
                secureCnxnFactory.startup(zkServer, needStartZKServer);
            }

            /*
            容器节点是 3.5 以后新增的节点类型，只要在调用create 方法时，
            指定CreateMode 为CONTAINER 即可创建容器的节点类型，容器节点的表现形式和持久节点是一样的，
            但是区别是 ZK 服务端启动后，会有一个单独的线程去扫描，所有的容器节点，
            当发现容器节点的子节点数量为 0 时，会自动删除该节点，除此之外和持久节点没有区别，
            官方注释给出的使用场景是Container nodes are special purpose nodes useful for recipes such as leader, lock, etc.
            说可以用在 leader 或者锁的场景中。

            持久TTL节点和持久顺序TTL节点，这两种类型的节点重点是后面的TTL，TTL 是time to live 的缩写，指带有存活时间，
            简单来说就是当该节点下面没有子节点的话，超过了TTL 指定时间后就会被自动删除，特性跟上面的容器节点很像，
            只是容器节点没有超时时间而已，但是TTL 启用是需要额外的配置（这个之前也有提过）配置是zookeeper.extendedTypesEnabled 需要配置成true，否则的话创建TTL 时会收到Unimplemented 的报错
             */
            // 该类就是用来周期性的进行检查和清理容器节点
            // 看底层的实现，也会清理ttl过期的无子节点的节点
            containerManager = new ContainerManager(zkServer.getZKDatabase(), zkServer.firstProcessor,
                    Integer.getInteger("znode.container.checkIntervalMs", (int) TimeUnit.MINUTES.toMillis(1)),
                    Integer.getInteger("znode.container.maxPerMinute", 10000)
            );
            containerManager.start();

            // Watch status of ZooKeeper server. It will do a graceful shutdown
            // if the server is not running or hits an internal error.
            shutdownLatch.await();

            shutdown();

            if (cnxnFactory != null) {
                cnxnFactory.join();
            }
            if (secureCnxnFactory != null) {
                secureCnxnFactory.join();
            }
            if (zkServer.canShutdown()) {
                zkServer.shutdown(true);
            }
        } catch (InterruptedException e) {
            // warn, but generally this is ok
            LOG.warn("Server interrupted", e);
        } finally {
            if (txnLog != null) {
                txnLog.close();
            }
        }
    }

    /**
     * Shutdown the serving instance
     */
    protected void shutdown() {
        if (containerManager != null) {
            containerManager.stop();
        }
        if (cnxnFactory != null) {
            cnxnFactory.shutdown();
        }
        if (secureCnxnFactory != null) {
            secureCnxnFactory.shutdown();
        }
        try {
            if (adminServer != null) {
                adminServer.shutdown();
            }
        } catch (AdminServerException e) {
            LOG.warn("Problem stopping AdminServer", e);
        }
    }

    // VisibleForTesting
    ServerCnxnFactory getCnxnFactory() {
        return cnxnFactory;
    }
}
