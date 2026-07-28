# OpenIM Java 服务端

`apps/server` 是 OpenIM MVP 的独立服务端，基于 Java 21、Spring Boot 3.5 和 Maven。它提供 REST API、原生 WebSocket 实时消息与 WebRTC 信令、虚拟钱包和红包领域逻辑。

## 本地运行

在仓库根目录执行：

```bash
pnpm dev:server
```

也可以直接使用 Maven：

```bash
mvn -f apps/server/pom.xml spring-boot:run
mvn -f apps/server/pom.xml test
mvn -f apps/server/pom.xml package
java -jar apps/server/target/open-im-server-0.1.0.jar
```

服务默认监听 `127.0.0.1:3001`。健康检查为 `GET /health`，REST API 位于 `/api/v1`，WebSocket 端点为 `/ws?token=<accessToken>&deviceId=<deviceId>`。

## 配置

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `HOST` | `127.0.0.1` | 服务监听地址 |
| `PORT` | `3001` | HTTP/WebSocket 端口 |
| `CLIENT_ORIGIN` | `http://localhost:5173` | 允许的客户端来源 |
| `TOKEN_SECRET` | 本地演示密钥 | HMAC-SHA256 令牌签名密钥；生产环境必须替换 |
| `DEMO_LOGIN_ENABLED` | `true` | 是否开放演示账号登录；`prod` profile 强制关闭 |
| `SPRING_PROFILES_ACTIVE` | 空 | 设置为 `prod` 时启用生产安全启动闸门 |

生产安全配置示例：

```bash
SPRING_PROFILES_ACTIVE=prod \
TOKEN_SECRET='<至少 32 字符的随机密钥>' \
CLIENT_ORIGIN='https://im.example.com' \
java -jar target/open-im-server-0.1.0.jar
```

缺少强密钥或尝试使用本地演示密钥时，服务端会拒绝启动。当前仓储仍是内存实现，因此该配置不能替代后续的持久化和高可用建设。

## 代码结构

```text
com.openim.server
├── auth       REST 鉴权拦截器、身份上下文和 HMAC 令牌
├── config     Spring MVC、CORS 与 WebSocket 配置
├── domain     领域模型、请求模型、内存仓储和红包算法
├── realtime   WebSocket 握手鉴权、命令路由、事件和 RTC 信令
└── web        REST 控制器、请求 ID、响应 Envelope 和异常处理
```

实时协议使用 JSON Envelope：客户端发送 `command`，服务端返回同一 `commandId` 的 `ack`，并向相关用户或设备推送 `event`。完整字段及事件目录见 [API 与实时事件](../../docs/01-architecture/API_AND_EVENTS.md)。

## 一致性与生产边界

- 消息用客户端幂等键去重，并在单实例临界区内分配会话级递增 `seq`；历史接口按 `afterSeq` 升序分页，每页最多 100 条。
- 红包在单实例临界区内完成余额扣减、预分配和幂等领取；到期访问会触发退款。
- 通话 REST API 管理房间状态，WebSocket 只向已授权的目标设备转发 SDP/ICE。
- 当前仓储、令牌状态和在线连接都在内存中，服务重启会恢复演示数据。

生产部署需将内存仓储替换为 PostgreSQL，引入 Redis 和 Outbox/消息队列完成跨实例广播与可靠事件投递，并为公网音视频配置 TURN/SFU。
