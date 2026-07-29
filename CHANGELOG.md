# 变更日志

本文件记录 OpenIM 的所有重要变更。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [未发布]

生产化改造：把演示 MVP 推进到具备持久化、可撤销认证、防护、可观测性与部署产物的状态。

### 新增

- **持久化**：PostgreSQL + Flyway，15 张表；复式记账的红包账务，可独立对账。
- **认证**：标准 JWT（JWS/HS256）、`kid` 多密钥轮换、刷新令牌轮换与重放检测、
  设备撤销与远程下线；客户端自动刷新（single-flight）。
- **防护**：按动作分策略的限流、CSP/HSTS 等安全响应头。
- **实时连接**：发送队列背压、应用层心跳与半开连接清理。
- **可观测性**：Prometheus 指标（9 项业务指标）、requestId/traceId 进入 MDC、
  生产输出 ECS 结构化 JSON 日志。
- **部署**：三阶段 Dockerfile、compose 编排、K8s 清单（三探针分离）。
- **质量**：红包并发多线程测试、JaCoCo 覆盖率门禁、ESLint、k6 压测脚本；
  CI 增加 gitleaks、CodeQL、SBOM 与 Trivy 镜像扫描。
- 仓库治理基线：`.editorconfig`、`CONTRIBUTING.md`、`CODEOWNERS`、PR 模板、Dependabot 配置。

### 变更

- **破坏性**：`POST /api/v1/auth/demo-login` 的 `userId` 字段改为 `username`，
  因为用户主键已改为 uuid。
- 访问令牌有效期由 1 小时缩短为 15 分钟，配合客户端自动刷新。
- 本地开发新增 Docker 依赖（PostgreSQL 与 Testcontainers）。

### 修复

- 红包过期退款此前只在有人恰好访问该红包时才触发，没人访问就永远不退款；
  改由 `ExpiryWorker` 定时驱动。
- 令牌重放检测中的设备撤销与随后抛出的 401 处于同一事务，回滚会把撤销一并撤掉，
  导致"检测到攻击但设备依然可用"。
- WebSocket 业务层按 1MB 校验信令载荷，而容器沿用 8KB 默认缓冲区，
  超过 8KB 的 SDP 在到达该校验前即被断连，校验实际从未生效。
- 客户端 `connect_error` 判断比较的是错误文案而非错误码，分支永远不成立。
- 指标名 `openim.red_packets.created` 被 Prometheus 剥离保留后缀后与其他指标撞名。

## [0.1.0] - 2026-07-28

M1 演示 MVP。可运行的类微信即时通信基线，包含 Web 客户端、Spring Boot 服务端、共享类型契约和完整产品/技术文档。

### 新增

- **认证**：三个演示账号、HMAC 签名的访问/刷新令牌、Spring MVC 与 WebSocket 双端鉴权。
- **会话与消息**：种子单聊/群聊、成员权限校验、会话摘要与历史消息；原生 WebSocket JSON 命令/事件/ACK 协议、客户端幂等键、会话级 `seq`、100 条升序分页补拉与去重合并。
- **多端**：用户房间/设备房间映射、同账号多连接事件扇出。
- **音视频**：通话房间创建/接听/拒绝/离开、参与者状态机、SDP/ICE 授权转发；WebRTC Mesh（最多 6 人）、静音与摄像头开关。
- **红包**：单聊定额与群聊等额/拼手气、余额扣减、领取幂等去重、状态广播。
- **安全闸门**：`prod` profile 禁用演示登录，强制至少 32 字符且非演示密钥；RTC 设备路由按用户与设备联合隔离。
- **工程**：pnpm workspace、TypeScript 严格模式、GitHub Actions CI（typecheck / test / build / 依赖审计）。

### 已知限制

- 数据、令牌与在线状态保存在单进程内存中，重启不保留。
- 服务端无法多实例部署（WebSocket 连接注册表在 JVM 内存中）。
- 无限流、无标准 JWT 密钥轮换与令牌撤销、无可观测性指标。
- WebRTC 使用 Mesh 与公共 STUN，未部署 TURN/SFU。
- 红包余额为本地虚拟资产，不连接真实支付。

[未发布]: https://github.com/OWNER/open-im/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/OWNER/open-im/releases/tag/v0.1.0
