-- 视频悬赏：原视频组「已核验」绑定（封顶只认核验过的组，不认作者自报）。
--
-- 为什么必须新增：封顶口径是「同一原视频跨平台合计」。现状 video_bounty_submission.origin_group_key
-- 来自作者请求，只做格式校验 —— 同一视频在 TikTok / YouTube 各投一次，canonicalVideoKey 不同、
-- 组键不同，per_video_cap 就被拆开绕掉。运营没有任何「这确实是同一原视频」的核验证据可依。
--
-- 只新增列与索引：不改既有列、不回填、不动数据；作者自报的 origin_group_key 保留作建议值，
-- 不再参与应计与封顶计算。核验后写入 verified_group_key，应计/封顶只读它。
-- 不执行生产 DDL，须走 chengyin-db-migrate 只读预检。
--
-- runner: assert=scalar-true
SELECT CAST(IF(DATABASE() IS NOT NULL
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
    AND table_name='video_bounty_submission' AND UPPER(engine)='INNODB')=1
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='video_bounty_submission'
    AND column_name IN ('verified_group_key','verified_by','verified_at',
                        'verified_source_ref','verified_group_version'))=0
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='video_bounty_submission' AND index_name='idx_vbs_verified_group')=0,
  1,JSON_EXTRACT('ASSERT_FAILED_video_bounty_group_verification_preflight','$')) AS UNSIGNED) AS assertion_ok;

ALTER TABLE video_bounty_submission
  ADD COLUMN verified_group_key VARCHAR(64) NULL
      COMMENT '运营核验后的原视频组键；NULL=未核验，不生成确定应计',
  ADD COLUMN verified_by BIGINT NULL COMMENT '核验操作者(后台 sys user id)',
  ADD COLUMN verified_at DATETIME NULL COMMENT '核验时间',
  ADD COLUMN verified_source_ref VARCHAR(200) NULL COMMENT '核验证据引用(工单/素材/外链编号)',
  ADD COLUMN verified_group_version INT NOT NULL DEFAULT 0
      COMMENT '核验版本；CAS 用，重投一致时不变',
  ADD KEY idx_vbs_verified_group (campaign_id, verified_group_key);

-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='video_bounty_submission'
    AND column_name IN ('verified_group_key','verified_by','verified_at','verified_source_ref')
    AND is_nullable='YES')=4
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='video_bounty_submission' AND column_name='verified_group_version'
    AND column_type='int' AND is_nullable='NO')=1
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='video_bounty_submission' AND index_name='idx_vbs_verified_group'
    AND seq_in_index=1 AND column_name='campaign_id')=1
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='video_bounty_submission' AND index_name='idx_vbs_verified_group'
    AND seq_in_index=2 AND column_name='verified_group_key')=1,
  1,JSON_EXTRACT('ASSERT_FAILED_video_bounty_group_verification_readback','$')) AS UNSIGNED) AS assertion_ok;
