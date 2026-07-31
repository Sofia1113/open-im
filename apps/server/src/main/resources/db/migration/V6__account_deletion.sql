-- 账号注销与个人数据删除
--
-- 迁移前没有任何注销能力：users.status 的 CHECK 里有 'DELETED' 取值，
-- 但没有一行代码会写入它。个人信息保护法与 GDPR 的删除权因此无法履行。
--
-- ── 为什么是匿名化而不是 DELETE ────────────────────────────
--
-- users 被 14 处外键引用（消息、会话成员、钱包账户、账务流水、红包、通话、审计）。
-- 硬删除有两条路，都不可接受：
--   * ON DELETE CASCADE —— 会连带删除账务流水，使复式记账的"所有账户余额之和恒定"
--     不再成立，对账永久不平。资金记录的完整性不能为删除权让步。
--   * ON DELETE SET NULL —— 大量列是 NOT NULL，且会让群聊里其他人的历史消息
--     突然失去发送者。
--
-- 因此采用匿名化：清除全部可识别信息，保留结构性引用。这是 GDPR 下对
-- "存在法律义务需要保留的数据"的标准处理方式，也是主流 IM 的做法。
--
-- 具体保留与清除的边界见 auth/AccountDeletionService 的类注释，
-- 以及 docs/02-features/SECURITY.md 的数据主体权利一节。

ALTER TABLE users
    -- 注销时间。status = 'DELETED' 是状态，这一列是事实发生的时间点，
    -- 用于计算"注销后多久可以清理残留数据"以及回答监管问询。
    ADD COLUMN deleted_at timestamptz,

    -- 匿名化完成时间。与 deleted_at 分开是因为两者可能不同步——
    -- 注销请求受理后，某些数据的清除可能需要异步完成（例如对象存储里的头像）。
    -- 两列都非空才表示删除权已完整履行。
    ADD COLUMN anonymized_at timestamptz;

COMMENT ON COLUMN users.deleted_at IS
    '账号注销时间。非空即表示用户已注销，此时 status 应为 DELETED。';

COMMENT ON COLUMN users.anonymized_at IS
    '个人身份信息清除完成时间。与 deleted_at 分开，用于追踪异步清除是否完成。';

-- 已注销账号的查询入口：合规问询与残留数据清理都按这个索引扫描
CREATE INDEX idx_users_deleted ON users (deleted_at)
    WHERE deleted_at IS NOT NULL;

-- ============================================================
-- 唯一约束的处理
-- ============================================================
--
-- username 上有唯一约束（users_username_key）。匿名化后必须给一个新的唯一值，
-- 否则多个注销账号会冲突。用 'deleted_' || id 保证唯一且可识别。
--
-- email 的唯一索引 uq_users_email_lower 已经带了
-- `WHERE email IS NOT NULL AND status <> 'DELETED'` 条件，
-- 因此注销后把 email 置 NULL 即可，不会与新注册的同邮箱账号冲突——
-- 这个条件当初就是为此预留的。

-- ============================================================
-- 匿名化后仍然保留的数据（有意为之）
-- ============================================================
--
--   wallet_accounts / wallet_transactions / wallet_entries
--     账务完整性。删除会让借贷不平、总额不守恒。金额本身不是个人身份信息。
--
--   messages
--     群聊消息同时是其他参与者的通信记录。发送者被匿名化为"已注销用户"，
--     消息正文保留。这是取舍：完全删除会在其他人的会话里留下无法解释的空洞。
--     单聊消息同理——对方仍然需要看到对话历史。
--
--   audit_logs
--     合规依据。actor_id 保留，因为审计的意义正是可追溯性；
--     但它已经不指向任何可识别的个人信息了（users 行已匿名化）。
--
--   red_packet_claims / red_packet_shares
--     账务凭证，与 wallet_* 同理。
