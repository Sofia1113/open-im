-- 演示种子数据
--
-- 仅在非 prod profile 下加载（见 application.yml 的 spring.flyway.locations）。
-- 使用 repeatable migration：总在全部版本迁移之后执行，配合 ON CONFLICT DO NOTHING 保证可重复运行。
--
-- UUID 使用固定值，让集成测试和手工验收可以直接引用。

-- ============================================================
-- 用户
-- ============================================================

INSERT INTO users (id, username, email, display_name, avatar, status, presence, last_seen_at)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'alice', 'alice@example.test', 'Alice 林夏', '林', 'ACTIVE', 'offline', now()),
    ('22222222-2222-2222-2222-222222222222', 'bob',   'bob@example.test',   'Bob 陈默',   '陈', 'ACTIVE', 'offline', now()),
    ('33333333-3333-3333-3333-333333333333', 'carol', 'carol@example.test', 'Carol 周晴', '周', 'ACTIVE', 'offline', now())
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 演示凭证
-- ============================================================
--
-- 三个演示账号的密码都是 openim-demo-2026，用于本地验收凭证登录链路。
--
-- 哈希写死而非运行时生成：种子数据必须可重复执行且不依赖应用代码，
-- 而 BCrypt 每次加盐的结果不同，无法用 SQL 表达"等价于某个明文"。
-- 三个账号共用同一个哈希是因为明文相同——正常注册不会出现这种情况，
-- 每个用户的 salt 由 BCryptPasswordEncoder 独立生成。
--
-- 这些凭证只随演示种子数据加载：prod profile 的 flyway.locations
-- 不含 db/seed，因此生产环境不存在这些账号。

INSERT INTO user_credentials (user_id, password_hash)
VALUES
    ('11111111-1111-1111-1111-111111111111',
     '$2y$12$CHLupiDTX2r7qjmKBVyge.FdtGT6xF2W2Rf3D4hCOmN6MpBbFTs4a'),
    ('22222222-2222-2222-2222-222222222222',
     '$2y$12$CHLupiDTX2r7qjmKBVyge.FdtGT6xF2W2Rf3D4hCOmN6MpBbFTs4a'),
    ('33333333-3333-3333-3333-333333333333',
     '$2y$12$CHLupiDTX2r7qjmKBVyge.FdtGT6xF2W2Rf3D4hCOmN6MpBbFTs4a')
ON CONFLICT (user_id) DO NOTHING;

-- ============================================================
-- 钱包账户：每人 500.00 元 = 50000 分
-- ============================================================

INSERT INTO wallet_accounts (id, owner_type, user_id, purpose, currency, available_balance)
VALUES
    ('a1111111-1111-1111-1111-111111111111', 'USER', '11111111-1111-1111-1111-111111111111', 'GENERAL', 'CNY', 50000),
    ('a2222222-2222-2222-2222-222222222222', 'USER', '22222222-2222-2222-2222-222222222222', 'GENERAL', 'CNY', 50000),
    ('a3333333-3333-3333-3333-333333333333', 'USER', '33333333-3333-3333-3333-333333333333', 'GENERAL', 'CNY', 50000)
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 会话：一个单聊、一个群聊
-- ============================================================

INSERT INTO conversations (id, type, title, avatar, owner_id, next_seq, last_message, last_active_at)
VALUES
    ('c1111111-1111-1111-1111-111111111111', 'direct', '好友会话', '友',
     NULL, 3, '周末一起去看展吗？', now()),
    ('c2222222-2222-2222-2222-222222222222', 'group', '周末出游计划', '游',
     '11111111-1111-1111-1111-111111111111', 3, '大家晚上八点碰一下', now() - interval '60 seconds')
ON CONFLICT (id) DO NOTHING;

-- 单聊唯一键：按字典序规范化 (low, high)
INSERT INTO direct_conversation_keys (user_low_id, user_high_id, conversation_id)
VALUES ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222',
        'c1111111-1111-1111-1111-111111111111')
ON CONFLICT (user_low_id, user_high_id) DO NOTHING;

INSERT INTO conversation_members (conversation_id, user_id, role, read_seq)
VALUES
    ('c1111111-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', 'MEMBER', 2),
    ('c1111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 'MEMBER', 2),
    ('c2222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'OWNER',  0),
    ('c2222222-2222-2222-2222-222222222222', '22222222-2222-2222-2222-222222222222', 'MEMBER', 2),
    ('c2222222-2222-2222-2222-222222222222', '33333333-3333-3333-3333-333333333333', 'MEMBER', 2)
ON CONFLICT (conversation_id, user_id) DO NOTHING;

-- ============================================================
-- 历史消息
-- ============================================================

INSERT INTO messages (id, conversation_id, seq, sender_id, client_message_id, type, content, sent_at)
VALUES
    ('d1111111-1111-1111-1111-111111111111', 'c1111111-1111-1111-1111-111111111111', 1,
     '11111111-1111-1111-1111-111111111111', 'seed-direct-1', 'text',
     '{"text": "嗨，最近怎么样？"}'::jsonb, now() - interval '9 minutes'),
    ('d1111111-1111-1111-1111-111111111112', 'c1111111-1111-1111-1111-111111111111', 2,
     '22222222-2222-2222-2222-222222222222', 'seed-direct-2', 'text',
     '{"text": "周末一起去看展吗？"}'::jsonb, now() - interval '8 minutes'),
    ('d2222222-2222-2222-2222-222222222221', 'c2222222-2222-2222-2222-222222222222', 1,
     '33333333-3333-3333-3333-333333333333', 'seed-group-1', 'text',
     '{"text": "路线我整理好了，发群里啦"}'::jsonb, now() - interval '9 minutes'),
    ('d2222222-2222-2222-2222-222222222222', 'c2222222-2222-2222-2222-222222222222', 2,
     '22222222-2222-2222-2222-222222222222', 'seed-group-2', 'text',
     '{"text": "大家晚上八点碰一下"}'::jsonb, now() - interval '8 minutes')
ON CONFLICT (id) DO NOTHING;
