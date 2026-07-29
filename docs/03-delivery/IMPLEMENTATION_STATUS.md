# 当前实现状态

> 基线日期：2026-07-29
> 版本：M1 MVP + 生产化改造进行中 / v0.1

## 生产化改造进度

按 [ROADMAP](../00-product/ROADMAP.md) 的升级闸门推进，当前进度：

| # | 阶段 | 状态 |
| --- | --- | --- |
| 1 | git 基线与仓库治理 | ✅ 完成 |
| 2 | PostgreSQL 持久化与领域服务拆分 | ✅ 完成 |
| 3 | 认证体系（标准 JWT、刷新轮换、撤销） | ✅ 完成 |
| 4 | 限流、安全响应头、WebSocket 健壮性 | ✅ 完成 |
| 5 | 容器化与可观测性 | ✅ 完成 |
| 6 | 测试补强与 CI 安全门禁 | ✅ 完成 |

## 已完成

| 领域 | 实现 |
| --- | --- |
| 工程 | Java 21/Spring Boot 服务端、pnpm Web 工作区、TypeScript 严格模式、统一脚本 |
| 治理 | EditorConfig、贡献指南、CODEOWNERS、漏洞报告策略、PR 模板、Dependabot |
| 持久化 | PostgreSQL + Flyway 迁移；`users`/`conversations`/`messages`/`wallet_*`/`red_packet_*`/`call_*`/`outbox_events`/`audit_logs` 全量建表 |
| 数据访问 | JdbcClient Repository 层；`UPDATE ... RETURNING` 分配会话序号，`FOR UPDATE SKIP LOCKED` 抢占红包份额 |
| 领域分层 | `InMemoryStore` 单类 907 行拆为 6 个领域服务 + 6 个 Repository，全局 `synchronized` 单锁移除 |
| 认证 | 标准 JWT（JWS/HS256）、`kid` 多密钥轮换、刷新令牌轮换与重放检测、设备撤销与远程下线、登录/刷新/撤销审计 |
| 客户端会话 | 访问令牌临近过期主动刷新、401 被动刷新重试、single-flight 防并发刷新、WebSocket 握手取最新令牌 |
| 限流 | 登录/刷新/发消息/信令/呼叫/发红包/领红包分策略配额；已认证按 userId、未认证按 IP；WebSocket 命令单独限流 |
| 安全响应头 | CSP、X-Content-Type-Options、X-Frame-Options、Referrer-Policy、Permissions-Policy、no-store；HSTS 仅生产下发 |
| 实时连接 | 发送队列背压（替代同步阻塞写）、应用层心跳与半开连接清理、容器帧上限与业务信令上限对齐 |
| 容器化 | 三阶段 Dockerfile（分层提取、非 root、只读根文件系统兼容）、compose 编排、K8s 清单（存活/就绪/启动三探针分离） |
| 可观测性 | Prometheus 指标（含 9 项业务指标）、requestId 与 traceId 进入 MDC、生产输出 ECS 结构化 JSON 日志 |
| 会话 | 单聊/群聊、成员权限、会话摘要与历史消息；未读数由已读游标推导 |
| 消息 | 原生 WebSocket JSON 命令/事件/ACK、客户端幂等键（数据库唯一约束兜底）、会话级 `seq`、100 条升序分页补拉 |
| 多端 | 用户房间/设备房间、同账号多连接事件扇出、其他设备停止响铃 |
| 通话 | 房间创建/接听/拒绝/离开、参与者状态、SDP/ICE 授权转发、超时收敛 Worker |
| WebRTC | Mesh PeerConnection、本地/远端媒体、静音、摄像头开关、轨道清理 |
| 红包 | 复式记账（用户 ↔ 托管账户）、等额/随机预分配、并发领取、过期退款 Worker、对账查询 |
| 客户端 | 登录、导航、会话列表、消息、红包、来电与通话全屏界面 |
| 质量 | 48 个 Java 测试（含 4 个真实多线程并发测试）、JaCoCo 覆盖率门禁、ESLint、Web/契约单元测试、k6 压测脚本 |
| CI 门禁 | lint → typecheck → test（含覆盖率）→ build；gitleaks 密钥扫描、CodeQL（Java + TS）、依赖审计、SBOM、Trivy 镜像扫描 |
| 安全闸门 | `prod` profile 禁用演示登录与演示种子数据，强制至少 32 字符且非演示密钥；RTC 设备路由按用户与设备联合隔离 |

### 本轮持久化改造的关键决策

- **选择 JdbcClient 而非 JPA**：红包领取依赖 `FOR UPDATE SKIP LOCKED` 与条件更新，需要对 SQL 和锁行为的精确控制。
- **复式记账**：红包资金在"已发出未领取"期间挂在系统托管账户，使任意时刻全部账户余额之和恒定，可独立对账（见 `WalletRepository#findUnbalancedTransactions`）。
- **过期退款改为 Worker 驱动**：迁移前只有当有人恰好访问红包时才触发检查，没人访问就永远不退款。
- **集成测试使用真实 PostgreSQL**：项目依赖 `SKIP LOCKED`、`RETURNING`、jsonb 和部分索引等专有语义，用 H2 测试等于没测生产会跑的 SQL。

## 部分完成

