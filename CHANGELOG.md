# 变更日志

本文件记录 OpenIM 的所有重要变更。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [未发布]

生产化改造：把演示 MVP 推进到具备持久化、可撤销认证、防护、可观测性与部署产物的状态。

### 会话创建与成员管理（第 10 轮）

本轮起于一次对"距离企业级生产还有哪些差距"的核查。核查发现的头号问题不是某个
能力不够完善，而是**主流程缺了一整段**：

> 服务端<b>没有任何创建会话的接口</b>。`grep 'INSERT INTO conversations'` 在生产代码里
> 零命中——只有演示种子和测试在写。而种子在 `prod` profile 下不加载。于是生产环境
> 注册出来的账号打开的是空会话列表，无法与任何人建立对话，**一条消息都发不出去**。
>
> 当时 149 个 Java 测试与 14 个 E2E 全部通过：它们用的都是种子里已存在的
> Alice/Bob/Carol 和已存在的会话，没有任何一条断言覆盖"一个全新账号能做什么"。
> 这比"看起来实现了、实际不可达"更严重——能力压根没写，而演示数据把它盖住了。

#### 新增

- **会话创建**：`POST /conversations/direct`（幂等，与同一人重复调用返回已有会话）、
  `POST /conversations/group`。
- **成员管理**：成员列表、批量加人、移出、退群、指派/撤销管理员、转让群主、
  修改群资料，以及 `GET /conversations/:id`（单个会话视图）。
- **`role` 字段第一次真正生效**。`conversation_members.role` 自建表起就有
  `MEMBER/ADMIN/OWNER` 三个取值，但此前**没有一行代码读它**。权限矩阵收敛到
  `MemberRole` 枚举并逐格断言（`MemberRoleTest`）：管理员不能互踢、
  谁都不能移出群主、群主不能直接退群、群主不能改自己的角色。
- **`join_seq`（V8 迁移）**：成员的历史可见下界。没有它时"把某人加进群"等同于
  "把这个群的全部历史交给他"，而加人只需要管理员权限、被泄露方毫无感知。
  退群再入群的人也看不到离开期间的消息。
- **已读游标终于可达**：`POST /conversations/:id/read`。`advanceReadSeq` 早就实现了，
  但**没有任何调用方**——未读数由 `next_seq - 1 - read_seq` 推导，因此它只增不减，
  用户看完消息后角标依然挂着。前端在选中会话及收到新消息时上报。
- **实时事件**：`conversation:created` / `members-changed` / `removed` / `updated`。
  `conversation:removed` 必须**按用户单独推送**——被移出的人已不在成员列表里，
  按会话扇出到不了他，客户端会继续显示一个已无权访问的会话直到操作全部 403。
- **前端**：联系人从静态 div 变成可点击（打开单聊）、新建群聊入口、成员管理面板
  （按自己的角色显示可用操作）。此前界面上没有任何"新建会话"的入口，
  因为服务端也没有对应的接口。
- **`conversations.spec.ts`**：5 个 E2E 用例，全部**从注册全新账号开始**，
  不使用演示账号也不打开种子会话。这是防止同类断层复发的机制。
- **`ProductionBootstrapIntegrationTest`**：prod profile 下注册 → 建会话 → 发消息 →
  对端读到 → 建群 → 加人 → 角色正确 → 越权被拒。

#### 修复

- **`UUID.compareTo` 与 PostgreSQL 的 uuid 排序不一致**。单聊唯一键
  `(user_low_id, user_high_id)` 要求有序，而 Java 把两个 64 位分量当**有符号** long
  比较，PostgreSQL 按 16 字节**无符号**比较——首字节 ≥ 0x80 时结论相反，
  写入直接违反 `chk_direct_key_order`。随机 UUID 约一半概率触发。
  **演示种子恰好掩盖了它**：种子用户 ID 是 `1111…`/`2222…`/`3333…`，首字节远小于
  0x80，两种比较给出相同结果。规范化收敛到 `ConversationRepository.DirectKey`，
  并由数据库判定顺序的测试锁定（不在 Java 里再实现一遍预期——那只能证明两段
  Java 代码一致，而缺陷恰恰在于 Java 与数据库不一致）。

