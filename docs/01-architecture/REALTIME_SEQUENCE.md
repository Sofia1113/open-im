# WebSocket 实时链路时序图

> 基于当前实现（Redis Streams + 跨实例扇出改造完成后）梳理：连接建立、聊天消息全链路、断线重连与断线补拉、消息接收后的会话成员查找与投递。
> 相关源码：`apps/server/src/main/java/com/openim/server/realtime`、`apps/server/src/main/java/com/openim/server/config/WebSocketConfig.java`、`apps/web/src/realtime.ts`、`apps/web/src/messageSync.ts`、`apps/web/src/App.tsx`

## 系统组件总览

| 组件 | 职责 | 关键参数（默认值） |
|---|---|---|
| `RealtimeClient`（web） | 原生 WebSocket 客户端：连接/事件监听/命令发送/指数退避重连 | 命令超时 15s；重连 1s 起、翻倍、上限 30s |
| `WebSocketAuthenticationInterceptor` | 握手鉴权：校验 token + deviceId + 设备会话，注入会话属性 | 失败 401 拒绝握手 |
| `RealtimeWebSocketHandler` | 连接注册表（sessions / userSessions / deviceSessions）、命令分发、心跳、会话投递 | 单实例上限 10000；心跳 30s；pong 超时 90s |
| `RealtimeBroadcastService` | 统一两条投递路径：本实例同步投递 + Redis Stream 跨实例发布 | 去重只决定"本实例是否投递" |
| `RealtimeEventStream` | Redis Streams 事件流，所有实例共享一条流 | `openim:events:stream`，MAXLEN 10 万 |
| `RealtimeEventStreamConsumer` | 每实例一个消费组（组名=instanceId）做广播式消费，XACK 后置 | block 500ms、batch 64、XAUTOCLAIM 30s |
| `EventDeduplicator` | 本实例本地去重：优先业务 dedupKey（如 `message:{id}`），无则退回流记录 ID | — |
| `PresenceService` | 在线状态（Redis TTL） | — |
| `ConversationMemberCache` | 会话成员列表缓存：Caffeine 短 TTL + Redis Pub/Sub 失效广播，自愈 | TTL 60s、容量 2 万 |
| `MessageService` | 消息落库：成员校验、clientMessageId 幂等、事务内分配 seq、写 outbox | 只接受文本，1–4000 字符 |
| `OutboxPublisherWorker` | 补偿投递：扫描 outbox 记录，`republishToConversation` 重投 | 5s 轮询；写流失败抛异常 → markFailed 重试 |

## 图 1：WebSocket 连接建立（握手鉴权 + 连接注册）

```mermaid
sequenceDiagram
    participant Client as 客户端 RealtimeClient
    participant WS as /ws 端点
    participant Interceptor as WebSocketAuthenticationInterceptor
    participant Token as TokenService
    participant Dev as DeviceRepository
    participant Handler as RealtimeWebSocketHandler
    participant Presence as PresenceService(Redis)

    Client->>Client: connect() → openSocket()
    Client->>Client: tokenProvider() —— 每次握手取一次新鲜 access token（15 分钟有效）
    alt token 获取失败
        Client-->>Client: dispatch connect_error(CONNECT_FAILED) → scheduleReconnect()
    else token 为 null（会话已失效）
        Client-->>Client: dispatch connect_error(AUTH_REQUIRED) —— 终止重连，引导重新登录
    else token 有效
        Client->>WS: new WebSocket(ws(s)://host/ws?token=...&deviceId=...) —— HTTP Upgrade 握手
        WS->>Interceptor: beforeHandshake(request, response, attributes)
        Interceptor->>Token: verify(token, "access") —— JWT 验签 + 时钟偏移容忍
        Token-->>Interceptor: TokenPayload(userId, deviceId)
        alt token 无效/过期 或 deviceId 与 token 不匹配 或 UUID 解析失败
            Interceptor-->>WS: 401，拒绝握手
        else 通过
            Interceptor->>Dev: isSessionActive(deviceId, userId) —— 设备会话必须有效
            alt 设备会话已失效（被撤销/被顶号）
                Interceptor-->>WS: 401，拒绝握手
            else 有效
                Interceptor->>WS: 握手成功，注入 attributes: USER_ID / DEVICE_ID
                WS->>Handler: afterConnectionEstablished(session)
                alt 实例已满（sessions.size >= maxConnections）
                    Handler-->>WS: 1013 SERVICE_OVERLOAD —— 让负载均衡器导向其他实例
                else 有空位
                    Handler->>Handler: ConcurrentWebSocketSessionDecorator 包装（并发安全 + 发送缓冲限制）
                    Handler->>Handler: 注册三张表：sessions / userSessions / deviceSessions
                    Handler->>Presence: markOnline(userId) —— Redis 写在线状态（带 TTL）
                end
            end
        end
    end
```

