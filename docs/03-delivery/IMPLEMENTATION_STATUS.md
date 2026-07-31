# 当前实现状态

> 基线日期：2026-07-30
> 版本：M1 MVP + 生产化改造进行中 / v0.1

## 生产化改造进度

按 [ROADMAP](../00-product/ROADMAP.md) 的升级闸门推进，当前进度：

> **✅ 的判定标准**：该能力在 `prod` profile 下可用且有自动化测试覆盖，
> 而不是"本轮计划内的任务已做完"。按后者标记会出现"认证体系 ✅ 完成"
> 与"prod 下无法登录"同时成立——那正是第 7 轮修复的第一个问题。

| # | 阶段 | 状态 |
| --- | --- | --- |
| 1 | git 基线与仓库治理 | ✅ 完成 |
| 2 | PostgreSQL 持久化与领域服务拆分 | ✅ 完成 |
| 3 | 会话管理（标准 JWT、刷新轮换、撤销） | ✅ 完成 |
| 4 | 限流、安全响应头、WebSocket 健壮性 | ✅ 完成 |
| 5 | 容器化与可观测性 | ✅ 完成 |
| 6 | 测试补强与 CI 安全门禁 | ✅ 完成 |
| 7 | 身份验证与正确性修复 | ✅ 完成 |
| 8 | 投递可靠性、降级可达性与容量治理 | ✅ 完成 |
| 9 | 告警、SLO、备份与恢复演练 | 🟡 规则已用真实 Prometheus 验证；告警未在故障下触发过 |
| 10 | E2E 与容量压测 | 🟡 E2E 完成；容量压测脚本就绪未执行 |
| 11 | 多实例真实验证 | ✅ 完成（17 项检查，发现并修复一个致命缺陷） |
| 12 | 会话创建与成员管理 | ✅ 完成（补上主流程缺失的一整段，见下） |

## 第 12 轮：主流程缺了一整段

本轮起于对"距离企业级生产还有哪些差距"的又一次核查。这次发现的头号问题不是
某个能力不够完善：

**服务端没有任何创建会话的接口。** `grep 'INSERT INTO conversations'` 在生产代码里
零命中——只有演示种子和测试在写。而种子在 `prod` profile 下不加载。于是生产环境
注册出来的账号打开空会话列表、无法与任何人建立对话、**一条消息都发不出去**。

**当时 149 个 Java 测试与 14 个 E2E 全部通过。** 它们用的都是种子里已存在的
Alice/Bob/Carol 和已存在的会话，没有任何一条断言覆盖"一个全新账号能做什么"。

这比第 8 轮那类"看起来实现了、实际不可达"更严重：能力压根没写，而演示数据
把它完整地盖住了。文档也没能揭示——`API_AND_EVENTS.md` 里
`POST /conversations/direct` 和 `/group` 早就写着，它们只是从未被实现。

### 本轮抓到的三个缺陷

| 缺陷 | 为什么此前不可见 |
| --- | --- |
| **没有创建会话的写路径** | 演示种子提供了会话，所有测试都用它 |
| **`UUID.compareTo` 与 PostgreSQL uuid 排序不一致**（单聊唯一键顺序） | 种子用户 ID 是 `1111…`/`2222…`，首字节远小于 0x80，两种比较结果相同。随机 UUID 才会以约一半概率暴露 |
| **消息幂等在并发重发下抛 500**（`25P02 current transaction is aborted`） | 单线程重发在预检查就命中，走不到 catch 分支 |

三者的共同形状与第 9 轮相同：**代码/文档看起来完备，测试全绿，而能力实际不存在
或不正确**。能发现它们的方式也相同——用真实的、不受演示数据庇护的形态去跑。

因此新增的测试刻意<b>不使用演示种子</b>：`conversations.spec.ts` 的 5 个 E2E 与
`ProductionBootstrapIntegrationTest` 的 3 个用例全部从注册全新账号开始。

### 顺带修复的"实现了但不可达"

- **已读游标**：`advanceReadSeq` 早就实现，但没有任何调用方——没有 REST 端点，
  前端也不调。未读数由 `next_seq - 1 - read_seq` 推导，因此只增不减。
  补上 `POST /conversations/:id/read` 与前端上报。
- **`role` 字段**：`MEMBER/ADMIN/OWNER` 自建表起就有，零代码引用。
  权限矩阵现由 `MemberRole` 统一判定并逐格断言。

仍处于同一状态的预留字段（已在 `DATA_MODEL.md` 逐字段标注）：
`conversation_members.muted`、`pinned_at`、`messages.status = 'RECALLED'`、
`messages.type` 的 `image`/`file`。

## 第 9 轮：把"配置就绪"变成"实测通过"