- **消息幂等在并发重发下不成立**（既有缺陷，本轮实测发现并修复）。
  `createMessage` 用 `catch (DuplicateKeyException)` 后回查首次写入的结果，
  而 PostgreSQL 的唯一约束冲突是**语句级错误，会把整个事务置为 aborted**，
  此后同一事务内的任何语句都返回 `25P02 current transaction is aborted`。
  那次回查自己就会失败，抛出的不是可识别的重复键异常而是一个 500——
  **而消息其实已经成功写入了**，客户端拿到 5xx 会继续重试。
  单线程重发看不出问题（预检查就命中了，走不到 catch 分支）。
  修复：把写入移入 `TransactionTemplate`，使冲突后事务回滚并结束，回查在新事务里
  执行。顺带解决 seq 空洞——`allocateNextSeq` 的自增随事务回滚，
  失败的尝试不占用序号（客户端按 seq 连续性判断是否漏收，空洞会让它永远等
  一条不存在的消息）。由 `MessageIdempotencyConcurrencyTest` 覆盖。
  同一根因也是单聊创建刻意改用 `ON CONFLICT DO NOTHING` + 回查的原因。

- `conversations.title/avatar/last_message` 是可空列而契约声明为非空 string。
  写入路径改为不写 NULL，读取侧兜底，使"数据库里没有 NULL"成为不变量，
  而不是让每个客户端各自处理空值。

#### 消息历史分页

补上此前不存在的两个取数方向。原先只有 `GET /messages?afterSeq=`
（`WHERE seq > :afterSeq ORDER BY seq`），那是**断线补拉**的语义而非历史回溯：

- **`GET /messages/history?beforeSeq=`**（新）——向上翻阅更早的消息。
  此前没有任何接口能取比某条消息更早的内容，而"往上滚动加载更早消息"
  是 IM 的基础交互。
- **`GET /messages/latest`**（新）——打开会话取最新一页。此前客户端从
  `afterSeq=0` 一页页翻到底，也就是把**整个会话的全部历史**拉进内存：
  五万条消息的会话点开就是 500 次串行请求。这不只是"看不到更早的消息"，
  而是打开会话本身会越来越慢，且劣化程度与会话活跃度成正比。
  演示数据里每个会话两条消息，永远看不出来。
- 两个新端点返回 `{messages, hasMore}`。`hasMore` 由服务端多取一条判断，
  而不是让客户端按"返回条数 < 页大小"推断——那个推断在最后一页恰好装满时是错的，
  客户端会多发一次请求拿到空数组，用户看到加载指示器闪一下却没有新内容。
- 可见范围仍以 `join_seq` 为下界，翻到入群那一刻停住。仓储层**不为
  `afterSeqExclusive` 提供默认值**：新增取数方向时漏传会是编译错误，
  而不是静默的历史泄露。
- `ORDER BY seq DESC` 命中既有的 `idx_messages_conversation_seq`，
  取最新一页的代价与会话总长度无关。
- 前端：打开会话改走 `/messages/latest`；"查看更早"按钮先展开本地已加载但
  未渲染的部分，本地展开完了再向服务端要下一页（对用户是同一个动作）。
  修掉一处相关缺陷——滚动到底的 effect 原先依赖 `messages.length`，
  向上翻页也会让长度增加，于是刚加载出来的历史会立刻被滚走。改为依赖最新一条的 id。
- 原 `GET /messages?afterSeq=` **保持不变**：它是已发布的契约，重连补拉依赖它。

#### 用户目录的可见性边界

`GET /api/v1/users` 此前返回**全站所有** ACTIVE 用户——无 `LIMIT` 的全表扫描，
而它是客户端加载时就会请求的接口。两个问题叠加：任何登录用户可以枚举全部注册者的
用户名、昵称与在线状态；响应体随注册量线性增长，十万用户时这一个接口就能打满连接池。

拆成三条语义不同的路径：