> 要点：握手是 WebSocket 唯一的一次性鉴权入口，鉴权失败以 401 拒绝、不建连接；容量闸门以 1013 拒绝（语义是"稍后重试"，客户端指数退避是正确的响应）。注册顺序固定"先注册表、后 presence"，避免"presence 已在线而连接不可达"的投递空洞。

连接建立后客户端侧：

```mermaid
sequenceDiagram
    participant Client as 客户端 RealtimeClient
    participant App as App.tsx（React 层）

    Client-->>Client: socket.onopen → reconnectAttempts = 0 → dispatch("connect")
    Client-->>App: connect 事件
    App->>App: setConnected(true)
    App->>App: syncKnownConversations() —— 对每个已知会话按本地最新 seq 断线补拉（展开见图 3）
```

## 图 2：聊天消息全链路（发送 → 落库 → 广播 → ACK）+ 心跳

```mermaid
sequenceDiagram
    participant Sender as 发送方 RealtimeClient
    participant Handler as RealtimeWebSocketHandler
    participant Msg as MessageService
    participant ConvRepo as ConversationRepository(PostgreSQL)
    participant Outbox as OutboxRepository
    participant Broadcast as RealtimeBroadcastService
    participant Local as 本实例本地投递（图 4）
    participant Stream as Redis Streams
    participant Consumer as 其他实例 RealtimeEventStreamConsumer
    participant Recipient as 接收方 RealtimeClient

    Sender->>Sender: emit("message:send", payload, ack)
    alt socket 未就绪（非 OPEN）
        Sender-->>Sender: 立即回调 ack(ok:false, CONNECTION_CLOSED)
    else 已连接
        Sender->>Sender: commandId = UUID；注册 15s 超时的 pending 命令
        Sender->>Handler: {kind:"command", commandId, event:"message:send", payload}
        Handler->>Handler: touch(sessionId) —— 刷新连接活跃时间
        Handler->>Handler: requireQuota(SEND_MESSAGE) —— 按 user 维度限流，超限 429 RATE_LIMITED
        Handler->>Msg: createMessage(senderId, input)
        Msg->>ConvRepo: requireMembership(conversationId, senderId) —— 非成员直接拒绝
        Msg->>Msg: findByClientMessageId —— 幂等：同一 clientMessageId 重复发送返回已存在消息
        alt 首次发送
            Msg->>Msg: TransactionTemplate 内：allocateNextSeq（会话级 seq）
            Msg->>ConvRepo: insert 消息（id, seq, senderId, clientMessageId）
            Msg->>ConvRepo: updateSummary(conversationId, text, sentAt) —— 会话摘要
            Msg->>Outbox: append("conversation", conversationId, "message:created", {messageId, conversationId, seq})
        end
        Msg-->>Handler: ImMessage（含 conversationId）
        Handler->>Broadcast: broadcastToConversation(conversationId, messageCreated(message))
        Broadcast->>Broadcast: deliverLocally：dedup("message:{id}") 未投递过才走本地
        Broadcast->>Local: emitToConversation(conversationId, "message:created", payload) —— 展开见图 4
        Broadcast->>Stream: publishQuietly：XADD 事件流（originInstanceId 标记来源实例）
        alt 写流失败
            Broadcast->>Broadcast: 静默降级为仅本地投递 —— 不因扇出失败向发送方报错；漏投由客户端按 seq 补拉或 Outbox 补偿兜底
        end
        Handler-->>Sender: {kind:"ack", commandId, ok:true, data:message}
        Sender->>Sender: handleMessage：按 commandId 找到 pending → 清 15s 超时 → acknowledge(message)
    end

    Note over Stream,Consumer: 跨实例扇出（广播语义：每个实例一个消费组，组名=instanceId）
    Consumer->>Stream: XREADGROUP 阻塞读取（block 500ms，batch 64）
    Stream-->>Consumer: 新记录
    alt originInstanceId == 本实例（自己发布的事件，已同步投递过）
        Consumer->>Stream: XACK —— 跳过，避免重复
    else 其他实例发布
        Consumer->>Consumer: dedupKey 去重（无 dedupKey 退回流记录 ID）
        alt 未投递过
            Consumer->>Local: dispatch → emitToConversation（展开见图 4）
        end
        Consumer->>Stream: XACK —— 确认在投递成功之后
    end

    Note over Handler,Recipient: 心跳（每 30s 一次）
    Handler->>Handler: sendHeartbeats()：遍历 sessions
    alt lastSeenAt 超过 pong 超时（90s）未刷新
        Handler->>Handler: close(SESSION_NOT_RELIABLE) —— 清理死连接，触发 afterConnectionClosed
    else 活跃连接
        Handler->>Recipient: PingMessage
        Recipient-->>Handler: PongMessage
        Handler->>Handler: handlePongMessage：touch + markOnline 续 Redis presence TTL
    end
```

