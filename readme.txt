项目的zookeeper版本3.5.7

Zookeeper源码阅读大纲：

一. 客户端代码：
1.  ClientCnxn客户端连接抽象

2. SendThread

3. EventThread

4. getData非事务请求

5. setData事务请求

6. ZKWatchManager 客户端watcher管理的实现



二. 服务端源码

1. 配置解析

2. 恢复内存数据库

3. 监听客户端连接和客户端会话管理

4. Leader选举

5. 数据差异化同步

6. 事务日志和快照日志

7. 事务请求流程，包括请求处理器链，两阶段提交

8. 内部节点之间的会话管理

9. 服务端watcher注册和触发

10. DataTree内存数据库