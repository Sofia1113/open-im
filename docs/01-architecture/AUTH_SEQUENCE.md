# 认证与缓存机制时序图

> 基于当前实现（Redis 缓存改造完成后）梳理：登录认证、Token 刷新、请求鉴权与会话有效性缓存。
> 相关源码：`apps/server/src/main/java/com/openim/server/{auth,web,cache}`

## 系统组件总览

| 组件 | 职责 | 关键参数（默认值） |
|---|---|---|
| `AuthController` | 无会话入口：`/login`、`/register`、`/refresh`、密码重置 | 失败统一 401/403 |
| `AuthenticationService` | 凭证比对、失败锁定、签发入口 | BCrypt cost=12（约 200–400ms） |
| `LoginAttemptService` | 失败计数/锁定（**REQUIRES_NEW 独立事务**，落 PostgreSQL） | 5 次失败锁 15 分钟 |
| `SessionService` | 会话编排：签发/轮换/撤销，刷新令牌只存 SHA-256 哈希 | access 900s / refresh 7 天 / 宽限 60s |
| `TokenService` | JWS（HS256）签发与验签，`tt` 声明区分 access/refresh | 时钟偏移容忍 30s |
| `DeviceRepository` | `devices` 表，CAS 式令牌轮换 | — |
| `SessionValidityCache` | Caffeine 本地缓存（**正负结果都缓存**），会话有效性查询 | TTL 30s，容量 5 万 |
| `CacheInvalidationBus` | Redis Pub/Sub 跨实例失效广播，事务提交后触发 | 失败降级为等 TTL |
| `AuthenticationInterceptor` | 每个受保护请求：验签 + 查缓存 + 注入 `AuthPrincipal` | — |

## 图 1：凭证登录（正常路径 + 失败/锁定路径）

```mermaid
sequenceDiagram
    participant Client as 客户端
    participant Ctrl as AuthController
    participant Auth as AuthenticationService
    participant Cred as CredentialRepository(PostgreSQL)
    participant Attempt as LoginAttemptService
    participant Sess as SessionService
    participant Token as TokenService
    participant Dev as DeviceRepository(PostgreSQL)
    participant Cache as SessionValidityCache

    Client->>Ctrl: POST /api/v1/auth/login (identifier, password, deviceId, deviceName)
    Ctrl->>Auth: login(...)
    Auth->>Cred: findByLoginIdentifier(identifier)
    Cred-->>Auth: Optional<Credential>

    alt 用户不存在
        Auth->>Auth: BCrypt.matches(password, dummyHash) —— 消除计时差异
        Auth-->>Client: 401 INVALID_CREDENTIALS
    else 用户存在
        alt 账号已锁定
            Auth->>Attempt: recordBlockedAttempt()（独立事务，不累加计数）
            Auth-->>Client: 403 ACCOUNT_LOCKED
        else 未锁定
            Auth->>Auth: BCrypt.matches(password, hash) —— cost=12
            alt 密码错误
                Auth->>Attempt: recordFailure() REQUIRES_NEW（独立提交，防回滚丢失计数）
                Attempt->>Cred: 失败计数 +1，达 5 次则锁定 15 分钟
                alt 达阈值
                    Auth-->>Client: 403 ACCOUNT_LOCKED
                else 未达阈值
                    Auth-->>Client: 401 INVALID_CREDENTIALS
                end
            else 密码正确
                Auth->>Attempt: recordSuccess() REQUIRES_NEW —— 清零失败计数
                Auth->>Sess: login(user, deviceId, deviceName)
                Sess->>Token: issue(userId, deviceId, "access", TTL=900s)
                Token-->>Sess: accessToken（jti 唯一，tt=access）
                Sess->>Token: issue(userId, deviceId, "refresh", TTL=604800s)
                Token-->>Sess: refreshToken（tt=refresh）
                Sess->>Dev: upsert(deviceId, SHA-256(refreshToken), expiresAt) —— 清空 revoked_at，设备"复活"
                Sess->>Cache: invalidate(deviceId, userId) —— 事务提交后触发
                Cache->>Redis: Pub/Sub 广播失效（展开见图 4）
                Sess-->>Auth: Session(access, refresh, user)
                Auth-->>Ctrl: 200
                Ctrl-->>Client: 200 { accessToken, refreshToken, expiresAt, user }
            end
        end
    end
```