第 8 轮交付了大量配置与脚本，但它们的状态是"写好了"而非"验证过"。这一轮把三项
标着「未验证」的工作真的跑起来。**三次验证各抓到一个此前不可见的缺陷**——
这是"实测"与"写好"之间差距的具体证据。

### 多实例验证（17 项检查通过）

`load-test/verify-multi-instance.mjs` 起两个实例共享 PostgreSQL/Redis，
用真实 WebSocket 验证：双向跨实例投递、恰好一次、在线状态双向可见、
消费组独立、限流配额共享、单实例停机不影响另一实例。

**抓到的缺陷：跨实例投递完全不工作。**

`RedisConfig` 里把 Lettuce 命令超时设为 1 秒（为了让降级快速失败），
同时用了 `TimeoutOptions.fixedTimeout`。而事件流消费者用
`XREADGROUP ... BLOCK 2000`——`fixedTimeout` 覆盖了 Lettuce 对阻塞命令的
超时推导，于是每一轮消费都在 1 秒时 `QueryTimeoutException`。

**单实例下这个故障完全不可见**：消费者收到的都是自己发的事件
（`originInstanceId` 相同直接跳过），消费失败不影响任何功能，
因为本地投递在发布时已同步完成。146 个测试全绿，而跨实例投递是死的。

修复过程中又踩了第二次：改成 `timeoutCommands(true)` 会抛
`TimeoutSource is required for enabled timeouts`，应用直接起不来。
最终方案是**完全不碰 `timeoutOptions`**，把保障放在配置约束上——
`stream-block-millis` (500ms) 必须小于 `command-timeout-millis` (1000ms)，
且消费者在启动时校验这个关系。

> 两次踩坑的共同教训：**对第三方库行为的断言必须实测**。
> 前两版的注释都写得很确定，而两次都是错的。

### 首次恢复演练（9 项检查通过）

生成真实备份并恢复到隔离容器，验证迁移历史完整、账务借贷平衡、
账户余额与最新流水一致、无孤立记录、红包计数一致。

**抓到两个脚本缺陷**：演练端口与 e2e profile 冲突（`port is already allocated`）；
迁移历史检查取到了 `R__demo_seed` 那条记录（可重复迁移的 `version` 是 NULL），
显示"Flyway 版本：无"——看起来像备份丢了迁移历史。顺带把这一项从
"展示信息"升级为**断言**：备份不含迁移历史时，恢复出的库对 Flyway 来说是
"有表但无记录"，下次启动会尝试重新应用 V1 并失败。

**局限已记录在案**：数据量只有 11 条消息，RTO 实测值不具代表性；
备份未经过对象存储链路。

### 告警规则实测（12 项检查通过）

`deploy/observability/verify-rules.sh` 提取 PrometheusRule CRD 为原生格式、
跑 `promtool check rules`、起真实 Prometheus 加载规则并抓取运行中的实例、
主动产生业务流量、确认记录规则真的算出数值。

**抓到的缺陷：延迟 SLI 一直是死的。**

Spring Boot 默认把 `http_server_requests_seconds` 发布为 **summary**
（只有 count/sum/max），**不含任何 bucket**——而 `histogram_quantile()` 需要 bucket。
因此以下全部静默失效：SLO 的延迟目标（p99 < 500ms）、`OpenIMHighLatency` 告警、
Grafana 的两个延迟面板、两条延迟记录规则。

它们的 PromQL 语法完全正确、`promtool` 校验通过、Prometheus 加载无错——
**只是永远返回空**。`AlertingContractTest` 也发现不了：它按<b>名字</b>比对指标，
而 `http_server_requests_seconds` 这个名字在 summary 形式下同样存在。

修复是启用 `management.metrics.distribution.percentiles-histogram`，
并把 SLO 边界配成专属 bucket（默认对数刻度在 500ms 附近精度不足）。
新增 `HttpLatencyHistogramTest` 断言**类型是 histogram**而非仅断言名字存在。

脚本本身也修了两处：一次性打完流量导致 `rate()` 趋于 0、SLI 算出 NaN
（改为后台持续产生流量）；以及把 NaN 当成"有值"通过——那正是这个脚本
本该防止的那种假绿。

### 这三次验证的共同点

抓到的三个缺陷有同一个形状：**代码/配置看起来完备，语法正确，测试全绿，
而能力实际不存在**。它们都无法通过增加单元测试发现，因为：

- 跨实例投递的失败在单实例下没有症状
- 备份脚本的缺陷只在有 e2e 容器/有真实迁移历史时显现
- 延迟指标的缺失在"按名字比对"的检查下完全隐形

唯一能发现它们的方式是**把系统真的按生产形态跑起来，然后观察**。

