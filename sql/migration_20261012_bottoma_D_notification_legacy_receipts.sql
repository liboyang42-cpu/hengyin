-- 旧发送记录没有持久外发身份：确认结果转终态，其余保留 UNKNOWN，不猜测重发。
INSERT IGNORE INTO notification_delivery_receipt
 (event_type,event_id,recipient_id,channel,status,create_time,update_time)
SELECT 'OFFICIAL_BROADCAST',broadcast_id,member_id,
 CASE WHEN channel='inapp' THEN 'IN_APP' ELSE 'WECHAT_SUBSCRIBE' END,
 CASE WHEN reached=1 AND channel='inapp' THEN 'IN_APP_STORED'
      WHEN reached=1 THEN 'CHANNEL_ACCEPTED' ELSE 'UNKNOWN' END,
 COALESCE(create_time,NOW()),NOW()
FROM official_broadcast_log WHERE channel IN ('inapp','subscribe');
