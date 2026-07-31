-- 密码重置（找回密码）
--
-- 迁移前的状态：用户忘记密码就永久失去账号。唯一的改密入口
-- （POST /api/v1/auth/password）要求提供当前密码，而"忘记密码"的定义
-- 恰恰是不知道当前密码。连续失败还会触发账号锁定，锁定提示写着
-- "请稍后再试或重置密码"——但重置密码这条路并不存在。
--
-- 令牌只存哈希，与刷新令牌同一思路：数据库泄漏时攻击者无法直接用它重置任何人的密码。
-- 单独一张表而不是往 user_credentials 加列，原因：
--   * 一个用户可以先后请求多次重置，是一对多关系
--   * 令牌需要独立的过期与"已使用"状态
--   * 保留历史记录用于排查"是谁在什么时候重置了这个账号"

CREATE TABLE password_reset_tokens (
    id              uuid        PRIMARY KEY,
    user_id         uuid        NOT NULL REFERENCES users (id),

    -- SHA-256 十六进制。刻意不加索引之外的约束：
    -- 哈希碰撞在密码学上不可行，唯一索引足够
    token_hash      text        NOT NULL,

    expires_at      timestamptz NOT NULL,

    -- 已使用时间。非空即表示该令牌不可再用。
    -- 不用 boolean used 列：时间戳同时回答了"用过吗"和"什么时候用的"，
    -- 后者在排查账号被接管时是关键证据。
    used_at         timestamptz,

    -- 请求来源的哈希，用于排查异常重置请求的分布。
    --
    -- 存哈希而非明文，与 audit_logs.ip_hash 的约定一致（该表注释：
    -- "ip 只存哈希，不存明文"）。哈希带部署级盐值——无盐的 IP 哈希毫无意义，
    -- IPv4 只有 2^32 个值，可以完全枚举反查。见 web/ClientIpHasher。
    requested_ip_hash varchar(64),

    created_at      timestamptz NOT NULL DEFAULT now()
);

-- 令牌查找的唯一入口。唯一索引同时防止同一个哈希被插入两次
CREATE UNIQUE INDEX uq_password_reset_token_hash
    ON password_reset_tokens (token_hash);

-- 按用户查询未使用的令牌：请求新令牌时作废该用户此前的全部未用令牌。
-- 部分索引只覆盖未使用且未过期的行，这是唯一会被查询的子集。
CREATE INDEX idx_password_reset_active
    ON password_reset_tokens (user_id)
    WHERE used_at IS NULL;

-- 清理任务按过期时间扫描
CREATE INDEX idx_password_reset_expiry
    ON password_reset_tokens (expires_at)
    WHERE used_at IS NULL;

COMMENT ON TABLE password_reset_tokens IS
    '密码重置令牌。只存 SHA-256 哈希，明文只在发送给用户的那一次出现，服务端不留存。';

COMMENT ON COLUMN password_reset_tokens.used_at IS
    '已使用时间。令牌单次有效，使用后立即置值；非空的令牌不再接受。';