| 用途 | 端点 | 可见范围 |
| --- | --- | --- |
| 我的联系人 | `GET /users`（路径不变） | 与我共处过至少一个**有效**会话的人 |
| 主动找人 | `GET /users/search?username=`（新） | 需提供**完整**用户名 |
| 渲染已知的人 | `GET /users/lookup?ids=`（新） | 由 ID 指定，无可见性边界 |

- **收窄后的可见集是可推导的**：这些人的昵称在会话成员列表里本来就对我可见，
  因此这个接口不再暴露任何"我本来看不到"的信息。退群后关系解除
  （条件带 `left_at IS NULL`），否则退群在隐私层面没有任何效果。
- **`search` 刻意不做前缀/模糊匹配**：`LIKE 'ab%'` 允许用几百次请求枚举出大部分
  用户名，等于把全站列表还回去了。新增 `USER_SEARCH` 限流策略（20 次/分钟）——
  **那个配额是可见性边界的一部分**，不限速的话可以用字典枚举哪些用户名存在。
- **`lookup` 不做可见性校验，这是有意的**：ID 由服务端通过消息、红包、通话等
  鉴权过的接口交给客户端，再校验一次等于要求客户端证明它是怎么拿到 ID 的。
  没有这条路径，收窄联系人列表会让历史消息里已退群成员的昵称退化成一串 UUID。
- 路径 `/api/v1/users` **保持不变**：它是已发布的契约，改路径会让所有现有客户端
  在同一次发版里失效。返回类型仍是 `User[]`，只是内容收窄了。
- 前端：联系人页新增按用户名查找的入口（陌生人不再出现在列表里，
  没有这个入口两个陌生用户无法建立第一次对话）；`userMap` 改为合并"联系人"与
  "按 ID 补齐的资料"两个来源，消息列表渲染时按需补齐缺失的发送者。
  建群的候选人只有联系人——把从未打过交道的人直接拉进群，对他来说等同于
  被陌生人加入一个未知的群聊。

#### 修复（本轮实测发现）

- **`findForUser` 的 `WHERE` 条件落在了 JOIN 的 `ON` 子句上**。
  `SELECT_CONVERSATION` 以 `LEFT JOIN LATERAL (...) peer ON c.type = 'direct'` 结尾，
  而它直接追加 `" AND c.id = :conversationId"`——那个条件因此约束的是 JOIN 而不是
  结果集，查询退化成"取该用户的**全部**会话，但只有 id 匹配的那个关联 peer"。
  此前不可见：唯一的调用方是红包与通话的成员校验，它们只用 `isPresent()` 判断，
  多返回几行不影响结论。直到会话创建路径用它返回**单个**会话视图，
  且测试里的用户拥有两个以上会话，`optional()` 才抛
  `IncorrectResultSizeDataAccessException`。
- **建立第一次对话后对方没有成为联系人**。联系人列表只在登录时拉一次，
  于是刚聊过的人只存在于搜索结果里——用户关掉搜索就再也找不到他。
  现在建会话成功、收到 `conversation:created`、成员变更时都会刷新联系人。

#### 已知局限（本轮未做）

- 成员变更不产生系统消息（"X 邀请 Y 加入群聊"）。`messages.type` 支持 `system`，
  但前端没有对应的渲染样式，会被当作普通气泡显示。
- 消息列表仍是"窗口 + 按需展开"而非虚拟滚动，无法跳转到任意历史位置。
- 没有好友模型：联系人由会话关系推导，因此没有好友请求、黑名单、分组，
  也无法阻止陌生人通过用户名向我发起对话。
- 联系人列表上限 500 且**不分页**——它的规模由我参与的会话数决定，
  正常账号远低于此。真需要翻页时应引入游标，而不是把上限调大。

### 实测验证（第 9 轮）

把第 8 轮交付的配置与脚本真的跑起来。**三次验证各抓到一个此前不可见的缺陷。**

