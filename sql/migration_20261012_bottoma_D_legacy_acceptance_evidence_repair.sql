-- 旧官方订阅宿主无条件 markReached，reached=1 不能证明微信接受。
-- 仅纠正无渠道代码的旧映射；保留原意图/ID与明确 code=0 的新回执，UNKNOWN 禁止自动重发。
UPDATE notification_delivery_receipt r
INNER JOIN official_broadcast_log l
    ON l.broadcast_id = r.event_id AND l.member_id = r.recipient_id
SET r.status = 'UNKNOWN', r.provider_code = 'LEGACY_UNVERIFIED', r.update_time = NOW()
WHERE r.event_type = 'OFFICIAL_BROADCAST'
  AND r.channel = 'WECHAT_SUBSCRIBE'
  AND l.channel = 'subscribe'
  AND r.status = 'CHANNEL_ACCEPTED'
  AND r.provider_code IS NULL;