| 能力 | 当前状态 | 下一步 |
| --- | --- | --- |
| 可靠事件 | `outbox_events` 表与写入路径已就绪，领域变更与事件同事务落库 | 接 Redis Pub/Sub 驱动跨实例推送，取代当前的进程内直接扇出 |
| 多实例部署 | 数据层已无状态化 | WebSocket 连接注册表仍在 JVM 内存，需迁移到 Redis 并配置粘性路由 |
| 断线恢复 | 指数退避重连；按各会话本地最大 `seq` 分页补拉并去重合并 | 同步水位持久化、缺口指标和跨实例恢复测试 |
| 设备会话 | 刷新令牌轮换 + 宽限期 + 重放检测；`DELETE /api/v1/sessions/current` 与 `/sessions` 支持单设备与全设备下线 | 设备列表接口、管理员强制下线、过期设备记录清理任务 |
| 密钥管理 | 支持多密钥并存与 `kid` 轮换，配置校验在启动时拒绝弱密钥 | 接入 KMS/Secret Manager，实现自动轮换而非手工切换环境变量 |
| 红包风控 | 金额整数化、数据库 CHECK 约束、唯一幂等键、不可变流水 | 限额策略、冻结、审计查询与定时对账任务 |
| 通话弱网 | ICE candidate、连接失败提示 | ICE restart、质量 stats、TURN |
| 多人通话 | 最多 6 人 Mesh 契约与 Peer 管理 | 多浏览器矩阵压测；生产接 SFU |

## 尚未实现（M2/M3）

- Redis、跨实例 WebSocket 广播与 Outbox 消费端。
- 分布式限流（当前配额按实例独立计算，N 个实例的实际总配额是 N 倍）。
- 好友申请、群成员完整管理、撤回、置顶、免打扰。
- 图片/文件对象存储、语音消息、搜索与离线推送。
- TURN/SFU、屏幕共享、移动端和 Electron 客户端。
- 管理后台、内容审核、举报、风控和合规工作流。
- 真实资金能力；当前明确禁止接入真实支付。

## 验证记录

本轮（容器化与可观测性）已执行：

- `pnpm typecheck` / `pnpm test`（44 个 Java 测试）/ `pnpm build` 全部通过。
- `docker compose --profile full up -d --build`：镜像构建成功，就绪探针转为 healthy。
- **容器化端到端冒烟**（首次真实链路验证，非仅单元测试）：
  - 探针分离：`/actuator/health/liveness` 与 `/readiness` 各自返回 UP
  - 安全头：CSP / nosniff / DENY / no-referrer / Permissions-Policy / no-store 均下发，非生产环境不下发 HSTS
  - 登录签发标准 JWT（三段式，头部含 `kid=k1`、`alg=HS256`）
  - 会话列表未读数由已读游标正确推导（群聊显示 2）
  - 红包全链路：发放 → 幂等重发不重复扣款 → 领取 → 状态 `exhausted`
  - 越权拦截：非会话成员领取返回 403 `NOT_A_MEMBER`
  - 刷新令牌轮换生效；宽限期内重放旧令牌被放行（多标签页容忍）
  - 登出后访问令牌立即失效（HTTP 401），而非等待自然过期
  - 登录限流在配额耗尽后返回 429 并带 `Retry-After`
  - **账务对账：借贷不平衡交易数 0，全部账户余额之和恒为 150000 分，托管账户归零**
  - 日志携带 `[应用名, traceId, spanId, requestId]`，可端到端关联
  - **容器重启后钱包余额保持 49900/50100，数据确实持久化**

上一轮（M1 MVP）验证记录：

- API 冒烟：Alice 发 100 分单聊红包，Bob 领取；红包状态 `exhausted`，余额分别为 49,900/50,100 分。
- HTTP 边界：未认证 API 返回统一 JSON 401，CORS `OPTIONS` 预检返回 200。
- 浏览器冒烟：登录、工作台、WebSocket 在线、¥500.00 钱包余额和实时消息回显通过，控制台无 error/warning。
- 兼容性：同时支持 `localhost:5173` 与 `127.0.0.1:5173` 两种本地来源。

音视频媒体的最终质量取决于摄像头/麦克风权限和网络拓扑；已覆盖房间/信令契约与界面实现，生产验收仍需按 RTC 文档完成双设备、TURN 和 3/6 人矩阵。

### 并发正确性验证

红包领取的四条不变量已由真实多线程测试覆盖（`RedPacketConcurrencyTest`）：

| 场景 | 断言 |
| --- | --- |
| 3 人同时抢 3 份 | 恰好全部领走，金额之和精确等于面额 |
| 同一人并发提交 8 次 | 只入账一份，`red_packet_claims` 仅一条记录 |
| 2 人抢 1 份 | 只有一人成功，另一人得到明确的业务拒绝 |
| 并发发红包且余额仅够一笔 | 只有一笔成功，余额不为负 |

每个场景结束后都额外断言全部账户余额之和不变、且不存在借贷不平衡的交易。
测试用 `CountDownLatch` 让线程尽量同时发起请求，否则线程池启动开销会把它们错开，
真正的竞争窗口就测不到。

### 覆盖率现状

| 计数器 | 覆盖率 |
| --- | --- |
| 行 | 62.8% |
| 分支 | 45.3% |
| 方法 | 65.6% |

门禁设为行 55% / 分支 40%——低于当前水平，用途是防回退而非追求数字。
阈值定得过高会诱导为覆盖率而写无断言的测试。

> **仍未覆盖**：浏览器端 E2E（Playwright）尚未搭建，WebRTC 通话与 UI 交互仍依赖手工验收。