> 要点：命令帧带 `commandId`，任何失败（限流、校验、内部错误）都回带同一 id 的错误 ack，客户端据此把失败与具体请求对上；消息在落库成功后才广播、才回 ack——"发送成功"永远是诚实的结果。写流失败不抛出：主路径业务已成功，漏掉的跨实例投递由客户端按 `seq` 补拉或 `OutboxPublisherWorker` 补偿（写流失败必须抛出，驱动 `markFailed` 重试）。

## 图 3：连接断开、指数退避重连与断线补拉

```mermaid
sequenceDiagram
    participant Client as 客户端 RealtimeClient
    participant Handler as RealtimeWebSocketHandler
    participant Presence as PresenceService(Redis)
    participant User as UserService(PostgreSQL)
    participant App as App.tsx
    participant API as GET /messages REST 接口

    Note over Client,Handler: 断开（网络抖动 / 服务端心跳清理 / 传输错误 / 服务端 1013）
    Handler->>Handler: afterConnectionClosed(session, status)
    Handler->>Handler: 从 sessions / userSessions / deviceSessions 三张表移除
    alt 该用户还有其他设备连接
        Handler->>Handler: 保持在线 —— 多设备在线时不能贸然下线
    else 无任何剩余连接
        Handler->>Presence: 删除 Redis presence key
        Handler->>User: setUserOnline(userId, false) —— 更新 DB last_seen_at
    end

    Client-->>Client: socket.onclose → socket = null → dispatch("disconnect")
    Client-->>App: disconnect 事件 → setConnected(false)
    Client->>Client: rejectPending(CONNECTION_CLOSED) —— 全部在途命令以失败回调，清 15s 超时
    alt 手动断开（disconnect()）
        Client->>Client: 取消重连调度，流程终止
    else 非手动
        Client->>Client: scheduleReconnect()：延迟 = min(30s, 1s × 2^attempts)，attempts+1
        loop 直到成功或终止
            Client->>Client: 到点后 openSocket() 重试
            Client->>Client: tokenProvider() 取新 token（旧 token 可能已过期）
            alt token 获取失败
                Client->>Client: CONNECT_FAILED → 继续退避重试
            else token 为 null
                Client->>Client: AUTH_REQUIRED —— 终止重连，引导重新登录
            else 握手成功（图 1 流程）
                Client-->>Client: onopen → reconnectAttempts = 0 → dispatch("connect")
                Client-->>App: connect 事件 → setConnected(true)
                App->>API: 对每个已知会话：GET /conversations/{id}/messages?afterSeq={本地最新 seq}
                API-->>App: 一页（≤100 条）
                App->>App: collectMessagePages 翻页到底 —— 补齐离线期间漏掉的全部消息，消除本地 seq 空洞
                App->>App: mergeMessages：按 message.id 去重、按 seq 排序，合并进本地
                App->>App: 更新会话摘要（lastMessage / lastActiveAt）并按 lastActiveAt 重排
            end
        end
    end
```

> 要点：重连必须每次重新取 token（访问令牌只有 15 分钟有效，长断线后旧令牌已过期）；指数退避首次 1s、翻倍、上限 30s；1013（服务过载）与网络抖动一样自动重连，只有 AUTH_REQUIRED 是终止性错误。断线补拉只翻页到底（补齐空洞），打开会话走 `/messages/latest` 只取最新一页——客户端靠 `seq` 连续性判断有没有漏收。

## 图 4：消息接收后的逻辑与"channel"查找投递（单聊 / 群聊 / 设备）

> "channel"在本项目中即**会话（conversation）**：事件按会话投递，成员关系实时查库解析，不维护"会话 → 连接"的快照索引（快照在成员变动后会失效：新成员收不到、退群者仍在收）。投递入口有三条路径，最终都落到本图的 `emitToConversation`。

