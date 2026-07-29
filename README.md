# OpenIM

一个可运行的类微信即时通信 MVP，包含 Web 客户端、独立服务端、共享类型契约和完整的产品/技术文档基线。

当前版本支持：

- 演示账号登录和多标签页实时连接。
- 单聊/群聊会话与实时文本消息，服务端 ACK、幂等键、会话序号和分页断线补拉。
- 一对一及最多 6 人的 WebRTC Mesh 语音/视频通话房间与信令。
- 单聊定额、群聊等额/拼手气虚拟红包，余额流水和幂等领取。
- 服务端成员权限校验、统一错误码、健康检查和严格输入校验。
- 响应式 Web 工作台和红包/通话交互界面。

> 红包余额为本地虚拟资产，不连接真实支付。数据持久化到 PostgreSQL，红包账务使用复式记账并可对账；但认证、限流和可观测性尚未达到生产标准，详见 [当前实现状态](./docs/03-delivery/IMPLEMENTATION_STATUS.md)。

## 工程结构

```text
apps/web                 React + TypeScript + Vite 客户端
apps/server              Java 21 + Spring Boot 3.5 服务端
packages/contracts       Web 客户端 TypeScript 协议类型
docs                     PRD、架构、专项设计和验收文档
```

## 快速开始

### 环境要求

- Java 21+、Maven 3.9+
- Node.js 22+（用于 Web 客户端）
- pnpm 10+
- Docker（提供本地 PostgreSQL，集成测试也依赖它运行 Testcontainers）
- Chrome、Edge 或 Safari 最近版本

### 安装与启动

```bash
docker compose up -d     # 启动 PostgreSQL
pnpm install
pnpm dev
```

服务端启动时由 Flyway 自动建表并写入演示数据，无需手工初始化。

打开 [http://127.0.0.1:5173](http://127.0.0.1:5173)，选择 Alice、Bob 或 Carol 登录。可使用普通窗口和无痕窗口模拟两个独立客户端。

需要重置数据时：

```bash
docker compose down -v && docker compose up -d
```

默认地址：

- Web：`http://127.0.0.1:5173`
- API：`http://127.0.0.1:3001`
- WebSocket：`ws://127.0.0.1:3001/ws`
- 健康检查：`http://127.0.0.1:3001/health`

若需覆盖配置，复制 `.env.example` 中的变量到本地环境。生产环境必须替换 `TOKEN_SECRET`。

部署配置校验可使用 `prod` profile：

```bash
SPRING_PROFILES_ACTIVE=prod \
TOKEN_SECRET='<至少 32 字符的随机密钥>' \
CLIENT_ORIGIN='https://im.example.com' \
DATABASE_URL='jdbc:postgresql://<host>:5432/openim' \
DATABASE_USER='<user>' \
DATABASE_PASSWORD='<password>' \
java -jar apps/server/target/open-im-server-0.1.0.jar
```

`prod` profile 会禁用演示登录、不加载演示种子数据，并在缺少密钥、密钥过短或误用本地演示密钥时拒绝启动。该 profile 是安全启动闸门，不代表整体已达到生产标准。

### 验证

```bash
docker compose up -d     # 集成测试需要 Docker 运行 Testcontainers
pnpm typecheck
pnpm test
pnpm build
```

### 以容器方式运行全栈

```bash
docker compose --profile full up -d --build
```

服务端会连同 PostgreSQL 一起启动，就绪后可访问：

- 健康检查：`http://127.0.0.1:3001/actuator/health`
- 存活探针：`http://127.0.0.1:3001/actuator/health/liveness`
- 就绪探针：`http://127.0.0.1:3001/actuator/health/readiness`
- 指标：`http://127.0.0.1:3001/actuator/prometheus`

Kubernetes 清单见 [`deploy/k8s/deployment.yaml`](./deploy/k8s/deployment.yaml)。

### 运行编译产物

```bash
pnpm build
java -jar apps/server/target/open-im-server-0.1.0.jar
pnpm --filter @open-im/web preview
```

## 演示路径

### 实时消息

1. 在两个浏览器上下文分别以 Alice 和 Bob 登录。
2. 打开双方单聊，发送文本消息。
3. 对方实时收到，发送方看到会话摘要和“已发送”状态。

### 音视频通话

1. Alice 在会话右上角选择语音或视频。
2. Bob 接听后允许浏览器摄像头/麦克风权限。
3. 双方通过 WebRTC 建立媒体连接，可静音、关闭摄像头和挂断。

公网环境必须配置 HTTPS 与 TURN；多人或移动弱网生产场景建议按设计文档接入 SFU。

### 红包

1. 点击输入框上方的 `¥`，填写金额和份数。
2. 群聊可选等额或拼手气；单聊固定为一份。
3. 另一账号点击红包卡片领取，钱包余额和领取详情即时更新。

## 文档入口

- [产品需求文档](./docs/00-product/PRD.md)
- [用户故事与业务规则](./docs/00-product/USER_STORIES.md)
- [版本路线图](./docs/00-product/ROADMAP.md)
- [总体架构](./docs/01-architecture/ARCHITECTURE.md)
- [数据模型](./docs/01-architecture/DATA_MODEL.md)
- [API 与实时事件](./docs/01-architecture/API_AND_EVENTS.md)
- [音视频专项设计](./docs/02-features/RTC.md)
- [红包与虚拟账务设计](./docs/02-features/RED_PACKET.md)
- [安全、隐私与合规](./docs/02-features/SECURITY.md)
- [MVP 验收方案](./docs/03-delivery/ACCEPTANCE.md)
- [当前实现状态](./docs/03-delivery/IMPLEMENTATION_STATUS.md)
- [Java 服务端说明](./apps/server/README.md)

## 当前边界

数据已持久化到 PostgreSQL，账务使用复式记账。剩余距离生产的差距：

- **多实例**：WebSocket 连接注册表仍在 JVM 内存中，起第二个实例会丢消息，需要 Redis Pub/Sub 或 MQ 扇出。Outbox 表已就绪但尚未驱动推送。
- **限流**：已按动作分策略限流，但配额是进程内的——多实例部署时实际总配额会翻倍，需换成 Redis 后端。
- **密钥管理**：JWT 支持多密钥轮换，但密钥仍来自环境变量，未接入 KMS/Secret Manager。
- **链路导出**：已生成 traceId/spanId 并注入日志，但未配置 OTLP collector，span 不对外导出。
- **音视频**：WebRTC 使用 Mesh 和公共 STUN，未部署 TURN/SFU。
- **客户端**：已实现 Web；Electron、iOS、Android 通过共享契约后续接入。
- **功能**：附件、离线推送、消息撤回、群成员管理和管理后台列入 M2。

进入真实业务前还需完成 Redis、对象存储、TURN/SFU、离线推送、内容安全、压测、渗透测试和隐私合规评审。
