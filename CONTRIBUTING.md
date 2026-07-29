# 贡献指南

## 环境要求

| 工具 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 21+ | 服务端以 Java 21 为编译目标 |
| Maven | 3.9+ | 服务端构建 |
| Node.js | 22+ | Web 客户端与契约包 |
| pnpm | 10.12.4 | 由 `packageManager` 字段锁定，请勿混用 npm/yarn |

另需 **Docker**：本地 PostgreSQL 与集成测试的 Testcontainers 都依赖它。

## 本地启动

```bash
docker compose up -d   # PostgreSQL
pnpm install
pnpm dev               # 同时启动 Spring Boot (:3001) 与 Vite (:5173)
```

服务端启动时由 Flyway 自动建表并写入演示数据。

也可以分别启动：

```bash
pnpm dev:server
pnpm dev:web
```

## 提交前必须通过

```bash
docker compose up -d   # 集成测试需要 PostgreSQL
pnpm lint
pnpm typecheck
pnpm test              # 含 JaCoCo 覆盖率门禁
pnpm build
```

CI 会执行同样的命令，本地不通过不要提 PR。

CI 另有三条独立流水线：安全扫描（gitleaks 密钥扫描、依赖审计、SBOM）、
CodeQL 静态分析、镜像构建与 Trivy 扫描。

### 压测

```bash
docker compose --profile full up -d --build
k6 run load-test/red-packet.js
```

压测不在 CI 中运行——它需要独立且可复现的环境，放进每次 PR 只会带来不稳定的失败。

## 工程结构

```text
apps/web                 React + TypeScript + Vite 客户端
apps/server              Java 21 + Spring Boot 3.5 服务端
packages/contracts       两端共享的 TypeScript 协议类型
docs                     PRD、架构、专项设计与验收文档
```

契约包 `packages/contracts` 被 Web 客户端直接依赖，服务端通过 Java Records + Bean Validation 实现同一套 JSON 契约。**改动任何字段都必须同时更新两侧**，否则会在运行时产生静默的契约漂移。

## 分支与提交

### 分支命名

```text
feat/<简短描述>      新功能
fix/<简短描述>       缺陷修复
refactor/<简短描述>  重构
docs/<简短描述>      文档
chore/<简短描述>     构建、CI、依赖
```

不要直接向 `main` 推送，所有变更走 PR。

### 提交信息

采用 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)：

```text
<type>(<scope>): <描述>

[可选正文]

[可选脚注]
```

`type` 取值：`feat` / `fix` / `docs` / `refactor` / `test` / `chore` / `perf` / `build` / `ci`。

`scope` 建议使用模块名：`server` / `web` / `contracts` / `auth` / `message` / `call` / `wallet` / `docs`。

示例：

```text
feat(wallet): 红包过期退款改为后台 Worker 驱动
fix(realtime): 修正 WebSocket 断连后会话注册表未清理的问题
```

破坏性变更在脚注中声明 `BREAKING CHANGE: <说明>`，并同步更新 `CHANGELOG.md`。

## 代码风格

- **Java**：4 空格缩进，行宽 120。公开类和非平凡方法写 Javadoc，说明"为什么"而不是复述代码。
- **TypeScript**：2 空格缩进。`tsconfig.base.json` 已开启 `strict`、`noUncheckedIndexedAccess`、`exactOptionalPropertyTypes`，不要用 `any` 或 `@ts-ignore` 绕过（ESLint 会拒绝）。
- **注释与文档统一使用中文**，与现有代码保持一致。
- 缩进与换行由 `.editorconfig` 约束，请确保编辑器已启用。

## 测试要求

参考 [ROADMAP.md §5 Definition of Done](./docs/00-product/ROADMAP.md)，一个功能被视为完成需要同时满足：

- 产品规则、错误分支和权限已明确
- API/事件契约已更新，客户端和服务端类型一致
- 核心逻辑包含成功、失败、重试/并发测试
- 日志不泄露令牌、消息敏感内容或完整账务信息
- 用户可见的加载、空态、断线和错误状态已实现
- README/运维说明足够让另一位开发者复现

**涉及账务（钱包、红包）和鉴权的改动必须附带并发测试或权限边界测试**，这是项目风险最高的两条链路。
参考 `RedPacketConcurrencyTest`：用真实线程池并发提交，并在结束后断言资金守恒与借贷平衡。
单线程测试覆盖不到"两个事务同时读到相同状态"的窗口，而缺陷恰恰出在那里。

## 安全要求

提交前请对照 [SECURITY.md](./docs/02-features/SECURITY.md) 自检：

- 每次访问会话、消息、通话或红包都以服务端成员关系为准，不信任客户端传入的角色。
- 所有 HTTP/WebSocket DTO 经过 Bean Validation，并设置正文、数组、SDP/ICE 的大小上限。
- 密钥、令牌不得写入仓库、前端包、镜像层或日志。
- 金额一律使用整数（分），配合事务、唯一幂等键和不可变流水。

发现安全问题请勿直接提 issue，按 [漏洞报告策略](./.github/SECURITY.md) 私下报告。

## 当前阶段说明

数据已持久化到 PostgreSQL，认证、限流、可观测性和容器化均已落地，
详细进度见 [IMPLEMENTATION_STATUS.md](./docs/03-delivery/IMPLEMENTATION_STATUS.md)。

新增功能前请先确认改动不会加深下列**尚未解决**的技术债：

- **WebSocket 连接注册表位于 JVM 内存**——这是当前阻塞多实例部署的头号问题。
  新增实时事件时请通过既有的 `emitToUser` / `emitToConversation` 扇出，
  不要另建进程内状态，否则迁移到 Redis Pub/Sub 时需要改的地方会更多。
- **限流配额是进程内的**——多实例下实际总配额会翻倍。新增限流策略时
  统一走 `RateLimiter`，切换到 Redis 后端时调用方无需改动。
- **Outbox 表已就绪但尚未驱动推送**——领域事件请继续写入 `outbox_events`，
  即使当前是进程内直接扇出，将来切换消费端时才不必回头补写。

在这三项解决之前，`deploy/k8s/deployment.yaml` 的 `replicas` 必须保持为 1：

- **WebSocket 连接注册表仍在 JVM 内存**——事件扇出已通过 Redis Pub/Sub 解决，但新连接路由仍需负载均衡层配置粘性路由（sticky session）。新增实时事件时请通过 `RealtimeBroadcastService.broadcastToXxx()` 而不是直接调用 `RealtimeWebSocketHandler`，以确保跨实例投递。
- **Outbox Worker 只处理消息和红包类事件**——新增领域事件类型时，同步在 `OutboxPublisherWorker.routeEvent()` 中添加路由逻辑，否则 Outbox 的补偿路径不会覆盖该类型。
- **密钥管理**：JWT 多密钥轮换已支持，但仍来自环境变量，未接入 KMS/Secret Manager。