```mermaid
sequenceDiagram
    participant Main as 主路径 handleSendMessage
    participant Consumer as 其他实例事件流消费者
    participant OutboxWorker as OutboxPublisherWorker（补偿路径）
    participant Broadcast as RealtimeBroadcastService
    participant Dedup as EventDeduplicator
    participant Handler as RealtimeWebSocketHandler.emitToConversation
    participant Cache as ConversationMemberCache(Caffeine)
    participant DB as conversation_members(PostgreSQL)
    participant Registry as userSessions 连接注册表
    participant ClientB as 接收方 RealtimeClient（单聊对端 / 群聊成员）

    alt 主路径：消息发送成功，同步投递本实例
        Main->>Broadcast: broadcastToConversation(conversationId, messageCreated(msg))
    else 跨实例路径：其他实例消费事件流
        Consumer->>Broadcast: dispatch → emitToConversation(conversationId, event, payload)
    else 补偿路径：Outbox 扫描（5s 轮询），保证不丢
        OutboxWorker->>Broadcast: republishToConversation(conversationId, event)
        Note over Broadcast: 与主路径唯一区别：写流失败<b>抛出</b> → markFailed 指数退避重试
    end

    Broadcast->>Dedup: shouldDeliver(dedupKey="message:{id}")
    alt 本实例已投递过同一事件（主路径与补偿路径撞车）
        Broadcast-->>Broadcast: 跳过本地投递（但仍照常写流，让没收到的实例有机会收到）
    else 未投递过
        Broadcast->>Handler: emitToConversation(conversationId, event, payload)
        alt 本实例没有任何本地连接（userSessions 为空）
            Handler-->>Handler: 提前返回 —— 多实例部署下的常态，省掉一次无谓的成员查询
        else 有连接
            Handler->>Handler: parseUuid 校验 conversationId，非法格式跳过并告警
            Handler->>Cache: listActiveMemberIds(conversationId)
            Cache->>DB: 缓存未命中（TTL 60s 内未失效）→ 回源查活跃成员
            DB-->>Cache: 成员 ID 列表
            alt 单聊（direct 会话）
                Note over Handler: 成员表恰好 2 行：{发送者, 对端}。投递目标 = 在线的那一方（+发送者本人的其他设备端）
            else 群聊（group 会话）
                Note over Handler: 成员表 = 全部<b>活跃</b>成员：加群/退群/被移出实时反映，无需重连；退群者从此收不到
            end
            loop 每个成员 ID
                Handler->>Registry: userSessions.get(memberId) —— 本实例该用户的在线连接集合
                Registry-->>Handler: sessionId 集合（可能为空）
            end
            Handler->>Handler: emit(targets)：构造 {kind:"event", event, payload} 信封
            Note over Handler: 集合先拷贝再遍历，避免并发断连修改底层集合
            Handler->>ClientB: 逐个连接 send（同用户多端、群聊多成员都收到）
            Note over Handler: send 失败仅记指标与日志 —— 死连接由心跳与关闭回调清理
        end
    end
```

> 要点：
> - **单聊 vs 群聊的差别不在投递逻辑，而在成员集合**：单聊是 2 人集合，群聊是 N 人活跃集合；`emitToConversation` 对两者走同一条"查库解析成员 → 注册表路由"路径，因此单聊创建时无需专门为投递做任何事。
> - 成员查询走 Caffeine 缓存（TTL 60s + 建群/加人/退群/移出时经 Redis Pub/Sub 失效广播主动清除）；缓存与"连接时快照"的关键区别是**自愈**——即使失效广播丢失，陈旧窗口也止于一个 TTL，而快照的错误会持续到连接断开。
> - 成员变更场景（`conversation:members-changed`）客户端收到后只重拉该会话；实时投递侧的成员关系更新由缓存失效承担。
> - **设备级 channel**（RTC 信令）：`broadcastToDevice(userId, deviceId, ...)` 按 `deviceSessions` 索引精确路由到指定设备，不查成员表；信令目标设备在其他实例时同样经 Redis Stream 转发。

客户端接收消息后：

```mermaid
sequenceDiagram
    participant Handler as 服务端（任意投递路径）
    participant Client as RealtimeClient
    participant App as App.tsx
    participant Store as 消息与会话状态

    Handler->>Client: {kind:"event", event:"message:created", payload:{eventId, message}}
    Client->>Client: handleMessage：kind="event" → dispatch("message:created", payload)
    Client->>App: message:created 监听器
    App->>Store: addMessage(message)
    Store->>Store: 按 conversationId 归组，mergeMessages：message.id 去重、seq 排序
    Store->>Store: 更新该会话摘要（lastMessage 预览 / lastActiveAt）
    Store->>Store: 会话列表按 lastActiveAt 重排（最新消息的会话置顶）
```

> 客户端与服务端共用同一个去重标识：服务端 dedupKey = `message:{id}`，客户端按 `message.id` 去重——同一条消息被补偿路径重投时，两端各自挡住重复。
