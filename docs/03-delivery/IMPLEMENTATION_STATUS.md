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
| 4 | 限流、安全响应头、WebSocket 健壮性 | ⬜ 未开始 |
| 5 | 容器化与可观测性 | ⬜ 未开始 |
| 6 | 测试补强与 CI 安全门禁 | ⬜ 未开始 |

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
| 会话 | 单聊/群聊、成员权限、会话摘要与历史消息；未读数由已读游标推导 |
| 消息 | 原生 WebSocket JSON 命令/事件/ACK、客户端幂等键（数据库唯一约束兜底）、会话级 `seq`、100 条升序分页补拉 |
| 多端 | 用户房间/设备房间、同账号多连接事件扇出、其他设备停止响铃 |
| 通话 | 房间创建/接听/拒绝/离开、参与者状态、SDP/ICE 授权转发、超时收敛 Worker |
| WebRTC | Mesh PeerConnection、本地/远端媒体、静音、摄像头开关、轨道清理 |
| 红包 | 复式记账（用户 ↔ 托管账户）、等额/随机预分配、并发领取、过期退款 Worker、对账查询 |
| 客户端 | 登录、导航、会话列表、消息、红包、来电与通话全屏界面 |
| 质量 | Testcontainers 真实 PostgreSQL 集成测试、Web/契约单元测试、类型检查、生产构建、GitHub Actions CI |
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
- 全链路限流、CSP/HSTS 等安全响应头。
- Micrometer 指标、OpenTelemetry 链路、结构化日志。
- 好友申请、群成员完整管理、撤回、置顶、免打扰。
- 图片/文件对象存储、语音消息、搜索与离线推送。
- TURN/SFU、屏幕共享、移动端和 Electron 客户端。
- 管理后台、内容审核、举报、风控和合规工作流。
- 真实资金能力；当前明确禁止接入真实支付。

## 验证记录

本轮（持久化改造）已执行：

- `pnpm typecheck`：Spring Boot 编译、Web 客户端和 TypeScript 契约通过。
- `pnpm test`：Java 集成测试基于 Testcontainers 真实 PostgreSQL 运行。
- `pnpm build`：Spring Boot 可执行 JAR、TypeScript 契约和 Web 生产构建通过。

上一轮（M1 MVP）验证记录：

- API 冒烟：Alice 发 100 分单聊红包，Bob 领取；红包状态 `exhausted`，余额分别为 49,900/50,100 分。
- HTTP 边界：未认证 API 返回统一 JSON 401，CORS `OPTIONS` 预检返回 200。
- 浏览器冒烟：登录、工作台、WebSocket 在线、¥500.00 钱包余额和实时消息回显通过，控制台无 error/warning。
- 兼容性：同时支持 `localhost:5173` 与 `127.0.0.1:5173` 两种本地来源。

音视频媒体的最终质量取决于摄像头/麦克风权限和网络拓扑；已覆盖房间/信令契约与界面实现，生产验收仍需按 RTC 文档完成双设备、TURN 和 3/6 人矩阵。

> **待补的关键测试**：红包并发领取的多线程测试尚未编写（计划在第 6 阶段补齐）。当前正确性依赖数据库唯一约束和 `SKIP LOCKED`，但缺少自动化验证。
