# API 与实时事件契约

## 1. 通用约定

- HTTP 基础路径：`/api/v1`；Content-Type 为 `application/json`。
- 时间使用 ISO 8601 UTC；金额为整数分；ID 为不透明字符串。
- 鉴权：`Authorization: Bearer <accessToken>`。
- 写请求使用 `Idempotency-Key` 或请求体中的业务幂等键。
- 成功响应：`{ "data": ..., "requestId": "..." }`。
- 失败响应：`{ "error": { "code": "...", "message": "...", "details": ... }, "requestId": "..." }`。
- 分页优先使用稳定游标：`?cursor=...&limit=50`，返回 `nextCursor`。

## 2. REST API

### 2.1 认证与用户

#### 用户目录的三条读取路径

`GET /users` 曾经返回**全站所有** ACTIVE 用户——无 `LIMIT` 的全表扫描，而它是
客户端加载时就会请求的接口。两个问题叠加：任何登录用户可以枚举全部注册者的
用户名、昵称与在线状态（隐私），且响应体随注册量线性增长（容量）。

| 用途 | 端点 | 可见范围 |
| --- | --- | --- |
| 我的联系人 | `GET /users` | 与我共处过至少一个**有效**会话的人 |
| 主动找人 | `GET /users/search?username=` | 需提供**完整**用户名 |
| 渲染已知的人 | `GET /users/lookup?ids=a,b,c` | 由 ID 指定，无可见性边界 |

前两条是"发现新的人"，需要边界；第三条是"把已经拿到的 ID 换成昵称"——那些 ID
本来就通过鉴权过的接口交给了客户端，再校验一次等于要求客户端证明它是怎么拿到的。
没有第三条路径，收窄联系人列表会让历史消息里已退群成员的昵称退化成一串 UUID。

**联系人的可见集是可推导的**：这些人的昵称在会话成员列表里本来就对我可见，
因此收窄之后这个接口不再暴露任何"我本来看不到"的信息。退群后关系解除
（查询条件带 `left_at IS NULL`），否则退群在隐私层面没有任何效果。

**`search` 刻意不做前缀或模糊匹配**：`LIKE 'ab%'` 允许攻击者用几百次请求枚举出
大部分用户名，等于把刚移除的全站列表还回去了，只是慢一点。它同时受一条独立的
限流策略保护（`USER_SEARCH`，20 次/分钟）——**那个配额是可见性边界的一部分**，
不只是防滥用：不限速的话可以用字典跑几十万次请求来枚举哪些用户名存在。

找不到时返回空数组而不是 404：「不存在」与「存在但不可见」在语义上无法区分，
而两者对调用方的处理方式相同。

**没有好友模型。** 联系人是从会话关系推导出来的，不是一张独立的关系表。
真正的好友请求/黑名单/分组属于 M2。



| 方法 | 路径 | 用途 | 已实现 |
| --- | --- | --- | --- |
| POST | `/auth/login` | 凭证登录（用户名或邮箱 + 密码） | ✅ |
| POST | `/auth/register` | 注册并直接登录 | ✅ |
| POST | `/auth/refresh` | 轮换访问/刷新令牌 | ✅ |
| POST | `/auth/demo-login` | 本地演示登录，`prod` 下返回 404 | ✅ |
| POST | `/sessions/password` | 修改密码（需当前密码，成功后撤销全部会话） | ✅ |
| DELETE | `/sessions/current` | 撤销当前设备令牌 | ✅ |
| DELETE | `/sessions` | 撤销全部设备令牌 | ✅ |
| GET | `/me` | 当前用户资料 | ✅ |
| GET | `/users` | 我的联系人（与我有会话往来的人） | ✅ |
| GET | `/users/search?username=` | 按**完整**用户名查找他人 | ✅ |
| GET | `/users/lookup?ids=` | 按 ID 批量取资料（渲染已知的人） | ✅ |
| GET | `/devices` | 当前账号设备列表 | ⬜ M2 |
| DELETE | `/devices/:deviceId` | 远程退出指定设备 | ⬜ M2 |

凭证登录请求。`identifier` 同时接受用户名和邮箱，由服务端判断——
让客户端先猜是哪一种只会在用户名含 `@` 时出错：

