# 俱乐部帖文联系方式只读统计(第十二轮 k2 第 2 项)

只读,一条 SELECT,不改数据。在生产只读账号下由业主执行。放在 Markdown 里,因为仓库的 SQL 清单闸只收受管目录里的 .sql。

```sql
-- 第十二轮 k2 第 2 项:只读。统计现存俱乐部帖文里疑似手机号 / 微信号的条数(游客将能看到帖文)。
-- 只有 SELECT,不改任何数据。MySQL 8(REGEXP_LIKE)。在只读账号下执行。
-- 口径:未删除的帖文;「游客可见」= 俱乐部未删除、在营业(status=1)、对外可见(public_visible 为空或 1)。
-- 手机号:前后不是数字的 1[3-9]+9 位数字(允许中间有空格或短横线)。
-- 微信号:「微信 / 威信 / V信 / vx / wx / weixin / 加V」后 0-3 个分隔符,接字母开头的 6-20 位字母数字 _ -。
SELECT
  COUNT(*)                                                              AS live_posts,
  SUM(phone_hit)                                                        AS posts_with_phone,
  SUM(wechat_hit)                                                       AS posts_with_wechat_id,
  SUM(phone_hit OR wechat_hit)                                          AS posts_with_either,
  SUM((phone_hit OR wechat_hit) AND guest_visible)                      AS guest_visible_posts_with_either
FROM (
  SELECT
    REGEXP_LIKE(cp.content, '(^|[^0-9])1[3-9]([ -]?[0-9]){9}([^0-9]|$)')                           AS phone_hit,
    REGEXP_LIKE(cp.content, '(微信|威信|[vV]信|[vV][xX]|[wW][xX]|weixin|WeChat|wechat|加[vV])[：: ]{0,3}[a-zA-Z][-_a-zA-Z0-9]{5,19}') AS wechat_hit,
    (c.status = 1 AND (c.public_visible IS NULL OR c.public_visible = 1))                         AS guest_visible
  FROM club_post cp
  JOIN club c ON c.id = cp.club_id AND c.del_flag = 0
  WHERE cp.del_flag = 0
) t;

-- 如需逐条复核(仍只读,只给 id 与俱乐部,不导出正文):
-- SELECT cp.id, cp.club_id
-- FROM club_post cp JOIN club c ON c.id = cp.club_id AND c.del_flag = 0
-- WHERE cp.del_flag = 0
--   AND (REGEXP_LIKE(cp.content, '(^|[^0-9])1[3-9]([ -]?[0-9]){9}([^0-9]|$)')
--     OR REGEXP_LIKE(cp.content, '(微信|威信|[vV]信|[vV][xX]|[wW][xX]|weixin|WeChat|wechat|加[vV])[：: ]{0,3}[a-zA-Z][-_a-zA-Z0-9]{5,19}'))
-- ORDER BY cp.id;
```