> 安全要点：`/register` 走同一签发链路（唯一约束防并发冲突，冲突统一 `IDENTIFIER_TAKEN`）；`/demo-login` 仅非 prod 生效，直接查用户后进 `SessionService.login`。失败计数与锁定**落在 PostgreSQL 而非 Redis**——Redis 是可降级组件，锁定必须多实例共享且可靠。

## 图 2：Token 刷新（一次性轮换 + 重放检测）

```mermaid
sequenceDiagram
    participant Client as 客户端
    participant Ctrl as AuthController
    participant Sess as SessionService
    participant Token as TokenService
    participant Dev as DeviceRepository(PostgreSQL)
    participant Revoke as DeviceRevocationService
    participant Cache as SessionValidityCache

    Client->>Ctrl: POST /api/v1/auth/refresh (refreshToken)
    Ctrl->>Sess: refresh(refreshToken)
    Sess->>Token: verify(refreshToken, "refresh") —— 验签/kid/issuer/tt/过期
    alt 验签失败
        Sess-->>Client: 401（统一 AUTH_REQUIRED，不区分原因）
    else 验签通过
        Sess->>Dev: findById(deviceId)
        alt 设备不存在 / 已撤销 / 用户不匹配
            Sess-->>Client: 401 会话已失效
        else 设备有效
            Sess->>Sess: SHA-256(token) 与 currentHash / previousHash 定长比对
            alt 既非当前也非上一个 → 明确重放
                Sess->>Revoke: revokeForTokenReuse() REQUIRES_NEW（独立提交，防被 401 回滚）
                Revoke->>Dev: revoke(deviceId, "token_reuse") —— 撤销整台设备
                Revoke->>Cache: invalidate(deviceId, userId) 提交后广播
                Sess-->>Client: 401 —— 该设备全部会话作废
            else 命中当前令牌
                Sess->>Token: issue 新 access(900s) + refresh(7天)
                Sess->>Dev: rotateRefreshToken(oldHash → newHash) —— 带旧哈希条件的 CAS 更新
                alt 0 行更新（并发刷新已抢先轮换）
                    Sess-->>Client: 401（另一请求已成功，不撤销设备）
                else CAS 成功
                    Sess-->>Client: 200 新令牌对（旧令牌必须作废，客户端须保存新的）
                end
            else 命中上一个令牌（宽限期 60s 内）
                Sess->>Token: issue 新 access + refresh
                Sess->>Dev: rotateWithinGracePeriod(previousHash, ..., 60s)
                alt 超窗 / CAS 失败
                    Sess->>Revoke: revokeForTokenReuse() —— 按重放处理
                    Sess-->>Client: 401
                else 交换成功（多标签页 / 网络重试的正常情况）
                    Sess-->>Client: 200 新令牌对
                end
            end
        end
    end
```

> 核心语义：**每次刷新都签发新刷新令牌并作废旧的**。已被轮换掉的令牌再次出现，即判定为传输/存储环节被窃取，立即撤销该设备全部会话，把损失限制在单台设备（refresh token rotation with reuse detection）。并发刷新靠 CAS 条件（`WHERE refresh_token_hash = :expectedHash`）保证只有一个成功。

## 图 3：请求鉴权与会话有效性缓存

```mermaid
sequenceDiagram
    participant Client as 客户端
    participant Interceptor as AuthenticationInterceptor
    participant Token as TokenService
    participant Cache as SessionValidityCache
    participant Dev as DeviceRepository(PostgreSQL)
    participant Ctrl as 业务 Controller

    Client->>Interceptor: 请求 + Authorization: Bearer <accessToken>
    Note over Interceptor: OPTIONS 预检直接放行
    Interceptor->>Token: verify(token, "access")
    alt 无令牌 / 验签失败 / 声明不匹配 / 过期
        Interceptor-->>Client: 401 AUTH_REQUIRED
    else 令牌有效
        Interceptor->>Cache: isSessionActive(deviceId, userId)
        alt 缓存命中（正缓存或负缓存）
            Cache-->>Interceptor: cached Boolean（TTL 30s，命中率进 Micrometer 指标）
        else 缓存未命中 → 回源
            Cache->>Dev: isSessionActive(JOIN users.status='ACTIVE') —— 单条查询合并校验
            Dev-->>Cache: 结果（正负都缓存 30s，防被撤销令牌反复穿透 DB）
        end
        alt 会话已失效（设备被撤销 / 账号被封禁）
            Interceptor-->>Client: 401 AUTH_REQUIRED
        else 会话有效
            Interceptor->>Interceptor: 注入 AuthPrincipal(userId, deviceId) 到请求属性
            Interceptor->>Ctrl: 放行
            Ctrl-->>Client: 200 业务响应
        end
    end
```