```json
{
  "identifier": "alice",
  "password": "<密码>",
  "deviceId": "web-01",
  "deviceName": "Alice 的浏览器"
}
```

注册请求。`email` 选填；`username` 限定小写字母、数字和下划线，
因为混合大小写会让"同一个用户名"产生歧义：

```json
{
  "username": "alice",
  "email": "alice@example.com",
  "displayName": "Alice 林夏",
  "password": "<至少 10 字符>",
  "deviceId": "web-01",
  "deviceName": "Alice 的浏览器"
}
```

认证错误码：

| 错误码 | 状态 | 含义 |
| --- | --- | --- |
| `INVALID_CREDENTIALS` | 401 | 账号或密码不正确。**不区分**账号不存在与密码错误——否则登录接口即账号枚举接口 |
| `ACCOUNT_LOCKED` | 403 | 连续失败次数达阈值（默认 5 次），临时锁定（默认 15 分钟） |
| `IDENTIFIER_TAKEN` | 409 | 用户名或邮箱已被使用。不指明是哪一个 |
| `REGISTRATION_DISABLED` | 403 | 部署关闭了注册（`openim.auth.registration-enabled=false`） |
| `VALIDATION_ERROR` | 400 | 密码不满足策略（长度、常见弱密码、包含用户名等） |
| `RATE_LIMITED` | 429 | 超出限流配额，响应带 `Retry-After` |

改密的语义是"此前所有会话都不再可信"，因此成功后该用户的**全部**设备会话
（含发起改密的这一台）都会被撤销，响应中 `reauthenticationRequired` 为 `true`，
客户端需引导用户用新密码重新登录。

### 2.2 会话与消息

| 方法 | 路径 | 用途 | 状态 |
| --- | --- | --- | --- |
| GET | `/conversations` | 会话列表与用户维度设置 | ✅ |
| GET | `/conversations/:id` | 单个会话视图 | ✅ |
| POST | `/conversations/direct` | 获取或创建单聊（幂等） | ✅ |
| POST | `/conversations/group` | 创建群聊 | ✅ |
| PATCH | `/conversations/:id` | 修改群资料（标题） | ✅ |
| GET | `/conversations/:id/messages?afterSeq=` | **断线补拉**：取 `afterSeq` 之后的消息，按 `seq` 升序最多 100 条 | ✅ |
| GET | `/conversations/:id/messages/latest?limit=` | **打开会话**：取最新一页，返回 `{messages, hasMore}` | ✅ |
| GET | `/conversations/:id/messages/history?beforeSeq=&limit=` | **向上翻阅**：取比 `beforeSeq` 更早的一页 | ✅ |
| POST | `/conversations/:id/read` | 更新已读 `seq` | ✅ |
| GET | `/conversations/:id/members` | 成员列表（含角色） | ✅ |
| POST | `/conversations/:id/members` | 批量加人（单次上限 50） | ✅ |
| DELETE | `/conversations/:id/members/:userId` | 移出成员 | ✅ |
| DELETE | `/conversations/:id/membership` | 主动退群 | ✅ |
| PATCH | `/conversations/:id/members/:userId` | 指派/撤销管理员 | ✅ |
| POST | `/conversations/:id/transfer-owner` | 转让群主 | ✅ |
| POST | `/messages/:id/recall` | 撤回消息 | ❌ M2 |

> **三个消息端点的可见范围都以 `conversation_members.join_seq` 为下界**，
> 成员看不到入群之前的消息。没有这个裁剪时"把某人加进群"等同于"把全部历史交给他"，
> 而加人只需要管理员权限。向上翻阅翻到 `join_seq` 就停住，此时 `hasMore` 为 false——
> 入群前的消息不算"还有更多"，它们对这个成员根本不存在。
>
> 新增取数方向时必须一并传入 `join_seq`（仓储层不默认为 0，正是为了让漏传变成
> 编译错误而不是静默的历史泄露）。

消息主要通过 `message:send` WebSocket 命令发送，HTTP 可在 M2 提供同语义的降级端点。

#### 三个取数方向