## 第 8 轮：投递可靠性、降级可达性与容量治理

本轮起于一次对"距离企业级生产还有哪些差距"的独立核查。核查发现的问题
**全部位于此前标记为已完成的能力内部**，且有三个是"看起来实现了、实际不可达"
这一类——它们比缺失的功能更危险，因为代码的存在会让人相信能力也存在。

### 修复的核心缺陷

| 问题 | 现象 | 修复 |
| --- | --- | --- |
| **投递语义实际是 at-most-once** | 注释写"至少一次"，但通道是 Redis Pub/Sub——无持久化、无确认、无重放。`convertAndSend` 返回即 `markPublished`，此刻某实例订阅器若在重连就永久错过该事件。且去重在**发布端**做，主路径抢到键后 Outbox 补偿被整个丢弃——补偿路径从未补偿过任何东西 | 通道换为 **Redis Streams**：每实例一个独立消费组（广播语义）、`XACK` 确认、`XAUTOCLAIM` 重投超时未确认的记录。去重下沉为**进程内**判断（"我这个实例投过了没有"才是它该回答的问题），不再依赖 Redis |
| **降级代码全部不可达** | 就绪探针包含 Redis。Redis 是所有实例共用的单点，它一挂，**每个** Pod 的就绪探针同时失败、Service 端点清空、整站 503。限流降级、去重放行、presence 兜底这些分支只在"Redis 挂了但服务仍在处理请求"时执行——而那一刻流量已被摘掉 | 就绪探针只保留数据库。Redis 状态改由 `openim_redis_available` 指标暴露，交给告警而非负载均衡器处置 |
| **降级是"慢速挂死"而非"快速失败"** | `RedisConfig` import 了 `ClientOptions`/`TimeoutOptions` 却**从未使用**——看起来配过超时，实际生效的是 Lettuce 默认值：命令超时 60 秒、断连期间命令排队等重连。Redis 挂掉后每个请求阻塞整整一分钟才走降级分支，线程被长期占住 | 命令超时降到 1 秒，`disconnectedBehavior = REJECT_COMMANDS`。**这个缺陷只有停掉真实 Redis 才会显现**——单元测试注入的是立即抛出的异常，而真实故障的形态是超时 |
| **鉴权每请求打一次库** | `AuthenticationInterceptor` 对每个请求执行 `isSessionActive()`。Hikari 池 20 连接，这是先于任何业务逻辑撞上的容量墙 | Caffeine 缓存 + 撤销时跨实例失效广播。失效注册在**事务提交后**——在事务内失效会让其他实例回源读到未提交的旧值并重新缓存，反而延长陈旧窗口 |
| **群消息扇出放大** | `emitToConversation` 每次查库解析成员：*每条群消息 × 每个实例* 一次查询 | 带失效的成员缓存。**鉴权路径刻意不走缓存**——扇出用陈旧成员表只是多推一条消息，鉴权用陈旧成员表是越权 |
| **迁移在有数据的库上失败** | V7 分区迁移先把数据插进 DEFAULT 分区再建当月分区，PostgreSQL 会因"DEFAULT 里有属于新分区的行"而拒绝。空库上通过、有数据的库上必然失败——而集成测试用的全是每次全新的 Testcontainers 库 | 调整顺序：先建全部目标月分区再插数据。新增 `MigrationOnPopulatedDatabaseTest`，在**有数据**的库上跑迁移 |
| **指标延迟注册** | `openim_auth_login_failures` 只在首次登录失败时才注册。服务刚启动时该序列**不存在**，于是 `failures / (success + failures)` 整体返回空——告警永远不触发，Grafana 一片空白，而"零失败"与"指标没接上"无法区分 | 预注册全部 reason 取值，使序列从启动起就存在且值为 0 |
| 日志占位符缺失 | `"...sessionId={} userId="` 传了两个参数但只有一个占位符，userId 永远不打印——而排查半开连接正需要它 | 补上 `{}` |

### 修正了自己先前的判断

核查报告里写过"`messages` 表无分区"并把它列为差距。深入看之后这个判断是**错的**，
分区表反而不该建：

- 消息查询全部是 `WHERE conversation_id = ? AND seq > ?`，走 `idx_messages_conversation_seq`。
  按时间分区后一次历史拉取要跨多个分区，条件里没有时间，分区裁剪帮不上忙——性能只会变差。
- IM 用户期望消息永久保留，`DROP PARTITION` 这个最大收益在这里没有场景。
- 分区表主键必须含分区键 `(id, sent_at)`，而 `red_packets.message_id` 引用 `messages(id)`——
  单列外键无法指向复合主键。要分区就得放弃这条数据库级完整性保证。

