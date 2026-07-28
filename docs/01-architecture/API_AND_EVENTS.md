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

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/auth/demo-login` | 本地演示登录，生产禁用 |
| POST | `/auth/refresh` | 轮换访问/刷新令牌 |
| POST | `/auth/logout` | 撤销当前设备令牌 |
| GET | `/me` | 当前用户资料 |
| GET | `/users` | 演示用户/联系人列表 |
| GET | `/devices` | 当前账号设备列表 |
| DELETE | `/devices/:deviceId` | 远程退出设备 |

演示登录请求：

```json
{
  "userId": "alice",
  "deviceId": "web-01",
  "deviceName": "Alice 的浏览器"
}
```

### 2.2 会话与消息

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/conversations` | 会话列表与用户维度设置 |
| POST | `/conversations/direct` | 获取或创建单聊 |
| POST | `/conversations/group` | 创建群聊 |
| GET | `/conversations/:id/messages?afterSeq=` | 游标补拉/历史消息；按 `seq` 升序返回最多 100 条 |
| POST | `/conversations/:id/read` | 更新已读 `seq` |
| POST | `/messages/:id/recall` | 撤回消息 |

消息主要通过 `message:send` WebSocket 命令发送，HTTP 可在 M2 提供同语义的降级端点。

历史补拉以响应末条消息的 `seq` 作为下一次 `afterSeq`，持续请求到返回不足 100 条。客户端在首次打开会话和每次 WebSocket 重连后执行补拉，并按消息 ID 去重合并，不能用历史响应覆盖同期到达的实时事件。

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

| 事件 | 方向 | 用途 |
| --- | --- | --- |
| `message:read` | 双向 | 提交/广播账号在会话的已读游标 |
| `message:recalled` | 服务端 → 客户端 | 广播撤回结果 |
| `conversation:updated` | 服务端 → 客户端 | 摘要、未读或成员信息变更 |
| `sync:request` | 客户端 → 服务端 | 提交各会话最后 `seq`，可选优化 |
| `sync:complete` | 服务端 → 客户端 | 实时流切换点 |

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