三个独立端点而不是给一个端点加参数：三者的**游标语义不同**（`afterSeq` 向后、
`beforeSeq` 向前、latest 无游标），塞进一个端点会产生"两个游标同时传时以谁为准"
这类只能靠读代码回答的问题，而每种组合都是一个可以单独出错的分支。

| 场景 | 端点 | 游标 |
| --- | --- | --- |
| 打开会话 | `/messages/latest` | 无 |
| 向上翻阅更早 | `/messages/history` | `beforeSeq`（已加载的最早 seq） |
| 重连补拉 | `/messages?afterSeq=` | `afterSeq`（已加载的最新 seq） |

**补拉**以响应末条消息的 `seq` 作为下一次 `afterSeq`，持续请求到返回不足 100 条——
离线期间漏掉的消息必须全部补齐，否则本地 `seq` 会留下空洞，而客户端正是靠 `seq`
连续性判断有没有漏收。客户端按消息 ID 去重合并，不能用历史响应覆盖同期到达的实时事件。

**打开会话**只取最新一页。此前它走的是 `afterSeq=0` 然后一页页翻到底，也就是把
整个会话的全部历史拉进内存——五万条消息的会话点开就是 500 次串行请求。
`ORDER BY seq DESC` 命中 `idx_messages_conversation_seq`，取最新一页的代价与
会话总长度无关。

`hasMore` 由服务端给出（多取一条判断），不要按"返回条数 < limit"推断：
最后一页恰好装满时那个推断是错的，客户端会多发一次请求拿到空数组才停下。

`limit` 由客户端建议、服务端裁剪到 100 —— 客户端不能通过 `limit` 一次拉走整个会话。

#### 群成员角色

`conversation_members.role` 取值 `MEMBER` / `ADMIN` / `OWNER`。权限矩阵由服务端
`MemberRole` 统一判定，客户端隐藏按钮只是体验优化，不是权限边界。

| 操作 | OWNER | ADMIN | MEMBER |
| --- | --- | --- | --- |
| 加人 | ✅ | ✅ | ❌ |
| 移出 MEMBER | ✅ | ✅ | ❌ |
| 移出 ADMIN | ✅ | ❌ | ❌ |
| 移出 OWNER | ❌ | ❌ | ❌ |
| 指派/撤销 ADMIN | ✅ | ❌ | ❌ |
| 转让群主 | ✅ | ❌ | ❌ |
| 修改群资料 | ✅ | ✅ | ❌ |
| 主动退群 | ❌（须先转让） | ✅ | ✅ |

约束：

- **群里恒有且仅有一个 OWNER**。转让时原群主降为 ADMIN，`conversations.owner_id`
  与两个成员的 role 在同一事务内更新。
- **群主不能直接退群**，否则会留下无人可管理的群。
- **单聊没有角色概念**，两位成员都是 `MEMBER`，全部成员管理操作对单聊返回
  `NOT_A_GROUP`。
- 成员上限默认 500（`openim.conversation.max-group-members`），按
  `left_at IS NULL` 计数，退群会腾出名额。

### 2.3 通话

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/calls` | 创建呼叫房间并邀请成员 |
| GET | `/calls/:roomId` | 获取房间与参与者状态 |
| POST | `/calls/:roomId/accept` | 指定设备接听 |
| POST | `/calls/:roomId/reject` | 拒绝呼叫 |
| POST | `/calls/:roomId/leave` | 离会或结束房间 |

创建通话请求：

```json
{
  "conversationId": "conv-group",
  "mediaType": "video",
  "inviteeIds": ["bob", "carol"],
  "idempotencyKey": "call-018f..."
}
```

### 2.4 钱包与红包

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/wallet` | 当前虚拟余额与最近流水 |
| POST | `/red-packets` | 扣款、创建红包并发布卡片 |
| GET | `/red-packets/:id` | 红包摘要与领取明细 |
| POST | `/red-packets/:id/claim` | 幂等领取红包 |

创建红包请求：

```json
{
  "conversationId": "conv-group",
  "mode": "random",
  "totalAmount": 1000,
  "totalCount": 5,
  "greeting": "恭喜发财",
  "clientMessageId": "msg-018f...",
  "idempotencyKey": "red-018f..."
}
```

