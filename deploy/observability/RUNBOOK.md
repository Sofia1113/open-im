# 值班手册（Runbook）

> 每条 critical 告警在这里都有对应条目。找不到条目的告警要么该降级为 warning，
> 要么该补一个条目——值班人在凌晨三点不该需要自己发明处置步骤。

## 通用起手式

无论哪条告警，先花 60 秒建立基本判断，不要直接跳到修复：

```bash
# 1. 实例状态：有几个在跑，就绪了吗
kubectl -n openim get pods -l app=openim-server -o wide

# 2. 最近发生了什么变更——事故的第一嫌疑人永远是最近一次部署
kubectl -n openim rollout history deployment/openim-server

# 3. 错误在哪一层：应用日志里的 ERROR 级别
kubectl -n openim logs -l app=openim-server --tail=200 --since=15m | grep -i error

# 4. 依赖是否健康
kubectl -n openim exec deploy/openim-server -- \
  curl -s localhost:3001/actuator/health | jq
```

**如果 15 分钟内有过部署，先回滚再排查。** 排查可以慢慢做，用户不能一直等：

```bash
kubectl -n openim rollout undo deployment/openim-server
kubectl -n openim rollout status deployment/openim-server --timeout=5m
```

---

## OpenIMAllInstancesDown

**症状**：Prometheus 抓不到任何实例，服务完全不可用。

**先确认这不是监控自身的问题**——比"服务挂了"更常见的是"Prometheus 挂了"：

```bash
# 从集群内直接打健康检查，绕过 Prometheus
kubectl -n openim run curl-probe --rm -i --restart=Never --image=curlimages/curl -- \
  curl -sS http://openim-server/actuator/health/readiness
```

能返回 UP 说明服务正常，问题在监控链路，按 P2 处理。

**服务确实挂了**，按可能性排序：

1. **Pod 起不来**
   ```bash
   kubectl -n openim describe pod -l app=openim-server | tail -40
   ```
   - `CrashLoopBackOff` → 看启动日志，最常见是配置缺失（`TOKEN_SECRET` 未注入、
     `DATABASE_URL` 指向错误的主机）
   - `ImagePullBackOff` → 镜像不存在或 GHCR 凭证过期
   - `Pending` → 节点资源不足，检查 `kubectl top nodes`

2. **Flyway 迁移失败**：启动时迁移不通过会直接退出。
   ```bash
   kubectl -n openim logs -l app=openim-server --previous | grep -i flyway
   ```
   迁移校验失败（`validate-on-migrate`）通常意味着有人手工改过数据库结构，
   或者部署了一个迁移历史不兼容的旧版本。**不要用 `flyway repair` 草率处理**，
   先搞清楚 schema 与代码版本的对应关系。