真正该分区的是 `audit_logs`：按时间查、按时间删、无外键引用。已按月分区。
详细论证写在 `V7__audit_log_partitioning.sql` 的头部注释里，防止后人"顺手补上"。

### 新增能力

| 能力 | 实现 |
| --- | --- |
| 密码找回 | `password_reset_tokens` 表（只存 SHA-256 哈希）、申请与确认两个端点、令牌单次使用（`UPDATE ... WHERE used_at IS NULL` 的行数判定并发）、账号枚举防护（已注册与未注册返回完全一致的 202）、重置后撤销全部会话并**解除账号锁定**（否则"账号已锁定，请重置密码"是一句空话）。送达渠道抽象为 `PasswordResetNotifier`；prod 下若只有日志兜底实现则**拒绝启动** |
| 账号注销 | 匿名化而非物理删除（`users` 被 14 处外键引用，硬删除会破坏账务完整性或让他人会话出现空洞）。清除用户名/昵称/邮箱/凭证/设备/重置令牌；保留账务流水、消息正文、审计记录。余额非零时拒绝注销 |
| 业务审计 | 红包发放/领取/退款写 `audit_logs`。此前资金操作只有 outbox（有保留期、会被清理）和聚合指标（不含单笔明细），无法回答"这笔钱是谁在什么时候动的" |
| IP 哈希 | `audit_logs.ip_hash` 列自建表起就存在但**从未被写入**。补上 `ClientIpHasher`，带部署级盐值——无盐的 IP 哈希毫无意义，IPv4 只有 2³² 个值可完全枚举反查 |
| 数据保留期 | `outbox_events`（7 天）、`password_reset_tokens`（7 天）、`audit_logs`（按月分区，默认 6 个月但**删除默认关闭**——审计数据的保留期由合规决定，不该由默认开启的定时任务执行） |
| 连接容量闸门 | 单实例上限 10000，超出以 1013 `SERVICE_OVERLOAD` 拒绝。此前无任何上限，结果是 OOMKill（该实例全部连接一起断）而非优雅拒绝一条 |
| OpenAPI 规范 | springdoc 从控制器实际签名生成，24 个路径 / 36 个 schema。用 `-api` 而非 `-ui`——Swagger UI 是额外的信息泄漏面。生产关闭端点，规范由 CI 导出为构建产物 |
| 告警与 SLO | 24 条告警规则（多窗口多燃烧率）、SLI 记录规则、SLO 定义与错误预算政策、Grafana dashboard（19 面板）、值班手册（每条 critical 都有处置步骤） |
| 备份 | K8s CronJob 每日全量 + 对象存储 + 产物校验；恢复演练脚本（含账务不变量校验）。**RPO 仍是 24 小时**——PITR 需要 WAL 归档，未配置 |
| E2E | Playwright 22 个用例，覆盖跨客户端实时投递、断线重连补拉、红包资金流、会话创建与群成员管理、消息历史分页。独立的 e2e 数据库（隔离 + 绕开宿主 PostgreSQL 端口冲突）。`conversations.spec.ts` 的用例全部从**注册全新账号**开始，不使用演示种子——种子会话正是掩盖了“prod 下建不了会话”那个缺陷的东西 |
| 故障注入 | `chaos-degradation.sh` 停掉真实 Redis 验证降级路径。12 项检查全部通过——**这是"降级真的可用"的唯一证据**，Lettuce 超时那个缺陷就是它发现的 |
| 前端健壮性 | ErrorBoundary（两层：根 + 消息列表）、消息列表窗口化（此前全量渲染）、日期分隔符按实际日期（此前硬编码"今天"，补拉的历史消息全被标成今天）、补齐按钮 `aria-label`（此前"发送红包"对屏幕阅读器只读出"¥"） |
| 链路导出 | OTLP exporter。**注意**：endpoint 刻意不在配置文件里给默认值——空字符串会让 Spring Boot 装配导出器然后校验失败，整个应用起不来。这是实测踩到的 |

第 3 轮原标题为"认证体系"，现更正为"会话管理"：它交付的是令牌签发、轮换与
撤销，不含身份验证。真正的凭证校验在第 7 轮完成。

## 第 7 轮：身份验证与正确性修复

本轮修复的四个问题都位于此前被标记为"已完成"的能力内部，
说明它们不是遗漏的待办，而是没有被验证过的假设。