领取请求：

```json
{
  "idempotencyKey": "claim-018f..."
}
```

## 3. 原生 WebSocket 连接

MVP 连接地址：

```ts
const url = new URL("ws://127.0.0.1:3001/ws")
url.searchParams.set("token", accessToken)
url.searchParams.set("deviceId", deviceId)
const socket = new WebSocket(url)
```

浏览器原生 WebSocket 无法设置自定义 Authorization Header，因此本地 MVP 在握手查询参数携带短期访问令牌；生产环境应使用安全 Cookie 或 `Sec-WebSocket-Protocol`，并确保代理不记录查询令牌。

客户端命令 Envelope：

```json
{
  "kind": "command",
  "commandId": "cmd-018f...",
  "event": "message:send",
  "payload": {}
}
```

服务端 ACK Envelope：

```json
{
  "kind": "ack",
  "commandId": "cmd-018f...",
  "ok": true,
  "data": {}
}
```

服务端事件 Envelope：

```json
{
  "kind": "event",
  "event": "message:created",
  "payload": {}
}
```

命名规则保持 `domain:verb`。客户端以 `commandId` 关联 ACK，15 秒无结果可使用业务幂等键重试。

## 4. 消息事件

### `message:send`（客户端 → 服务端）

```json
{
  "conversationId": "conv-ab",
  "clientMessageId": "msg-018f...",
  "type": "text",
  "content": { "text": "你好" }
}
```

ACK 返回持久化消息。幂等重试必须返回相同结果。

### `message:created`（服务端 → 客户端）

```json
{
  "eventId": "evt-018f...",
  "message": {
    "id": "m-101",
    "conversationId": "conv-ab",
    "seq": 101,
    "senderId": "alice",
    "clientMessageId": "msg-018f...",
    "type": "text",
    "content": { "text": "你好" },
    "sentAt": "2026-07-28T08:00:00.000Z"
  }
}
```

### 其他消息事件

| 事件 | 方向 | 用途 | 状态 |
| --- | --- | --- | --- |
| `conversation:created` | 服务端 → 客户端 | 有新会话与你相关（别人开了单聊、把你拉进群） | ✅ |
| `conversation:members-changed` | 服务端 → 客户端 | 成员增减，载荷含 `joined` / `removed` | ✅ |
| `conversation:removed` | 服务端 → 客户端 | 你被移出或已退群，客户端应移除该会话 | ✅ |
| `conversation:updated` | 服务端 → 客户端 | 群资料或成员角色变更 | ✅ |
| `message:read` | 双向 | 提交/广播账号在会话的已读游标 | ❌ 已读走 REST，不广播给他人 |
| `message:recalled` | 服务端 → 客户端 | 广播撤回结果 | ❌ M2 |
| `sync:request` | 客户端 → 服务端 | 提交各会话最后 `seq`，可选优化 | ❌ 客户端按会话逐个补拉 |
| `sync:complete` | 服务端 → 客户端 | 实时流切换点 | ❌ 同上 |

会话类事件的载荷<b>只带 `conversationId`</b>，客户端据此拉 `GET /conversations/:id`。
这不是为了省流量——会话视图**因人而异**（单聊的标题是对端的昵称、未读数是各自的
游标），一个能同时发给所有收件人的完整会话对象并不存在。

`conversation:removed` 必须**按用户单独推送**：被移出的人已经不在会话成员列表里，
按会话扇出到不了他。少了这条推送，他的客户端会继续显示一个已经无权访问的会话，
直到下一次操作全部返回 403。

## 5. 通话事件

| 事件 | 方向 | 核心字段 |
| --- | --- | --- |
| `call:invite` | 服务端 → 客户端 | room、creator、mediaType、expiresAt |
| `call:participant:joined` | 服务端 → 客户端 | roomId、user、deviceId |
| `call:participant:left` | 服务端 → 客户端 | roomId、userId、reason |
| `call:ended` | 服务端 → 客户端 | roomId、reason、endedAt |
| `call:answered-elsewhere` | 服务端 → 同账号其他设备 | roomId、acceptedDeviceId |
| `rtc:offer` | 客户端 ↔ 转发服务 | roomId、targetUserId、sdp |
| `rtc:answer` | 客户端 ↔ 转发服务 | roomId、targetUserId、sdp |
| `rtc:ice-candidate` | 客户端 ↔ 转发服务 | roomId、targetUserId、candidate |
| `rtc:renegotiate` | 客户端 ↔ 转发服务 | roomId、targetUserId、reason |

