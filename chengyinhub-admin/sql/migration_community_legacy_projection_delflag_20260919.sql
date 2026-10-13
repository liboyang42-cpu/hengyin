-- 收敛 #1057 广场历史投影的可见性：把 legacy 侧已经不公开的帖子从「已发布+可搜索」改回隐藏。
-- 阶段：post-deploy（纯数据对账，不依赖新 jar；可任意时刻重跑，第二次起为 no-op）。
-- 前置：migration_community_post_platform_20260909.sql（投影表由它建）。
--
-- 根因（2026-09-19 生产实跑回读坐实）：回填把 lifecycle 判成
--   `CASE WHEN s.del_flag = 2 THEN 'DELETED' WHEN s.status = 1 THEN 'PUBLISHED' ...`，
-- 只认 del_flag=2 一种删除；而广场的真源 view_creative_square 过滤的是 `del_flag = 0 AND status = 1`。
-- ums_creative_square.del_flag 的列注释只定义了「0 存在 / 2 删除」，库里却另有 13 条 del_flag=1
-- （全部 status=1，多为测试期内容）：视图里出现 0 条，新投影里却是 13 条 PUBLISHED + 搜索 VISIBLE。
-- 也就是说这份投影不是「以后会漂」，是今天就已经比用户实际能看到的多了 13 条；一旦切读源，
-- 这些作者侧已经消失的帖子会带着图片重新出现在广场和搜索里。
--
-- 只改 lifecycle 与搜索文档的派生可见性，不动 search_eligible / recommendation_eligible /
-- interaction_enabled——回填那三列的 CASE 本来就带 `COALESCE(del_flag,0)=0`，这 13 条已经是 0。
-- 也不删行：切源底稿要留着，条数守恒由下面的断言钉住。

UPDATE community_post p
JOIN ums_creative_square s ON s.id = p.id
SET p.lifecycle = 'REMOVED',
    p.update_time = NOW()
WHERE p.client_request_id LIKE 'legacy-square-%'
  AND p.lifecycle = 'PUBLISHED'
  AND COALESCE(s.del_flag, 0) <> 0
  AND s.del_flag <> 2;

-- 搜索文档按运行期同一套派生式重算（CommunityPostServiceImpl.runMaintenance 走的就是这个口径），
-- 否则这条修完，下一轮维护任务又会按 lifecycle 把它写回 VISIBLE，两边永远在打架。
UPDATE community_search_document d
JOIN community_post p ON p.id = d.document_id AND d.document_type = 'POST'
SET d.visibility_state = CASE WHEN p.lifecycle = 'DELETED' THEN 'DELETED'
                              WHEN p.lifecycle = 'PUBLISHED' AND p.search_eligible = 1 THEN 'VISIBLE'
                              ELSE 'HIDDEN' END,
    d.indexed_at = NOW()
WHERE p.client_request_id LIKE 'legacy-square-%'
  AND d.visibility_state <> CASE WHEN p.lifecycle = 'DELETED' THEN 'DELETED'
                                 WHEN p.lifecycle = 'PUBLISHED' AND p.search_eligible = 1 THEN 'VISIBLE'
                                 ELSE 'HIDDEN' END;

-- 1) 不允许再有「legacy 侧不公开、投影却 PUBLISHED」的帖子。
-- runner: assert=scalar-true
SELECT NOT EXISTS (
  SELECT 1
  FROM community_post p
  JOIN ums_creative_square s ON s.id = p.id
  WHERE p.client_request_id LIKE 'legacy-square-%'
    AND p.lifecycle = 'PUBLISHED'
    AND COALESCE(s.del_flag, 0) <> 0
) AS assertion_ok;

-- 2) 投影里的已发布条数必须等于真源视图口径的条数（这是「切源前后用户看到的同一批帖子」的钉子）。
-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM ums_creative_square
   WHERE COALESCE(del_flag, 0) = 0 AND status = 1 AND member_id IS NOT NULL)
  =
  (SELECT COUNT(*) FROM community_post
   WHERE client_request_id LIKE 'legacy-square-%' AND lifecycle = 'PUBLISHED') AS assertion_ok;

-- 3) 搜索文档的可见性必须与 lifecycle + search_eligible 派生一致（防第二条 UPDATE 漏项）。
-- runner: assert=scalar-true
SELECT NOT EXISTS (
  SELECT 1
  FROM community_search_document d
  JOIN community_post p ON p.id = d.document_id AND d.document_type = 'POST'
  WHERE p.client_request_id LIKE 'legacy-square-%'
    AND d.visibility_state <> CASE WHEN p.lifecycle = 'DELETED' THEN 'DELETED'
                                   WHEN p.lifecycle = 'PUBLISHED' AND p.search_eligible = 1 THEN 'VISIBLE'
                                   ELSE 'HIDDEN' END
) AS assertion_ok;

-- 4) 这次只改可见性、不删历史：投影条数仍等于 legacy 有作者成员的条数。
-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM community_post WHERE client_request_id LIKE 'legacy-square-%')
  =
  (SELECT COUNT(*) FROM ums_creative_square WHERE member_id IS NOT NULL) AS assertion_ok;