| 问题 | 现象 | 修复 |
| --- | --- | --- |
| `prod` 下无任何登录入口 | `demo-login` 被 profile 禁用，`/refresh` 需要已有令牌，因此无法获得第一个令牌；`users` 表也没有凭证列 | 新增 `user_credentials` 表、`/auth/login`、`/auth/register`、`/sessions/password`；BCrypt cost 12 |
| 会话事件路由用连接时快照 | `conversationSessions` 只在连接建立时填充，此后成员变动不反映——新成员收不到消息，已退出成员仍在接收 | 删除该索引，`emitToConversation` 实时查库解析成员后按 `userSessions` 路由 |
| Outbox 补偿路径从未生效 | 载荷不含 `conversationId` 而 Worker 按它路由，因此全部事件被静默跳过；线上事件名用的是 outbox 内部名；载荷结构与主路径不一致 | 载荷补齐 `conversationId`；两条路径统一经 `RealtimeEvent` 工厂构造；Worker 从数据库重读聚合 |
| 限流故障时一律放行 | Redis 异常即 fail-open，登录接口在 Redis 抖动期间完全失去保护；本地降级因 `redisRateLimiter` 永不为 null 而是不可达代码 | `RedisRateLimiter` 返回 `Optional.empty()` 表示无法判定；降级真正走本地令牌桶，凭证类策略配额收紧 4 倍 |

### 本轮发现的隐藏缺陷

集成测试暴露了一个不在原计划内的问题：**登录失败计数与审计写在
`@Transactional` 方法内，而该方法随后抛出 401/403，回滚把计数一并撤销**，
导致账号锁定永远不触发。修复方式与既有的 `DeviceRevocationService` 相同——
计数移入 `LoginAttemptService` 并标注 `REQUIRES_NEW` 独立提交。

这个缺陷只有在"连续失败 N 次后断言状态变为 ACCOUNT_LOCKED"的测试下才会显现，
单看代码每一行都是合理的。

### 关键决策

- **BCrypt 而非 Argon2id**：`SECURITY.md` 未指定算法，而 `Argon2PasswordEncoder`
  依赖 bouncycastle，Spring Boot BOM 不托管其版本。自行 pin 一个加密库意味着
  要独立跟踪它的 CVE；`spring-security-crypto` 内置 BCrypt，版本由 BOM 管理。
  代价是 72 字节输入截断，已在 `PasswordPolicy` 显式拒绝超长密码而非静默截断。
- **不引入 `spring-boot-starter-security`**：它会自动装配一条 Servlet Filter 链，
  与既有的 `AuthenticationInterceptor` 形成两套并行鉴权路径——两处都能放行请求时，
  任一处的疏漏都是漏洞。只取 crypto 模块做哈希。
- **密码/短信/OAuth 的取舍**：`SECURITY.md` 要求生产使用短信或 SSO，
  但两者都需要外部 provider，当前环境无法接入也无法测试。
  凭证存储、失败锁定、审计、限流这套基础设施对三种方式共用，
  后续接入短信/OAuth 是增加一个 identity provider，不是重做。
- **失败计数存数据库而非 Redis**：账号锁定不能建立在会降级放行的存储之上。
  限流可以降级（它防的是滥用），锁定不能（它防的是账号被爆破）。
- **限流降级不做"拒绝一切登录"**：那会把 Redis 抖动放大成完整的认证不可用。
  数据库侧的账号锁定在降级期间仍然生效且不依赖 Redis，针对单账号的爆破由它兜底，
  限流只需拦住横向扫描，因此降级时收紧配额而非封锁。

## 已完成