- **多实例验证**（17 项检查，`load-test/verify-multi-instance.mjs`）
  发现**跨实例投递完全不工作**：Lettuce 命令超时设为 1 秒且用了 `fixedTimeout`，
  它覆盖了对阻塞命令的超时推导，`XREADGROUP ... BLOCK 2000` 每轮都超时。
  **单实例下这个故障完全不可见**——消费者收到的都是自己发的事件并直接跳过，
  146 个测试全绿而跨实例投递是死的。
  修复：不设 `timeoutOptions`，改为约束 `stream-block-millis < command-timeout-millis`
  并在启动时校验（`timeoutCommands(true)` 那个变体会因缺 `TimeoutSource` 而无法启动）。

- **告警规则实测**（12 项检查，`deploy/observability/verify-rules.sh`）
  发现**延迟 SLI 一直是死的**：Spring Boot 默认把 `http_server_requests_seconds`
  发布为 summary，不含任何 bucket，而 `histogram_quantile()` 需要 bucket。
  延迟 SLO、`OpenIMHighLatency` 告警、Grafana 两个延迟面板、两条延迟记录规则
  全部静默失效——语法正确、promtool 通过、Prometheus 无错，只是永远返回空。
  按名字比对指标的 `AlertingContractTest` 也发现不了（summary 形式下名字同样存在）。
  修复：启用 `percentiles-histogram` 并把 SLO 边界配成专属 bucket；
  新增 `HttpLatencyHistogramTest` 断言**类型**而非名字。

- **首次恢复演练**（9 项检查）发现两处脚本缺陷：演练端口与 e2e profile 冲突；
  迁移历史检查取到了可重复迁移那条记录（`version` 为 NULL）而误报"无迁移历史"。
  顺带把迁移历史从"展示信息"升级为断言——备份不含它时，恢复出的库
  下次启动会尝试重新应用 V1 并失败。

- 修正一类 shell 脚本 bug：变量名紧跟中文标点时（`$VAR：`）会被吞进变量名，
  在 `set -u` 下是硬错误且只在那一行执行时才暴露。已全仓库修正并改用 `${VAR}`。

### 可靠性修复（第 8 轮）

本轮的问题全部位于此前标记为"已完成"的能力内部。其中三个属于
**代码存在但不可达**——比缺失功能更危险，因为代码的存在会让人相信能力也存在。

- **投递语义从 at-most-once 修正为 at-least-once**。通道由 Redis Pub/Sub
  换为 **Redis Streams**：每实例一个独立消费组（广播语义而非竞争消费）、
  `XACK` 在处理后确认、`XAUTOCLAIM` 重投超时未确认的记录。
  此前 `convertAndSend` 返回即 `markPublished`，而 Pub/Sub 无持久化无确认——
  任一实例的订阅器在重连窗口内就永久错过该事件。
- **去重从跨实例共享改为进程内判断**。旧实现在<b>发布前</b>用 Redis 键去重，
  主路径抢到键之后 Outbox 补偿路径被整个丢弃——补偿从未补偿过任何东西。
  "我这个实例投过了没有"才是去重该回答的问题，而它是本地状态。
- **就绪探针移除 Redis**。Redis 是所有实例共用的单点，放进就绪组会让它的故障
  同时摘掉每一个 Pod 的流量，把"部分功能降级"放大成"整站 503"，
  同时使限流降级、去重放行、presence 兜底全部变成不可达分支。
- **配置 Lettuce 命令超时与断连行为**。此前 `RedisConfig` import 了
  `ClientOptions`/`TimeoutOptions` 却从未使用，实际生效的是默认值：
  命令超时 60 秒、断连期间命令排队。Redis 挂掉后每个请求阻塞一分钟才走降级——
  功能上等同于不可用。**这个缺陷由故障注入脚本发现**，单元测试注入的是
  立即抛出的异常，测不出"超时"这种故障形态。
- **修正 V7 迁移在有数据的库上失败**。分区迁移先把数据插进 DEFAULT 再建当月分区，
  PostgreSQL 会因"DEFAULT 里有属于新分区的行"而拒绝。空库通过、有数据必然失败——
  而所有集成测试用的都是每次全新的 Testcontainers 库。
