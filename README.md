# OpenIM

一个可运行的类微信即时通信 MVP，包含 Web 客户端、独立服务端、共享类型契约和完整的产品/技术文档基线。

当前版本支持：

- 用户名/邮箱 + 密码注册与登录，连续失败锁定；本地额外提供免密的演示账号入口。
- 单聊/群聊会话与实时文本消息，服务端 ACK、幂等键、会话序号和分页断线补拉。
- 会话创建与群成员管理：单聊幂等创建、建群、加人、移出、退群、管理员指派、
  转让群主，以及基于 `MEMBER/ADMIN/OWNER` 的权限矩阵。新成员看不到入群前的历史。
- 消息历史三个取数方向：打开会话取最新一页、向上翻阅更早、断线补拉，
  可见范围均以入群序号为下界。
- 用户目录带可见性边界：联系人为"与我有会话往来的人"，
  主动找人需提供完整用户名（不做前缀匹配，避免用户名被枚举）。
- 一对一及最多 6 人的 WebRTC Mesh 语音/视频通话房间与信令。
- 单聊定额、群聊等额/拼手气虚拟红包，余额流水和幂等领取。
- 服务端成员权限校验、统一错误码、健康检查和严格输入校验。
- 响应式 Web 工作台和红包/通话交互界面。

> 红包余额为本地虚拟资产，不连接真实支付。数据持久化到 PostgreSQL，红包账务使用复式记账并可对账；
> 认证、密码找回、限流与实时投递（Redis Streams，at-least-once）已可在 `prod` profile 下运行。
> 告警规则、SLO、备份与压测脚本均已就绪但**尚未接入真实监控栈、未执行演练与压测**，
> 多实例也未真实验证过——详见 [当前实现状态](./docs/03-delivery/IMPLEMENTATION_STATUS.md)。

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

