# 变更日志

本文件记录 OpenIM 的所有重要变更。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [未发布]

### 新增

- 仓库治理基线：`.editorconfig`、`CONTRIBUTING.md`、`CODEOWNERS`、PR 模板、Dependabot 配置。

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

当前版本为演示基线，**禁止用于生产**：

- 数据、令牌与在线状态保存在单进程内存中，重启不保留。
- 服务端无法多实例部署（WebSocket 连接注册表在 JVM 内存中）。
- 无限流、无标准 JWT 密钥轮换与令牌撤销、无可观测性指标。
- WebRTC 使用 Mesh 与公共 STUN，未部署 TURN/SFU。
- 红包余额为本地虚拟资产，不连接真实支付。

[未发布]: https://github.com/OWNER/open-im/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/OWNER/open-im/releases/tag/v0.1.0
