-- 用户凭证与登录失败保护
--
-- 迁移前 users 表没有任何凭证列，唯一的登录入口是 /auth/demo-login——
-- 它按 username 查到用户就直接签发令牌，且在 prod profile 下被禁用。
-- 结果是生产环境没有任何可用的登录方式。
--
-- 这里把"身份验证"补上，与已有的"会话管理"（JWT 轮换、设备撤销）衔接：
-- 验证通过后仍然走 SessionService.login()，签发链路不变。
--
-- 凭证与用户分表存储，原因：
--   * users 会被大量只读查询命中（会话列表、成员展示），凭证列跟着一起进缓存
--     和日志的风险没有必要承担
--   * 后续接入短信/OAuth 时，一个用户会有多种凭证，分表是自然的扩展点
--   * 权限上可以对凭证表单独收紧（未来若引入只读副本或分析账号）

CREATE TABLE user_credentials (
    user_id                 uuid        PRIMARY KEY REFERENCES users (id),

    -- BCrypt 哈希，含算法标识、cost 和 salt，形如 $2a$12$...
    -- 选择 BCrypt 而非 Argon2id：Argon2PasswordEncoder 需要 bouncycastle，
    -- 而 Spring Boot BOM 不托管 bcprov 版本，自行 pin 一个加密库意味着
    -- 要自己跟踪它的 CVE。BCrypt 由 spring-security-crypto 内置，
    -- cost 12 在 OWASP 的可接受范围内。
    password_hash           text        NOT NULL,

    -- 密码最后一次变更时间。改密时会撤销该用户的全部设备会话，
    -- 因此令牌失效由 devices.revoked_at 保证；这一列是审计与排查用的事实记录
    -- （"用户报告被盗号，密码是什么时候改的"）。
    password_changed_at     timestamptz NOT NULL DEFAULT now(),

    -- 连续失败次数。成功登录清零，达到阈值后锁定。
    failed_attempts         int         NOT NULL DEFAULT 0
                                        CHECK (failed_attempts >= 0),

    -- 锁定截止时间。为空表示未锁定；过去的时间表示锁定已自然解除。
    -- 不用 boolean locked 列：那需要一个额外的定时任务来解锁，
    -- 而时间戳让"是否锁定"成为一个可以在查询里直接判断的事实。
    locked_until            timestamptz,

    last_login_at           timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE user_credentials IS
    '密码凭证。只存哈希，不存明文也不存可逆加密值。日志与指标中禁止出现本表任何字段的值。';

COMMENT ON COLUMN user_credentials.locked_until IS
    '锁定截止时间。登录校验用 locked_until > now() 判断，无需定时解锁任务。';

-- ============================================================
-- 邮箱：账号找回与通知的联系方式
-- ============================================================
--
-- 放在 users 而不是 user_credentials：邮箱是用户资料的一部分，
-- 未来即使改为短信或 OAuth 登录，邮箱仍然存在。

ALTER TABLE users
    ADD COLUMN email varchar(254);

-- 大小写不敏感的唯一约束：邮箱的本地部分理论上区分大小写，
-- 但实践中所有主流服务商都不区分，允许 Alice@x.com 和 alice@x.com
-- 同时注册只会造成账号混淆和找回歧义。
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email))
    WHERE email IS NOT NULL AND status <> 'DELETED';

COMMENT ON COLUMN users.email IS
    '联系邮箱，登录标识之一。唯一性按 lower(email) 判定，见 uq_users_email_lower。';

-- ============================================================
-- 演示用户的凭证
-- ============================================================
--
-- 只在非 prod 加载种子数据，因此这里不能直接 INSERT——
-- 该迁移在 prod 也会执行。演示凭证放在 db/seed/R__demo_seed.sql 里，
-- 与其他演示数据同进同退。