| 领域 | 实现 |
| --- | --- |
| 工程 | Java 21/Spring Boot 服务端、pnpm Web 工作区、TypeScript 严格模式、统一脚本 |
| 治理 | EditorConfig、贡献指南、CODEOWNERS、漏洞报告策略、PR 模板、Dependabot |
| 持久化 | PostgreSQL + Flyway 迁移，17 张表：`users`/`user_credentials`/`devices`/`conversations`/`messages`/`wallet_*`/`red_packet_*`/`call_*`/`outbox_events`/`audit_logs` |
| 数据访问 | JdbcClient Repository 层；`UPDATE ... RETURNING` 分配会话序号，`FOR UPDATE SKIP LOCKED` 抢占红包份额 |
| 领域分层 | `InMemoryStore` 单类 907 行拆为 6 个领域服务 + 6 个 Repository，全局 `synchronized` 单锁移除 |
| 身份验证 | 用户名/邮箱 + 密码登录（BCrypt cost 12）、注册、改密（验证当前密码并撤销全部会话）、连续失败锁定、账号枚举防护（失败响应统一 + 计时攻击防护）、认证指标与全链路审计 |
| 会话管理 | 标准 JWT（JWS/HS256）、`kid` 多密钥轮换、刷新令牌轮换与重放检测、设备撤销与远程下线、登录/刷新/撤销审计 |
| 客户端会话 | 访问令牌临近过期主动刷新、401 被动刷新重试、single-flight 防并发刷新、WebSocket 握手取最新令牌 |
| Redis 基础设施 | Lettuce 连接池、命令超时 1 秒 + 断连即拒绝（降级快速失败而非慢速挂死）、StringRedisTemplate、事件流消费者（虚拟线程）；**就绪探针刻意不含 Redis**——它是共用单点，放进去会让故障摘掉全部实例并使降级代码不可达 |
| 在线状态（Presence） | Redis SETEX + TTL：连接建立/心跳 pong 续期，断开立即删除，实例崩溃自动过期；批量查询用 pipeline |
| 跨实例事件扇出 | **Redis Streams**（`openim:events:stream`）+ 每实例独立消费组 + XACK + XAUTOCLAIM 重投；originInstanceId 跳过自身；USER/DEVICE/CONVERSATION 三类路由 |
| 分布式限流 | Redis Lua 原子计数器（固定窗口），多实例配额共享；Redis 不可用自动降级为进程内 Bucket4j |
| Outbox 投递 | OutboxPublisherWorker 用 `FOR UPDATE SKIP LOCKED` 锁定后写入 Redis Streams；写流失败会 markFailed 重试（此前 Pub/Sub 下写入失败也标记成功）；补偿主路径的遗漏，且**即使本地已去重也照常写流**——当初没收到的实例正等着它 |
| 限流 | 登录/刷新/发消息/信令/呼叫/发红包/领红包分策略配额；已认证按 userId、未认证按 IP；WebSocket 命令单独限流 |
| 安全响应头 | CSP、X-Content-Type-Options、X-Frame-Options、Referrer-Policy、Permissions-Policy、no-store；HSTS 仅生产下发 |
| 实时连接 | 发送队列背压（替代同步阻塞写）、应用层心跳与半开连接清理、容器帧上限与业务信令上限对齐 |
| 容器化 | 三阶段 Dockerfile（分层提取、非 root、只读根文件系统兼容）、compose 编排、K8s 清单（存活/就绪/启动三探针分离） |
| 可观测性 | Prometheus 指标（业务 + 认证 + 事件流 + 缓存 + 降级信号，全部预注册使零值也可观测）、requestId 与 traceId 进入 MDC、生产输出 ECS 结构化 JSON 日志、OTLP span 导出（注入 endpoint 即启用）、24 条告警规则 + SLO + dashboard + 值班手册 |
| 会话 | 单聊/群聊、成员权限、会话摘要与历史消息；未读数由已读游标推导 |
| 消息 | 原生 WebSocket JSON 命令/事件/ACK、客户端幂等键（数据库唯一约束兜底）、会话级 `seq`、100 条升序分页补拉 |
| 多端 | 用户房间/设备房间、同账号多连接事件扇出、其他设备停止响铃 |
| 通话 | 房间创建/接听/拒绝/离开、参与者状态、SDP/ICE 授权转发、超时收敛 Worker |
| WebRTC | Mesh PeerConnection、本地/远端媒体、静音、摄像头开关、轨道清理 |
| 红包 | 复式记账（用户 ↔ 托管账户）、等额/随机预分配、并发领取、过期退款 Worker、对账查询 |
| 客户端 | 登录、导航、会话列表、消息、红包、来电与通话全屏界面 |
| 质量 | 146 个 Java 测试（含 4 个真实多线程并发测试、有数据库上的迁移测试、告警契约测试）、15 个 Web 单元测试、14 个 Playwright E2E、故障注入脚本、JaCoCo 覆盖率门禁、ESLint、k6 压测脚本（红包/消息吞吐/长连接容量） |
| CI 门禁 | lint → typecheck → test（含覆盖率）→ build；独立的 E2E job（service container 起数据库）；gitleaks 密钥扫描、CodeQL（Java + TS）、依赖审计、SBOM、Trivy 镜像扫描；OpenAPI 规范导出为产物 |
| 安全闸门 | `prod` profile 禁用演示登录与演示种子数据，强制至少 32 字符且非演示密钥；RTC 设备路由按用户与设备联合隔离 |

### 本轮持久化改造的关键决策

- **选择 JdbcClient 而非 JPA**：红包领取依赖 `FOR UPDATE SKIP LOCKED` 与条件更新，需要对 SQL 和锁行为的精确控制。
- **复式记账**：红包资金在"已发出未领取"期间挂在系统托管账户，使任意时刻全部账户余额之和恒定，可独立对账（见 `WalletRepository#findUnbalancedTransactions`）。
- **过期退款改为 Worker 驱动**：迁移前只有当有人恰好访问红包时才触发检查，没人访问就永远不退款。
- **集成测试使用真实 PostgreSQL**：项目依赖 `SKIP LOCKED`、`RETURNING`、jsonb 和部分索引等专有语义，用 H2 测试等于没测生产会跑的 SQL。