信令转发前必须验证：房间活跃、发送者已加入、目标已加入/被邀请、载荷大小合法。不得在应用日志中记录 SDP 和 ICE 内容。

## 6. 红包事件

| 事件 | 方向 | 用途 |
| --- | --- | --- |
| `red-packet:created` | 服务端 → 客户端 | 新红包摘要，通常随消息卡片到达 |
| `red-packet:claimed` | 服务端 → 客户端 | 某用户领取后的计数和状态变更 |
| `red-packet:exhausted` | 服务端 → 客户端 | 红包已领完 |
| `red-packet:expired` | 服务端 → 客户端 | 红包过期 |
| `wallet:updated` | 服务端 → 账户所有设备 | 新余额和关联交易 ID |

广播给会话的红包事件只包含展示字段；个人领取金额、余额等只发送给有权查看的用户设备。

## 7. 稳定错误码

| 错误码 | HTTP | 含义 |
| --- | --- | --- |
| `AUTH_REQUIRED` | 401 | 未登录或令牌无效 |
| `TOKEN_EXPIRED` | 401 | 访问令牌过期，可尝试刷新 |
| `FORBIDDEN` | 403 | 没有操作权限 |
| `NOT_A_MEMBER` | 403 | 不是有效会话/房间成员 |
| `NOT_FOUND` | 404 | 资源不存在或不可见 |
| `VALIDATION_ERROR` | 400 | 请求字段不合法 |
| `INSUFFICIENT_ROLE` | 403 | 群内角色不足（加人、移出、改角色、转让） |
| `NOT_A_GROUP` | 400 | 该操作仅适用于群聊，对单聊无意义 |
| `SELF_CONVERSATION` | 400 | 不能与自己创建单聊 |
| `EMPTY_GROUP` | 400 | 群聊至少需要一位其他成员 |
| `GROUP_MEMBER_LIMIT` | 400 | 超过群成员上限，整批拒绝 |
| `OWNER_MUST_TRANSFER` | 400 | 群主需先转让才能退群 |
| `USE_LEAVE_ENDPOINT` | 400 | 移出自己请改用退群接口 |
| `USE_TRANSFER_ENDPOINT` | 400 | 设为群主请改用转让接口 |
| `CANNOT_CHANGE_OWN_ROLE` | 400 | 群主不能变更自己的角色 |
| `ALREADY_OWNER` | 400 | 转让目标就是当前群主 |
| `CONVERSATION_CONFLICT` | 409 | 单聊并发创建冲突且回查失败，可重试 |
| `RATE_LIMITED` | 429 | 频率超过策略 |
| `MESSAGE_TOO_LARGE` | 400 | 消息超过限制 |
| `CALL_BUSY` | 409 | 用户已在其他通话 |
| `CALL_EXPIRED` | 409 | 房间已结束/邀请已过期 |
| `INSUFFICIENT_BALANCE` | 409 | 虚拟余额不足 |
| `RED_PACKET_EXHAUSTED` | 409 | 红包已领完 |
| `RED_PACKET_EXPIRED` | 409 | 红包已过期 |
| `RED_PACKET_ALREADY_CLAIMED` | 409 | 已领取；响应附原结果 |
| `INTERNAL_ERROR` | 500 | 未预期错误，返回 requestId |

## 8. 版本兼容策略

- 新增可选字段属于兼容变更；删除/改义/改类型必须升级 API 主版本。
- 客户端忽略未知事件字段，服务端接受旧客户端缺失的新可选字段。
- 每个连接上报 `clientVersion/platform`；服务端可返回最低支持版本。
- Java DTO、Bean Validation 与 TypeScript 类型必须通过契约测试保持一致；生产阶段由 OpenAPI/AsyncAPI 生成模型。
