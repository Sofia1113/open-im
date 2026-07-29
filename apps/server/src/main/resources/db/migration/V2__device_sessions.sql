-- 设备会话：刷新令牌轮换与撤销
--
-- 刷新令牌采用"每次使用即轮换"策略，并检测旧令牌的重复使用：
-- 一个已经被轮换掉的刷新令牌再次出现，只可能是它在传输或存储中被窃取，
-- 此时撤销该设备的全部会话，把损失限制在一台设备上。

ALTER TABLE devices
    ADD COLUMN refresh_token_expires_at timestamptz,
    ADD COLUMN refresh_token_rotated_at timestamptz,
    ADD COLUMN revoked_reason           varchar(64);

COMMENT ON COLUMN devices.refresh_token_expires_at
    IS '当前刷新令牌的过期时间，供清理任务回收长期不活跃的设备记录。';
COMMENT ON COLUMN devices.revoked_reason
    IS '撤销原因：logout / token_reuse / admin / user_frozen。token_reuse 表示检测到令牌重放。';

-- 按用户批量撤销（远程下线全部设备）时使用
CREATE INDEX idx_devices_active ON devices (user_id)
    WHERE revoked_at IS NULL;

-- 清理任务扫描过期设备会话
CREATE INDEX idx_devices_refresh_expiry ON devices (refresh_token_expires_at)
    WHERE revoked_at IS NULL;
