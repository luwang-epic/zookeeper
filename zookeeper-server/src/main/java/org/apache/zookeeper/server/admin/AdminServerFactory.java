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

package org.apache.zookeeper.server.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;

/**
 * Factory class for creating an AdminServer.
 */
public class AdminServerFactory {
    private static final Logger LOG = LoggerFactory.getLogger(AdminServerFactory.class);

    /**
     * This method encapsulates the logic for whether we should use a
     * JettyAdminServer (i.e., the AdminServer is enabled) or a DummyAdminServer
     * (i.e., the AdminServer is disabled). It uses reflection when attempting
     * to create a JettyAdminServer, rather than referencing the class directly,
     * so that it's ok to omit Jetty from the classpath if a user doesn't wish
     * to pull in Jetty with ZooKeeper.
     */
    public static AdminServer createAdminServer() {
        if (!"false".equals(System.getProperty("zookeeper.admin.enableServer"))) {
            try {
                /*
                在idea中会报NoClassDefFoundError错误，是因为idea中没有jetty服务器相关的jar
                需要在pom文件中将jetty-server和jackson-databind的依赖的provide范围去除，
                并加入<groupId>com.jfinal</groupId><artifactId>jetty-server</artifactId>的依赖
                 */
                Class<?> jettyAdminServerC = Class.forName("org.apache.zookeeper.server.admin.JettyAdminServer");
                Object adminServer = jettyAdminServerC.getConstructor().newInstance();
                return (AdminServer) adminServer;

            } catch (ClassNotFoundException e) {
                LOG.warn("Unable to start JettyAdminServer", e);
            } catch (InstantiationException e) {
                LOG.warn("Unable to start JettyAdminServer", e);
            } catch (IllegalAccessException e) {
                LOG.warn("Unable to start JettyAdminServer", e);
            } catch (InvocationTargetException e) {
                LOG.warn("Unable to start JettyAdminServer", e);
            } catch (NoSuchMethodException e) {
                LOG.warn("Unable to start JettyAdminServer", e);
            } catch (NoClassDefFoundError e) {
                LOG.warn("Unable to load jetty, not starting JettyAdminServer", e);
            }
        }
        return new DummyAdminServer();
    }
}