> 为什么每请求都查缓存而不是只信令牌：**令牌有效 ≠ 会话有效**——远程下线、令牌重放撤销、封号必须快速生效，而非等 access token 自然过期（900s）。缓存的代价是把"撤销立即生效"放宽为"一次网络往返（广播）内生效，Redis 故障时最坏 30s"——仍比等过期快 30 倍。

## 图 4：跨实例缓存失效广播（CacheInvalidationBus）

```mermaid
sequenceDiagram
    participant SessA as SessionService（实例 A）
    participant BusA as CacheInvalidationBus A
    participant LocalA as Caffeine A（本地缓存）
    participant Redis as Redis Pub/Sub
    participant BusB as CacheInvalidationBus B
    participant LocalB as Caffeine B（本地缓存）

    SessA->>BusA: invalidateAfterCommit("session-validity", key)
    Note over SessA, BusA: 注册事务同步回调，当前事务提交后执行
    BusA->>LocalA: invalidateLocal(key) —— 本地立即失效，不依赖 Redis
    BusA->>Redis: PUBLISH openim:cache-invalidate {originInstanceId, cache, key}
    Redis-->>BusB: 订阅推送
    alt originInstanceId ≠ 实例 B（其他实例发起的）
        BusB->>LocalB: invalidateLocal(key)
    else originInstanceId = 实例 B（自己发起的）
        BusB-->>BusB: 忽略 —— 本地已失效
    end
    Note over Redis: 广播失败（如 Redis 故障）→ 仅计数告警，不抛出<br/>其他实例靠自身 30s TTL 兜底收敛
```

**失效触发点全景**（写路径全部经 `invalidateAfterCommit`，避免事务内广播引发"旧值被重新缓存"竞态）：

| 触发场景 | 失效范围 | 调用方 |
|---|---|---|
| 登录成功（设备复活） | 单设备 key | `SessionService.login` |
| 登出 / 远程下线 | 单设备 key | `SessionService.logout` |
| 令牌重放检测 | 单设备 key | `DeviceRevocationService` |
| 改密 / 退出全部设备 | **全部清空**（`*`） | `SessionService.logoutAllDevices` |
| 密码重置成功 | 全部清空（经全设备撤销） | `PasswordResetService` |
| 账号注销/封禁 | 全部清空 | `AccountDeletionService` 等 |

> 设计取舍：失效广播用 Pub/Sub 而消息投递用 Redis Streams——失效通知丢失至多多用一个 TTL 的陈旧数据（有界、可自愈），所以不重试、不持久化、不确认；所有接入缓存的组件都必须自带 TTL 兜底。`invalidateAll` 用于受影响设备集合未知的批量操作，代价仅是其他用户多一次回源。

## 关键机制小结

1. **双令牌模型**：access（15 分钟，无状态验签）+ refresh（7 天，有状态哈希存库、一次性轮换）；刷新令牌被盗用的损失被"单设备撤销"封顶。
2. **数据库是正确性来源，Redis 是性能优化**：失败锁定、令牌哈希、CAS 轮换都在 PostgreSQL；Redis 只承担跨实例缓存失效广播（可降级）。
3. **三处独立事务（REQUIRES_NEW）**：失败计数、令牌重放撤销、缓存失效注册——都因"调用方随后抛异常会连带回滚"而必须独立提交，否则撤销/锁定永远不生效。
4. **缓存两级防线**：本地 Caffeine（30s TTL，正负结果都缓存）+ Redis Pub/Sub 主动失效，把每请求一次 DB 往返降为内存命中。