打开 [http://127.0.0.1:5173](http://127.0.0.1:5173)。可以直接点选 Alice、Bob 或 Carol 一键进入（演示登录，不校验密码），也可以用账号密码登录同样的演示账号：

| 账号 | 密码 |
| --- | --- |
| `alice` / `bob` / `carol` | `openim-demo-2026` |

演示账号只随种子数据加载，`prod` profile 下不存在。可使用普通窗口和无痕窗口模拟两个独立客户端。

> **本地环境的两个已知坑**：
>
> 1. 若宿主机已有 PostgreSQL 占用 5432，它会优先于 Docker 的端口映射被连接，
>    服务端启动时报 `role "openim" does not exist`。先停掉本机实例，
>    或用 `DATABASE_URL` 指向其他端口。E2E 用的 `e2e` profile 使用独立端口
>    （55432/56379）正是为了绕开这个冲突。
> 2. 修改**已经在本地应用过**的迁移文件后，启动会报
>    `Migration checksum mismatch`。这是 Flyway 的正确行为——它防止已应用的
>    迁移被悄悄改动。本地重置即可：`docker compose down -v && docker compose up -d`
>    （演示数据会自动重建）。**不要在生产用 `flyway repair` 草率处理**，
>    先搞清楚 schema 与代码版本的对应关系。

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

生产环境的第一个账号通过注册接口创建（`openim.auth.registration-enabled` 可关闭注册以支持邀请制）：

```bash
curl -X POST https://<host>/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","email":"admin@example.com","displayName":"管理员",
       "password":"<至少 10 字符的密码>","deviceId":"bootstrap","deviceName":"CLI"}'
```

### 验证

```bash
docker compose up -d     # 集成测试需要 Docker 运行 Testcontainers
pnpm lint
pnpm typecheck
pnpm test                # 213 个 Java 测试 + 16 个 Web 测试 + 覆盖率门禁
pnpm build
```

端到端测试（自动启动服务端与 Web，用独立的 e2e 数据库）：

```bash
pnpm e2e:install         # 首次需要装 Chromium
pnpm e2e                 # 22 个用例：实时投递、断线补拉、红包资金流、认证、
                         # 会话创建与群成员管理、消息历史分页
                         # （会话与分页用例全部从注册全新账号开始，不用种子）
pnpm e2e:down            # 清理 e2e 数据库
```

故障注入——验证 Redis 停机时服务确实降级运行而非不可用：

```bash
docker compose --profile full up -d --build
./load-test/chaos-degradation.sh
```

> 这个脚本发现过一个只有真实故障才会暴露的缺陷：Lettuce 命令超时未配置，
> 导致 Redis 挂掉后每个请求阻塞 60 秒才走降级分支。单元测试注入的是
> 立即抛出的异常，测不出"超时"这种故障形态。

多实例验证——起两个实例，用真实 WebSocket 验证跨实例行为：

```bash
docker compose --profile multi up -d --build
node load-test/verify-multi-instance.mjs
```

> 17 项检查：双向跨实例投递、恰好一次、在线状态双向可见、消费组独立、
> 限流配额共享、单实例停机隔离。**这是"多实例可用"的唯一证据**——
> 单实例下所有跨实例代码路径都退化成本地操作，看不出任何问题。
> 这个脚本发现过跨实例投递曾**完全不工作**（Lettuce 阻塞读超时），
> 而当时 146 个测试全绿。

告警规则实测——用真实 Prometheus 加载规则并求值：

```bash
docker compose --profile multi up -d --build   # 或 --profile full
./deploy/observability/verify-rules.sh
```

> promtool 语法校验 + 规则被真实 Prometheus 加载 + 记录规则真的算出数值。
> 这个脚本发现延迟 SLI 一直是死的——Spring Boot 默认不发布 histogram bucket，
> 而所有延迟告警的表达式语法都正确、promtool 通过、只是永远返回空。

恢复演练——验证备份真的能用来重建：

```bash
docker exec openim-postgres pg_dump -U openim -d openim -Fc \
  --no-owner --no-privileges > /tmp/openim.dump
./deploy/scripts/restore-drill.sh /tmp/openim.dump
```

> 9 项检查，含迁移历史完整性与账务不变量。`pg_restore` 返回 0
> 只证明文件能解析，不证明数据可用。结果记入
> [`BACKUP.md`](./deploy/observability/BACKUP.md) 的演练记录表。

容量压测（需要独立环境，不进 CI）：

```bash
k6 run load-test/message-throughput.js     # 消息吞吐，阶梯加压
k6 run load-test/websocket-capacity.js     # 长连接容量
k6 run load-test/red-packet.js             # 红包并发正确性
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

Kubernetes 清单见 [`deploy/k8s/deployment.yaml`](./deploy/k8s/deployment.yaml)，
备份任务见 [`deploy/k8s/backup-cronjob.yaml`](./deploy/k8s/backup-cronjob.yaml)。

监控配置（告警规则、Grafana dashboard、SLO 定义、值班手册）在
[`deploy/observability/`](./deploy/observability/)。**这些文件尚未接入任何真实的
Prometheus/Grafana**——指标名有 `AlertingContractTest` 保护，但"告警真的会响"
没有被验证过。

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
- [SLO 与错误预算](./deploy/observability/SLO.md)
- [值班手册](./deploy/observability/RUNBOOK.md)
- [备份与恢复](./deploy/observability/BACKUP.md)
- [Java 服务端说明](./apps/server/README.md)

## 当前边界

数据持久化到 PostgreSQL，账务使用复式记账。Redis 用于在线状态、分布式限流、
跨实例事件扇出（**Redis Streams**，投递语义为 at-least-once）和缓存失效广播。
认证、密码找回、账号注销均可在 `prod` 下使用。

剩余距离生产的差距，按"离上线还差多远"排序：

**必须先做完的**

- **多实例已验证，但粘性路由未验证**：已真实启动两个实例跑过 17 项检查
  （`load-test/verify-multi-instance.mjs`）——双向跨实例投递、恰好一次、
  在线状态双向可见、消费组独立、限流配额共享、单实例停机不影响另一实例。
  这次验证发现跨实例投递曾**完全不工作**（Lettuce 阻塞读超时），已修复。
  仍未验证的是 WebSocket 连接的**粘性路由**——连接注册表在 JVM 内存里，
  需要负载均衡层配置会话亲和（`deploy/k8s` 的 Ingress 已配 cookie 亲和但未实测）。
- **告警未在真实故障下触发过**：24 条告警规则已用真实 Prometheus 验证
  （`deploy/observability/verify-rules.sh`）——promtool 语法校验通过、规则被加载、
  记录规则能算出数值、健康状态下不误报。**但"故障时真的会响并送达值班人"
  还差两步**：注入故障观察状态转为 firing，以及配置 Alertmanager 与接收端。
  这次验证发现延迟 SLI 一直是死的（Spring Boot 默认不发布 histogram bucket），已修复。
- **备份已演练一次，但数据量不具代表性**：首次演练通过，9 项检查全部成立
  （含账务不变量与迁移历史完整性）。但数据量只有 11 条消息，**RTO 实测值无意义**；
  且备份未经过对象存储的上传下载链路。**RPO 仍是 24 小时**（PITR 需要 WAL 归档）。
  详见 `deploy/observability/BACKUP.md` 的演练记录。
- **容量未压测**：压测脚本覆盖消息吞吐与长连接容量，但没跑过。
  因此 `max-connections: 10000` 是估算值而非实测值。

**上线后需要尽快补的**

- **身份验证方式**：密码登录 + 密码找回已实现；短信/邮箱验证码、OAuth/SSO、MFA 未实现。
  密码找回的送达渠道是抽象的（`PasswordResetNotifier`），prod 下必须接入真实实现
  或显式关闭该功能——否则应用**拒绝启动**。
- **密钥管理**：JWT 支持多密钥轮换，但密钥仍来自环境变量，未接入 KMS/Secret Manager。
- **链路导出**：OTLP exporter 已就绪，注入 `MANAGEMENT_OTLP_TRACING_ENDPOINT` 即启用；
  未注入时 traceId/spanId 仍进日志，只是看不到跨服务调用链。
- **备份加密与跨区域**：dump 以明文存在对象存储，且与数据库同区域。
- **音视频**：WebRTC 使用 Mesh 和公共 STUN，未部署 TURN/SFU。E2E 用假设备，
  只覆盖信令与界面，不覆盖媒体质量。

**结构性的技术债**

- **客户端**：Web 单文件 `App.tsx` 约 1900 行，无路由与状态管理，无 i18n
  （中文硬编码），无前端错误监控——服务端有 traceId、指标与告警，
  而前端崩溃没有任何人知道，只有一个 `ErrorBoundary` 兜底渲染。
  会话令牌存在 `localStorage`，XSS 即可窃取（企业级方案是 httpOnly cookie + CSRF
  或 BFF 持有令牌）。已抽出纯逻辑模块（`messageDate.ts`、`messageSync.ts`）
  使其可测试，但整体拆分尚未进行。Electron、iOS、Android 通过共享契约后续接入。
- **没有好友模型**：联系人是从会话关系**推导**出来的（与我共处过至少一个会话的人），
  不是一张独立的关系表。因此没有好友请求、没有黑名单、没有分组，
  也无法阻止陌生人通过用户名向我发起对话。这些属于 M2。
- **消息列表无虚拟滚动**：改用"窗口 + 按需展开"（一屏 80 条，点击展开 120 条），
  契合"用户几乎总在看最新消息"的使用方式。真正需要跳转到任意历史位置时
  才值得引入虚拟滚动。
- **消息表增长**：`messages` **刻意不做时间分区**——查询模式是
  `conversation_id + seq`，按时间分区会让历史拉取跨分区且分区裁剪失效，
  还会破坏 `red_packets.message_id` 的外键。真正需要时应做归档或按会话哈希分区，
  详细论证见 `V7__audit_log_partitioning.sql` 的头部注释。
- **功能**：附件、离线推送、消息撤回、免打扰、会话置顶和管理后台列入 M2。
  `messages.type` 的 `image`/`file`、`messages.status` 的 `RECALLED`、
  `conversation_members` 的 `muted`/`pinned_at` 都是**建表起就有、至今零代码引用**
  的预留取值——schema 里存在不代表能力存在（`users.status` 的 `'DELETED'`
  曾经也是如此，见 `AccountDeletionService` 的类注释）。
  数据模型文档已按"是否已被代码使用"逐字段标注。
- **内容安全**：无敏感词过滤、无图片审核、无举报与申诉流程。
  在境内运营 IM 这是监管强制项，不是可选功能。
- **群成员管理的注意事项**：任何新增的写 `conversation_members` 的路径都**必须**
  调用 `conversationService.invalidateMembers()`——漏掉的症状是新成员延迟收消息、
  被移出者继续收消息，且要等一个 TTL 才消失。现有的六条写路径都已调用。

进入真实业务前还需完成对象存储、TURN/SFU、离线推送、内容安全、渗透测试和隐私合规评审。
账号注销采用匿名化而非物理删除，保留了账务流水与消息正文——
**这个边界有法律含义，上线前应由法务确认**（见 `AccountDeletionService` 的类注释）。