- **修正指标延迟注册**。`openim_auth_login_failures` 只在首次失败时才注册，
  服务刚启动时该序列不存在，于是失败率告警的表达式整体返回空、永不触发。
- 修正 `RealtimeWebSocketHandler` 的 SLF4J 占位符缺失，userId 此前永不打印。

### 性能

- **鉴权不再每请求打库**。`AuthenticationInterceptor` 改用 Caffeine 缓存 +
  撤销时跨实例失效广播。失效注册在**事务提交后**——在事务内失效会让其他实例
  回源读到未提交的旧值并重新缓存，反而延长陈旧窗口。
- **群消息扇出不再每次查库**。成员关系加带失效的缓存。
  **鉴权路径刻意不走缓存**：扇出用陈旧成员表只是多推一条消息，鉴权用陈旧成员表是越权。
- `requireMembership` 由两次查询（exists + isActiveMember）合并为单条双 EXISTS 查询。
- 生产库连接加 `leak-detection-threshold` 与 `statement_timeout`。

### 新增能力（第 8 轮）

- **密码找回**：`password_reset_tokens`（只存 SHA-256 哈希）、令牌单次使用、
  账号枚举防护、重置后撤销全部会话并解除账号锁定。送达渠道抽象为
  `PasswordResetNotifier`，prod 下若只有日志兜底实现则**拒绝启动**。
- **账号注销**：匿名化而非物理删除（`users` 被 14 处外键引用）。
  清除身份信息与凭证，保留账务流水、消息正文、审计记录。余额非零时拒绝。
- **业务审计**：红包发放/领取/退款写 `audit_logs`；补上一直未被写入的 `ip_hash`
  （带部署级盐值——无盐的 IP 哈希可被完全枚举反查）。
- **数据保留期**：`audit_logs` 按月分区 + `DataRetentionWorker`。
  审计分区的删除**默认关闭**，保留期由合规决定而非定时任务。
- **连接容量闸门**：单实例上限，超出以 1013 `SERVICE_OVERLOAD` 拒绝。
  此前无上限，结果是 OOMKill 而非优雅拒绝。
- **OpenAPI 规范**：springdoc 从控制器实际签名生成，24 路径 / 36 schema。
  用 `-api` 而非 `-ui`（Swagger UI 是额外的信息泄漏面），生产关闭端点，CI 导出为产物。
- **告警与 SLO**：24 条告警规则（多窗口多燃烧率）、SLI 记录规则、
  SLO 与错误预算政策、Grafana dashboard、值班手册。
- **备份**：每日 CronJob + 对象存储 + 产物校验；恢复演练脚本含账务不变量校验。
- **E2E**：Playwright 14 个用例，覆盖跨客户端实时投递、断线重连补拉、红包资金流。
- **故障注入**：`chaos-degradation.sh` 停真实 Redis 验证降级，12 项检查。
- **压测扩展**：消息吞吐（阶梯加压）、长连接容量。
- **OTLP 导出**：span 可导出到 collector。endpoint 不给默认值——
  空字符串会让导出器装配失败并阻止启动。
- **前端健壮性**：ErrorBoundary（两层）、消息列表窗口化、
  日期分隔符按实际日期（此前硬编码"今天"，补拉的历史消息全被标成今天）、
  补齐按钮 `aria-label`（"发送红包"对屏幕阅读器此前只读出"¥"）。

### 修正了先前的判断

核查报告曾把"`messages` 表无分区"列为差距。深入看之后这是**错的**：
消息查询是 `conversation_id + seq`，按时间分区会让历史拉取跨分区且分区裁剪失效；
IM 消息不删除，`DROP PARTITION` 的收益用不上；且分区表主键必须含分区键，
会破坏 `red_packets.message_id` 的外键。真正该分区的是 `audit_logs`。
论证写在 `V7__audit_log_partitioning.sql` 头部，防止后人"顺手补上"。


### 新增