## 部分完成

| 能力 | 当前状态 | 下一步 |
| --- | --- | --- |
| 可靠事件 | **Redis Streams + 每实例独立消费组 + XACK**，投递语义为 at-least-once；未确认记录由 XAUTOCLAIM 重投；去重在各实例进程内判断，不依赖 Redis 可用性 | Outbox 事件类型覆盖范围可扩展；当前仅处理消息和红包类事件，通话状态只走主路径 |
| 多实例部署 | **已用两个实例实测 17 项**：双向跨实例投递、恰好一次、在线状态双向可见、消费组独立、限流配额共享、单实例停机隔离。验证过程发现并修复了"跨实例投递完全不工作"（Lettuce 阻塞读超时） | WebSocket **连接注册表**仍在 JVM 内存，新连接需负载均衡层配置粘性路由——**粘性路由本身未实测**（需要真实 Ingress/LB） |
| 告警与 SLO | **已用真实 Prometheus 验证 12 项**：promtool 语法校验、规则被加载无错、抓取可达、记录规则算出数值、健康态不误报。验证发现延迟 SLI 一直是死的（缺 histogram bucket），已修复 | **告警未在真实故障下触发过**——需要注入故障观察状态转 firing，并配置 Alertmanager 与接收端。备份告警额外依赖 kube-state-metrics |
| 备份与恢复 | **首次演练已执行**，9 项检查通过（迁移历史完整性、账务借贷平衡、余额与流水一致、无孤立记录）。演练发现并修复两处脚本缺陷 | **RPO 仍是 24 小时**（需要 WAL 归档）；备份未加密、未跨区域；**演练数据量仅 11 条消息，RTO 实测值不具代表性**；未经过对象存储链路 |
| 容量 | 单实例连接上限、连接池泄漏检测、`statement_timeout`、压测脚本（消息吞吐 + 长连接容量） | **压测未执行**。`max-connections: 10000` 是按"每连接成本很小"估算的，不是测出来的 |
| 身份验证方式 | 用户名/邮箱 + 密码 | 短信/邮箱验证码、OAuth/SSO、MFA、密码重置（找回）流程 |
| 断线恢复 | 指数退避重连；按各会话本地最大 `seq` 分页补拉并去重合并 | 同步水位持久化、缺口指标和跨实例恢复测试 |
| 设备会话 | 刷新令牌轮换 + 宽限期 + 重放检测；`DELETE /api/v1/sessions/current` 与 `/sessions` 支持单设备与全设备下线 | 设备列表接口、管理员强制下线、过期设备记录清理任务 |
| 密钥管理 | 支持多密钥并存与 `kid` 轮换，配置校验在启动时拒绝弱密钥 | 接入 KMS/Secret Manager，实现自动轮换而非手工切换环境变量 |
| 红包风控 | 金额整数化、数据库 CHECK 约束、唯一幂等键、不可变流水 | 限额策略、冻结、审计查询与定时对账任务 |
| 通话弱网 | ICE candidate、连接失败提示 | ICE restart、质量 stats、TURN |
| 多人通话 | 最多 6 人 Mesh 契约与 Peer 管理 | 多浏览器矩阵压测；生产接 SFU |

## 尚未实现（M2/M3）

- 好友申请、群成员完整管理、撤回、置顶、免打扰。
- 图片/文件对象存储、语音消息、搜索与离线推送。
- TURN/SFU、屏幕共享、移动端和 Electron 客户端。
- 管理后台、内容审核、举报、风控和合规工作流。
- 真实资金能力；当前明确禁止接入真实支付。

## 验证记录

本轮（身份验证与正确性修复）已执行 `pnpm lint` / `typecheck` / `test`（87 个 Java 测试）/ `build`
全部通过，覆盖率门禁上调至行 65% / 分支 48% 后仍通过。

**真实链路冒烟**（非仅单元测试，服务端与 PostgreSQL/Redis 实际运行）：

- 凭证登录签发 JWT，令牌可访问受保护接口；邮箱大小写混合（`ALICE@Example.TEST`）正确解析
- **账号枚举防护**：错误密码与不存在账号的响应完全一致（同为 `401 INVALID_CREDENTIALS`，消息相同）
- **账号锁定**：连续 5 次失败后转为 `ACCOUNT_LOCKED`，此后即使密码正确仍拒绝
- 注册 → 自动创建钱包账户 → 改密 → 旧令牌立即 401 → 新密码可登录，全链路通过
- 弱密码（`password123`）被拒；重复用户名返回 `IDENTIFIER_TAKEN` 且不指明冲突字段
- **Outbox 补偿**：事件载荷含 `conversationId`，Worker 扫描后 pending 归零、失败数 0；
  去重键写入 Redis，红包领取产生 `:1:partially_claimed` 与 `:0:published` 两个独立键
  （证明状态变化不会被误判为重复而丢弃）
