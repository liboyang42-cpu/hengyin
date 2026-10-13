-- R19-PENDINGSHELL 只读核查(不执行/不落库):存量「主题未过审、活动壳却已公开且有报名/订单」的记录与金额。
--
-- 背景:修复前 pro 主题建题/发布草稿就会生成自办活动壳(status=1/publish_status=1/del_flag=0),
-- 而主题此时还在内容审核中(status=0 待审)或已驳回(status=2)——玩家在公开活动列表就能看到并下单。
-- 本修复只堵「新建壳」的时点(过审回调才建),不清理历史行;存量的公开壳需要人工处置(下架活动/退款),
-- 所以用本 SQL 盘点曝光面。判据与公开活动列表 SQL(CmsActivityMapper.selectPublicCmsActivityList)同口径。
--
-- 口径说明:
--   topic_state = PENDING_REVIEW(待审 status=0 且 is_draft=0) / REJECTED(驳回 status=2) / DRAFT(遗留草稿 status=0 且 is_draft=1,一并列出)
--   shell_kind  = SELF_HOST(壳承接方=主题发布者,即本修复涉及的自办壳) / OTHER(其他来源,如俱乐部场次)
--   live_rows   = payment_status 0待支付/1支付中/2已支付;paid_rows 只数 2已支付(真金白银)
--   金额单位元;payable_amount=实际需付,wechat_payment_amount=微信实付,point_payment_amount=积分抵扣
SELECT
    t.id                                AS topic_id,
    t.name                              AS topic_name,
    t.publish_mode                      AS publish_mode,
    t.status                            AS topic_status,
    t.user_status                       AS topic_user_status,
    t.merchant_status                   AS topic_merchant_status,
    t.lifecycle                         AS topic_lifecycle,
    CASE
        WHEN t.status = 2 THEN 'REJECTED'
        WHEN COALESCE(t.is_draft, 0) = 1 THEN 'DRAFT'
        ELSE 'PENDING_REVIEW'
    END                                 AS topic_state,
    a.id                                AS activity_id,
    a.name                              AS activity_name,
    CASE WHEN a.member_id = t.member_id THEN 'SELF_HOST' ELSE 'OTHER' END AS shell_kind,
    COUNT(r.id)                                                          AS order_rows,
    SUM(CASE WHEN r.payment_status = 2 THEN 1 ELSE 0 END)                AS paid_rows,
    COALESCE(SUM(CASE WHEN r.payment_status = 2 THEN r.payable_amount END), 0)        AS paid_payable_amount,
    COALESCE(SUM(CASE WHEN r.payment_status = 2 THEN r.wechat_payment_amount END), 0) AS paid_wechat_amount,
    COALESCE(SUM(CASE WHEN r.payment_status = 2 THEN r.point_payment_amount END), 0)  AS paid_point_amount,
    COALESCE(SUM(CASE WHEN r.payment_status IN (0, 1) THEN r.payable_amount END), 0)  AS unpaid_payable_amount,
    MIN(r.create_time)                  AS first_order_at,
    MAX(r.create_time)                  AS last_order_at,
    GROUP_CONCAT(CASE WHEN r.payment_status = 2 THEN r.registration_no END
                 ORDER BY r.id SEPARATOR ',')                            AS paid_registration_nos
FROM cms_topic t
JOIN cms_activity a
    ON a.topic_id = t.id
   AND a.status = 1
   AND a.publish_status = 1
   AND a.del_flag = 0
   AND a.cancel_time IS NULL
JOIN cms_registration r
    ON r.owner_type = 2
   AND r.owner_id = a.id
   AND r.payment_status IN (0, 1, 2)
WHERE t.status IN (0, 2)
  AND t.del_flag = 0
GROUP BY t.id, a.id
ORDER BY paid_rows DESC, paid_payable_amount DESC, t.id ASC;