3. **数据库不可达**：就绪探针包含数据库，数据库挂了所有 Pod 都会 NotReady。
   参见 [DatabaseConnectionPoolExhausted](#databaseconnectionpoolexhausted) 的数据库排查部分。

---

## OpenIMErrorBudgetBurning

**症状**：5xx 比例远超 SLO 允许值。

**定位到具体接口和错误类型**：

```promql
# 哪个接口在报错
topk(5, sum by (uri, status) (
  rate(http_server_requests_seconds_count{status=~"5..",uri!~"/actuator.*"}[5m])
))
```

然后按错误集中的位置分头查：

- **集中在某一个接口** → 该接口的代码路径出了问题，看那个接口的日志。
  用 requestId 串起一次完整请求：
  ```bash
  kubectl -n openim logs -l app=openim-server --since=15m | grep '"requestId":"<id>"'
  ```
- **均匀分布在所有接口** → 共享依赖出问题，几乎总是数据库。检查连接池：
  ```promql
  hikaricp_connections_pending{pool="openim-pool"}
  hikaricp_connections_active{pool="openim-pool"}
  ```
- **只在某一个实例上** → 那个实例状态异常，直接删掉让它重建：
  ```bash
  kubectl -n openim delete pod <pod-name>
  ```

**注意区分**：`INTERNAL_ERROR` 是兜底异常处理器返回的，日志里一定有完整堆栈
（`GlobalExceptionHandler` 会 log.error）。找不到堆栈说明 5xx 不是应用产生的，
而是 Ingress 或 Service 层返回的——检查 Ingress controller 日志。

---

## DatabaseConnectionPoolExhausted

**症状**：`hikaricp_connections_pending > 0` 持续，请求排队等连接。

连接池大小是 `DATABASE_POOL_SIZE`（生产默认 20）。耗尽有三种原因，处置方式完全不同，
**不要一上来就调大池子**——如果是连接泄漏，调大只是延后崩溃时间。

**1. 慢查询占住连接**（最常见）

```sql
-- 当前正在执行且超过 5 秒的查询
SELECT pid, now() - query_start AS duration, state, left(query, 200) AS query
  FROM pg_stat_activity
 WHERE state = 'active' AND now() - query_start > interval '5 seconds'
 ORDER BY duration DESC;
```

找到长时间运行的查询后，先确认它是否安全终止，再：
```sql
SELECT pg_cancel_backend(<pid>);   -- 温和取消
SELECT pg_terminate_backend(<pid>); -- 取消无效时强制断开
```

**2. 事务未提交**（连接被长期持有）

```sql
SELECT pid, now() - xact_start AS xact_age, state, left(query, 200)
  FROM pg_stat_activity
 WHERE xact_start IS NOT NULL AND now() - xact_start > interval '1 minute'
 ORDER BY xact_age DESC;
```

`idle in transaction` 状态的连接是典型的代码 bug——事务开了没关。记下堆栈信息报给开发。

**3. 真实流量超过容量**

前两项都排除后才考虑扩容。先横向扩实例（每个实例有自己的池），
而不是单纯调大单实例的池——数据库的总连接数是有上限的（`max_connections`），
无脑调大池子会让数据库先被打满。

```bash
kubectl -n openim scale deployment/openim-server --replicas=4
```

---

## RealtimeStreamConsumerStalled

**症状**：`openim_realtime_stream_pending` 持续增长，实时消息投递受影响。

**影响范围先说清楚**：消息本身已经写入数据库，不会丢。用户重连后按 `seq` 补拉能拿到
历史消息。真正受影响的是红包状态刷新、通话信令这些没有补拉兜底的事件。

**排查顺序**：

```bash
# 1. 消费者线程还活着吗——正常应该看到周期性的消费日志
kubectl -n openim logs -l app=openim-server --since=10m | grep -i "stream"

# 2. 消费组和 pending 的实际状态
kubectl -n openim exec deploy/openim-server -- sh -c \
  'redis-cli -u "$REDIS_URL" XINFO GROUPS openim:events:stream'
```

`XINFO GROUPS` 的输出里重点看：

- **`pending` 很大且不下降** → 消费者读了但处理一直失败。看
  `openim_realtime_stream_processing_failures_total` 和应用日志里的
  `Failed to process realtime stream record`。
- **`lag` 很大但 `pending` 为 0** → 消费者根本没在读。检查消费者线程是否死了
  （虚拟线程异常退出），重启实例是最快的恢复手段。
- **消费组数量远超实例数** → 旧实例的消费组没被清理。正常情况下
  `cleanupStaleConsumerGroups` 每 10 分钟清理一次；如果堆积了几十个组，
  说明清理任务也没在跑，整个调度器可能已经卡住。

**恢复手段**（按破坏性从小到大）：

```bash
# a. 重启单个卡住的实例
kubectl -n openim delete pod <pod-name>

# b. 手工清理某个消费组的 pending（会丢弃这些事件的投递，消息本身不受影响）
redis-cli -u "$REDIS_URL" XGROUP DESTROY openim:events:stream <group-name>
# 实例会在下一轮轮询中自动重建消费组，从流末尾开始读

# c. 极端情况：整条流不可用，删除后重建
# 代价是所有实例的位点重置。消息不丢（在数据库里），
# 但已发布未消费的事件全部丢弃。
redis-cli -u "$REDIS_URL" DEL openim:events:stream
```

---

## OutboxBacklog

**症状**：`outbox_events` 中未发布记录持续增长。

**先确认 Worker 在跑**：

```bash
kubectl -n openim logs -l app=openim-server --since=10m | grep -i outbox
```

```sql
-- 积压的分布：是全部积压还是集中在某类事件
SELECT event_type, count(*), min(occurred_at) AS oldest
  FROM outbox_events
 WHERE published_at IS NULL
 GROUP BY event_type
 ORDER BY count(*) DESC;

-- 反复失败的记录
SELECT id, event_type, attempts, last_error, next_attempt_at
  FROM outbox_events
 WHERE published_at IS NULL AND attempts > 3
 ORDER BY attempts DESC
 LIMIT 20;
```

**常见原因**：

1. **Redis 写入失败** → `last_error` 里会有 `RealtimePublishException`。
   先按 [RedisUnavailable](#redisunavailable) 处理，Redis 恢复后积压会自动消化。
2. **某类事件的载荷有问题** → 集中在一个 `event_type` 上。
   看 `last_error`；载荷缺字段的记录会被标记为已发布并跳过（`routeEvent` 返回 false），
   不会积压——积压说明是抛异常而非跳过。
3. **Worker 没在跑** → 所有实例的调度器都停了。检查是否有线程池耗尽的日志，
   重启实例。

**积压清理**：如果确认某批事件已经无意义（例如已经过了几小时的通话信令），
可以手工标记为已发布，但**必须先确认它们真的不需要投递**：

```sql
-- 先看清楚要标记哪些
SELECT count(*) FROM outbox_events
 WHERE published_at IS NULL AND occurred_at < now() - interval '6 hours';

-- 确认后再执行
UPDATE outbox_events
   SET published_at = now(), last_error = 'manually skipped: stale'
 WHERE published_at IS NULL AND occurred_at < now() - interval '6 hours';
```

---

## RedisUnavailable

**症状**：`openim_redis_available == 0`。**这是 warning 不是 critical**——
服务设计为降级运行。

**当前的服务水平**（先向上汇报这个，而不是"Redis 挂了"）：

| 能力 | 状态 |
| --- | --- |
| 登录、注册、改密 | 正常 |
| 发消息、拉历史 | 正常 |
| 红包发放与领取 | 正常 |
| 同实例内的实时投递 | 正常 |
| 跨实例实时投递 | **暂停**（消息由客户端补拉兜底） |
| 在线状态 | **全部显示离线** |
| 限流 | **降级为每实例独立配额**（凭证类已自动收紧 4 倍） |

**排查**：

```bash
# Redis 是否可达
kubectl -n openim exec deploy/openim-server -- sh -c \
  'redis-cli -u "$REDIS_URL" PING'

# 内存是否打满——Streams 会持续占用内存
kubectl -n openim exec deploy/openim-server -- sh -c \
  'redis-cli -u "$REDIS_URL" INFO memory | grep -E "used_memory_human|maxmemory"'
```

**内存打满时**：事件流是主要占用者。确认修剪任务在跑：

```bash
redis-cli -u "$REDIS_URL" XLEN openim:events:stream
# 应当在 stream-max-length（默认 100000）附近。远超说明修剪没生效。

# 手工修剪
redis-cli -u "$REDIS_URL" XTRIM openim:events:stream MAXLEN '~' 50000
```

**恢复后**：不需要人工干预。限流会自动切回 Redis 计数器，presence 随心跳恢复，
积压的 outbox 事件会被 Worker 消化。确认 `openim_redis_available` 回到 1 即可。

---

## TokenReuse

**症状**：检测到多次刷新令牌重放。**这是安全事件，不是稳定性事件。**

系统已经自动处置了——每次检测到重放都会撤销对应设备的全部会话
（`DeviceRevocationService.revokeForTokenReuse`）。值班人要做的是判断**是攻击还是 bug**。

```sql
-- 涉及哪些用户和设备
SELECT actor_id, payload->>'deviceId' AS device_id, created_at
  FROM audit_logs
 WHERE action = 'auth.token_reuse_detected'
   AND created_at > now() - interval '1 hour'
 ORDER BY created_at DESC;
```

**判断依据**：

- **集中在少数用户、大量设备** → 很可能是攻击，令牌泄漏。
  联系这些用户，考虑强制改密。
- **分散在大量用户、每人一两次** → 更可能是客户端 bug。
  检查是不是刚发布了新版客户端——并发刷新在宽限期外会被误判为重放。
  临时缓解可以调大 `REFRESH_GRACE_SECONDS`，但这会削弱重放检测能力，
  只作为止血手段，必须同时修客户端。
- **全部来自同一 IP 段** → 自动化攻击，在 Ingress 层封禁。

---

## 事故记录

每次触发 critical 告警后填一行。这张表的价值在于让重复发生的问题变得显眼——
同一个原因出现三次，就该做根因修复而不是继续按手册恢复。

| 日期 | 告警 | 影响时长 | 根因 | 后续改动 |
| --- | --- | --- | --- | --- |
| — | — | — | — | — |

---

## ConnectionsRejected

**症状**：实例已达连接数上限，新 WebSocket 连接被以 1013 SERVICE_OVERLOAD 拒绝。

**影响范围**：被拒的用户收不到实时消息，但**历史消息仍可通过 HTTP 拉取**，
发消息也不受影响（那是 WebSocket 命令，已连上的不受影响；新用户会走重连退避）。
不是全站故障，但对被影响的用户是明确可感知的。

```promql
# 各实例的连接水位
openim_realtime_connections / openim_realtime_max_connections

# 拒绝速率
rate(openim_realtime_connections_rejected_total[5m])
```

**处置**（按优先级）：

1. **横向扩容**——首选。连接注册表在 JVM 内存里，加实例就是加容量：
   ```bash
   kubectl -n openim scale deployment/openim-server --replicas=4
   ```
   注意 Ingress 的会话亲和只影响**新连接**落到哪个 Pod，已建立的连接不会迁移，
   因此扩容后水位是靠新连接逐步摊平的，不会立即下降。

2. **调高上限**——仅在确认内存有余量时。上限是防止内存失控的闸门，
   调高它等于把 OOMKill 的风险换回来：
   ```bash
   kubectl -n openim set env deployment/openim-server REALTIME_MAX_CONNECTIONS=20000
   ```
   先看 `jvm_memory_used_bytes{area="heap"}` 的当前水位再决定。

3. **检查是不是连接泄漏**而非真实增长：
   ```promql
   # 连接数远超在线用户数说明有连接没被清理
   sum(openim_realtime_connections)
   ```
   心跳机制会清理半开连接（`pong-timeout-millis`，默认 90 秒）。
   如果连接数持续增长而用户数没变，检查是否有客户端在反复重连而不关闭旧连接。

**为什么不该直接关掉上限**：没有上限时，连接数无界意味着内存占用无界，
最终结果是 OOMKill——那会让**该实例上的所有连接一起断开**，
而不是优雅拒绝一条新连接。