- **限流降级**：停掉 Redis 后注册接口放行 1 次即返回 429（修复前会全部放行），
  `openim_rate_limit_degraded_total` 与 `degraded_rejections_total` 正确上报
- 账务对账：借贷不平衡交易 0，全部账户余额之和恒为 150000 分
- **`prod` profile 实测**：`demo-login` 返回 404、演示账号不存在（未加载 seed），
  但注册 → 登录 → `/api/v1/me` 全部成功，HSTS 正确下发
  — 这是本轮的核心验证：生产环境从"无法登录"变为"可登录"

> 本地环境注意：若宿主机已有 PostgreSQL 占用 5432，它会优先于 Docker 端口映射被连接，
> 表现为 `role "openim" does not exist`。验证时使用了独立端口的容器规避。

上一轮（Redis 改造）已执行：

- `pnpm test`（55 个 Java 测试）/ `pnpm build` / 覆盖率门禁全部通过。
- `docker compose up -d` 同时启动 PostgreSQL + Redis，服务端就绪探针包含两者。
- Pub/Sub 路由逻辑通过 `RealtimeEventSubscriberTest` 覆盖（6 个用例）。
  *（第 8 轮已用 Redis Streams 取代 Pub/Sub，该测试类随之删除，改由
  `RealtimeEventStreamIntegrationTest` 覆盖——它验证的是 Pub/Sub 做不到的那些保证。）*

上一轮（容器化与可观测性）验证记录：

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
| 行 | 73.2% |
| 分支 | 51.7% |
| 方法 | 74.0% |

门禁设为行 65% / 分支 48%——低于当前水平，用途是防回退而非追求数字。
阈值定得过高会诱导为覆盖率而写无断言的测试。

### 测试构成

| 层次 | 数量 | 覆盖 |
| --- | --- | --- |
| Java 单元 + 集成 | 149（28 个文件） | 领域逻辑、并发不变量、认证、投递、分区、迁移、指标类型 |
| Web 单元 | 15（2 个文件） | 消息合并、日期分隔 |
| E2E（Playwright） | 14（3 个文件） | 跨客户端实时投递、断线重连补拉、红包资金流、认证 |
| 故障注入 | 12 项检查 | Redis 停机时的降级行为 |
| 多实例验证 | 17 项检查 | 跨实例投递、presence、消费组、配额共享、故障隔离 |
| 恢复演练 | 9 项检查 | 备份可恢复性、迁移历史、账务不变量 |
| 告警规则实测 | 12 项检查 | promtool 校验、规则加载、记录规则求值、无误报 |

**几个测试值得单独说明**，因为它们锁定的是容易被"顺手改掉"的东西：

- `AlertingContractTest` —— 比对告警规则与 dashboard 引用的每个指标名与实际注册的指标。
  引用不存在的指标不会报错，只会让告警**永远不触发**，这是监控最危险的失败模式。
- `MigrationOnPopulatedDatabaseTest` —— 在**有数据**的库上跑迁移。其余集成测试都用
  每次全新的 Testcontainers 库，因此迁移只在空库上被验证过，而生产库总是有数据的。
- `RedisTimeoutConfigTest` —— 锁定 Lettuce 的命令超时与断连行为。这两项配置被移除后
  降级会退化为"每个请求慢 60 秒"，而所有现有测试仍然全绿。
- `ConversationMemberCacheExpiryTest` —— 验证缓存在失效广播**丢失**时仍会自愈。
  这是缓存优于连接时快照的关键区别：快照的错误持续到重连，缓存的错误止于一个 TTL。
- `ProductionPasswordResetGuardTest` —— 锁定"prod 下不允许把重置令牌写进日志"。
  这个闸门的价值在于它阻止启动，而阻止启动是最容易被注释掉的那类代码。
- `HttpLatencyHistogramTest` —— 断言 HTTP 延迟以 **histogram** 而非 summary 发布。
  断言类型而非名字是关键：退回 summary 时指标名依然存在，
  所有按名字比对的检查都会通过，而延迟 SLO 与告警已经死了。

> **仍未覆盖**：WebRTC 通话的媒体链路（E2E 用假设备，只验证信令与界面）；
> 多实例部署的真实行为；容量压测（脚本已就绪但未执行，因此
> `max-connections: 10000` 仍是一个未经验证的估算值）。