- **持久化**：PostgreSQL + Flyway，17 张表；复式记账的红包账务，可独立对账。
- **身份验证**：用户名/邮箱 + 密码登录（BCrypt cost 12）、注册、改密；
  连续失败锁定；账号枚举防护（失败响应统一 + 对不存在账号也执行等价开销的哈希比对）。
  在此之前 `prod` profile 下没有任何可用的登录入口。
- **会话管理**：标准 JWT（JWS/HS256）、`kid` 多密钥轮换、刷新令牌轮换与重放检测、
  设备撤销与远程下线；客户端自动刷新（single-flight）。
- **防护**：按动作分策略的限流、CSP/HSTS 等安全响应头。
- **实时连接**：发送队列背压、应用层心跳与半开连接清理。
- **可观测性**：Prometheus 指标（9 项业务指标 + 6 项认证指标 + 限流降级指标）、
  requestId/traceId 进入 MDC、生产输出 ECS 结构化 JSON 日志。
- **部署**：三阶段 Dockerfile、compose 编排、K8s 清单（三探针分离）。
- **质量**：87 个服务端测试（含 4 个真实多线程并发测试）、JaCoCo 覆盖率门禁、
  ESLint、k6 压测脚本；CI 增加 gitleaks、CodeQL、SBOM 与 Trivy 镜像扫描。
- 仓库治理基线：`.editorconfig`、`CONTRIBUTING.md`、`CODEOWNERS`、PR 模板、Dependabot 配置。

### 变更

- **破坏性**：`POST /api/v1/auth/demo-login` 的 `userId` 字段改为 `username`，
  因为用户主键已改为 uuid。
- 访问令牌有效期由 1 小时缩短为 15 分钟，配合客户端自动刷新。
- 本地开发新增 Docker 依赖（PostgreSQL 与 Testcontainers）。
- 演示账号现在同时具备密码（`openim-demo-2026`），可用于验收凭证登录链路；
  免密的演示登录入口保留，仅在非 `prod` 环境可用。
- 改密成功后撤销该用户的**全部**设备会话（含发起改密的设备），客户端需重新登录。
- 覆盖率门禁由行 55% / 分支 40% 上调至 65% / 48%。

### 修复

- **`prod` profile 下无法登录**：`demo-login` 被 profile 禁用，而 `/refresh` 需要
  已有的刷新令牌，因此无法获得第一个令牌；`users` 表也没有任何凭证列。
- **登录失败计数被事务回滚吞掉**：计数与审计写在 `@Transactional` 方法内，
  该方法随后抛出 401/403，回滚把计数一并撤销，导致账号锁定永远不触发。
  与下一条是同一类缺陷。
- 令牌重放检测中的设备撤销与随后抛出的 401 处于同一事务，回滚会把撤销一并撤掉，
  导致"检测到攻击但设备依然可用"。
- **Outbox 补偿路径从未投递过任何事件**：写入方未在载荷中提供 `conversationId`，
  而 Worker 按该字段路由，因此所有事件都落到"会话 ID 为空"的分支被静默跳过。
  同时 Worker 把 outbox 内部事件名当作线上事件名发布，载荷结构也与主路径不一致。
- **消费端幂等缺失**：Worker 的注释声称"消费者按事件 ID 幂等处理"，
  但订阅端没有任何去重逻辑。修复前该缺陷被上一条掩盖——事件根本没被投递。
- **会话事件路由使用连接时的成员快照**：`conversationId → sessionId` 索引只在连接
  建立时填充，此后成员变动不再更新，新成员收不到消息、已退出成员仍在接收。
  改为按数据库成员关系实时解析。
- **限流在 Redis 故障时放行一切**：登录接口在 Redis 抖动期间完全失去保护。
  同时"降级为进程内令牌桶"的代码不可达（判断条件是 `redisRateLimiter == null`，
  而它是 `@Component`，永不为 null）。
- 红包过期退款此前只在有人恰好访问该红包时才触发，没人访问就永远不退款；
  改由 `ExpiryWorker` 定时驱动。
- Outbox Worker 的批次查询用 `FOR UPDATE SKIP LOCKED` 取行锁，但方法没有事务注解，
  锁在查询结束时即释放，多实例 Worker 会重复处理同一批事件。
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
