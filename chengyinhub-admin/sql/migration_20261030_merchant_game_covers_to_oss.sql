-- ============================================================================
-- 平台商家玩法封面迁入本站 OSS 图库(2026-10-30,实走 B1)
--
-- 现状:35 款平台商家玩法(cms_template_library)与 2 条平台自有成员模板(cms_member_template,
-- member_id=100000)的 img_url 是 https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/*.jpg。
-- 这个形状不是登记媒体、也不是本站存储 key,AppMediaReferenceGuard 拒绝采用,读路径也不签发可见地址
-- (AppMediaPublicationGuardTest 明确要求拒绝),商家因此采用不了平台玩法。
--
-- 做法:35 张原图逐字节(md5 回读核对)上传到本站 OSS 桶 chengyin-oss 的 upload/<md5>.jpg,
-- 这是守卫认可的「本站存储未登记系统图」形状,读时按 key 重签。本迁移只改写引用。
-- ⚠️ 前置:图必须已在 OSS 且回读通过(2026-10-30 已完成,35/35)。
-- 幂等:按旧值精确匹配改写,重跑不改行。回滚:同表 CASE 反向映射。
-- ============================================================================

UPDATE cms_template_library
   SET img_url = CASE img_url
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/branch-story-relay.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/d8881349a8b12d125eefa6d0b3f8865e.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/city-sidequest-observation.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/7e78a8daffcba3a2c94e03c458b50d5a.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/clothing-care-bingo.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/acd7612ec11ea88bc0e0b25fce626e10.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/coffee-flavor-personality.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/363d3554bf573a675755ca1abcfc309b.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/color-co-creation.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/c2034bb011bca6d8c5d27dd9a4a2626e.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/detail-observation-blitz.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/22a004404b88798e87c503293ee5585d.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/duo-memory-relay.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/baa606560cc76d59bc1fbdaac7d0acdc.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/family-safety-check-relay.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/1bc3ecdf1b75c3b58a5fd02d4bd0529e.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/festival-story-puzzle.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/f1ebe784e730fefa3fdebb48c48f9656.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/fitness-reaction-60.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/8fe329c85e9172be1f759d8c78fd8d00.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/gift-match-guess.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/d15ed80bd6ace7ab3fdb78ccaccfae5e.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/gym-charity-sprint.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/f7682e0a27bfb39a718306b15cfc4a33.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/hidden-menu-clue-chain.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/e87e7812f002d3846ff78f97d8386db8.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/home-safety-mines.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/0c9b46e2f4549e0cee636549cab1c352.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/keepsake-treasure-clues.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/2e497a3d84ab87029fa43abec1819c77.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/menu-co-creation.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/074fbd63e1a74a7487cb79a0ec60b5e2.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/movement-bingo.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/35bc60793ac37154318c49c1f58b2ae9.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/original-role-mystery.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/49c397217cff9ef7709992f942fa3283.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/partner-training-relay.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/47e2df8dca943e9d2cf4bd1e586e677a.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/performance-bingo.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/4eb7a1de7f7a73bef3b833a739866576.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/repair-tool-guess.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/c122f18e626eb647224cd2723f3aafaa.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/rhythm-blind-guess.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/2ccf78cf28130b62e4778b4e98801782.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/route-badge-story.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/660817d5c22bdbc4f9c7a49da64353da.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/seasonal-storage-challenge.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/d355ec00c57c54965de94e28f0125fd5.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/self-care-bingo.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/739742f12cd264de1d250011fbf55f64.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/signature-dish-blitz.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/dea4b76bba8684ed4737a9fb95738645.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/song-mood-relay.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/7054204bc8082141ca068c9443a9862f.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/space-styling-challenge.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/44635f8ce9ccb2be03c24420830c18f5.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/stain-detective.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/285cb232b98dd4a36ed6de332614d532.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/style-personality-lab.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/ca9be0c6ee70e647d4d7e1bd7b302dac.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/table-flavor-relay.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/4e93e2f8bdbf7015bf066bd482bfb1d3.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/table-mission-bingo.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/b01a09009cb9912161aa27793819d9e0.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/team-energy-relay.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/dc42e036bcdc86ac602bd04a9247532a.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/travel-partner-decisions.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/53981dd73de1c3d9dc7fd736a487e289.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/travel-safety-bingo.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/54079a8c0d978ec61aa270e9b78216a6.jpg'
    ELSE img_url END
 WHERE img_url IN (
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/branch-story-relay.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/city-sidequest-observation.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/clothing-care-bingo.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/coffee-flavor-personality.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/color-co-creation.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/detail-observation-blitz.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/duo-memory-relay.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/family-safety-check-relay.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/festival-story-puzzle.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/fitness-reaction-60.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/gift-match-guess.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/gym-charity-sprint.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/hidden-menu-clue-chain.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/home-safety-mines.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/keepsake-treasure-clues.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/menu-co-creation.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/movement-bingo.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/original-role-mystery.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/partner-training-relay.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/performance-bingo.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/repair-tool-guess.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/rhythm-blind-guess.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/route-badge-story.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/seasonal-storage-challenge.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/self-care-bingo.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/signature-dish-blitz.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/song-mood-relay.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/space-styling-challenge.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/stain-detective.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/style-personality-lab.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/table-flavor-relay.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/table-mission-bingo.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/team-energy-relay.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/travel-partner-decisions.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/travel-safety-bingo.jpg');

UPDATE cms_member_template
   SET img_url = CASE img_url
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/branch-story-relay.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/d8881349a8b12d125eefa6d0b3f8865e.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/city-sidequest-observation.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/7e78a8daffcba3a2c94e03c458b50d5a.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/clothing-care-bingo.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/acd7612ec11ea88bc0e0b25fce626e10.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/coffee-flavor-personality.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/363d3554bf573a675755ca1abcfc309b.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/color-co-creation.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/c2034bb011bca6d8c5d27dd9a4a2626e.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/detail-observation-blitz.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/22a004404b88798e87c503293ee5585d.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/duo-memory-relay.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/baa606560cc76d59bc1fbdaac7d0acdc.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/family-safety-check-relay.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/1bc3ecdf1b75c3b58a5fd02d4bd0529e.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/festival-story-puzzle.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/f1ebe784e730fefa3fdebb48c48f9656.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/fitness-reaction-60.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/8fe329c85e9172be1f759d8c78fd8d00.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/gift-match-guess.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/d15ed80bd6ace7ab3fdb78ccaccfae5e.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/gym-charity-sprint.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/f7682e0a27bfb39a718306b15cfc4a33.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/hidden-menu-clue-chain.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/e87e7812f002d3846ff78f97d8386db8.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/home-safety-mines.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/0c9b46e2f4549e0cee636549cab1c352.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/keepsake-treasure-clues.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/2e497a3d84ab87029fa43abec1819c77.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/menu-co-creation.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/074fbd63e1a74a7487cb79a0ec60b5e2.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/movement-bingo.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/35bc60793ac37154318c49c1f58b2ae9.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/original-role-mystery.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/49c397217cff9ef7709992f942fa3283.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/partner-training-relay.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/47e2df8dca943e9d2cf4bd1e586e677a.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/performance-bingo.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/4eb7a1de7f7a73bef3b833a739866576.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/repair-tool-guess.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/c122f18e626eb647224cd2723f3aafaa.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/rhythm-blind-guess.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/2ccf78cf28130b62e4778b4e98801782.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/route-badge-story.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/660817d5c22bdbc4f9c7a49da64353da.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/seasonal-storage-challenge.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/d355ec00c57c54965de94e28f0125fd5.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/self-care-bingo.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/739742f12cd264de1d250011fbf55f64.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/signature-dish-blitz.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/dea4b76bba8684ed4737a9fb95738645.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/song-mood-relay.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/7054204bc8082141ca068c9443a9862f.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/space-styling-challenge.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/44635f8ce9ccb2be03c24420830c18f5.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/stain-detective.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/285cb232b98dd4a36ed6de332614d532.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/style-personality-lab.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/ca9be0c6ee70e647d4d7e1bd7b302dac.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/table-flavor-relay.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/4e93e2f8bdbf7015bf066bd482bfb1d3.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/table-mission-bingo.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/b01a09009cb9912161aa27793819d9e0.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/team-energy-relay.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/dc42e036bcdc86ac602bd04a9247532a.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/travel-partner-decisions.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/53981dd73de1c3d9dc7fd736a487e289.jpg'
    WHEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/travel-safety-bingo.jpg' THEN 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/54079a8c0d978ec61aa270e9b78216a6.jpg'
    ELSE img_url END
 WHERE member_id = 100000 AND img_url IN (
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/branch-story-relay.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/city-sidequest-observation.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/clothing-care-bingo.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/coffee-flavor-personality.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/color-co-creation.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/detail-observation-blitz.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/duo-memory-relay.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/family-safety-check-relay.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/festival-story-puzzle.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/fitness-reaction-60.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/gift-match-guess.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/gym-charity-sprint.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/hidden-menu-clue-chain.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/home-safety-mines.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/keepsake-treasure-clues.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/menu-co-creation.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/movement-bingo.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/original-role-mystery.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/partner-training-relay.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/performance-bingo.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/repair-tool-guess.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/rhythm-blind-guess.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/route-badge-story.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/seasonal-storage-challenge.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/self-care-bingo.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/signature-dish-blitz.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/song-mood-relay.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/space-styling-challenge.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/stain-detective.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/style-personality-lab.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/table-flavor-relay.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/table-mission-bingo.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/team-energy-relay.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/travel-partner-decisions.jpg',
  'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/travel-safety-bingo.jpg');

-- runner: assert=scalar-true
SELECT (
  NOT EXISTS (SELECT 1 FROM cms_template_library WHERE img_url LIKE '%merchant-game-covers%')
  AND NOT EXISTS (SELECT 1 FROM cms_member_template WHERE img_url LIKE '%merchant-game-covers%')
  AND (SELECT COUNT(*) FROM cms_template_library WHERE img_url IN (
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/d8881349a8b12d125eefa6d0b3f8865e.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/7e78a8daffcba3a2c94e03c458b50d5a.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/acd7612ec11ea88bc0e0b25fce626e10.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/363d3554bf573a675755ca1abcfc309b.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/c2034bb011bca6d8c5d27dd9a4a2626e.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/22a004404b88798e87c503293ee5585d.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/baa606560cc76d59bc1fbdaac7d0acdc.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/1bc3ecdf1b75c3b58a5fd02d4bd0529e.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/f1ebe784e730fefa3fdebb48c48f9656.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/8fe329c85e9172be1f759d8c78fd8d00.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/d15ed80bd6ace7ab3fdb78ccaccfae5e.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/f7682e0a27bfb39a718306b15cfc4a33.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/e87e7812f002d3846ff78f97d8386db8.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/0c9b46e2f4549e0cee636549cab1c352.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/2e497a3d84ab87029fa43abec1819c77.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/074fbd63e1a74a7487cb79a0ec60b5e2.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/35bc60793ac37154318c49c1f58b2ae9.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/49c397217cff9ef7709992f942fa3283.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/47e2df8dca943e9d2cf4bd1e586e677a.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/4eb7a1de7f7a73bef3b833a739866576.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/c122f18e626eb647224cd2723f3aafaa.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/2ccf78cf28130b62e4778b4e98801782.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/660817d5c22bdbc4f9c7a49da64353da.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/d355ec00c57c54965de94e28f0125fd5.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/739742f12cd264de1d250011fbf55f64.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/dea4b76bba8684ed4737a9fb95738645.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/7054204bc8082141ca068c9443a9862f.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/44635f8ce9ccb2be03c24420830c18f5.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/285cb232b98dd4a36ed6de332614d532.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/ca9be0c6ee70e647d4d7e1bd7b302dac.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/4e93e2f8bdbf7015bf066bd482bfb1d3.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/b01a09009cb9912161aa27793819d9e0.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/dc42e036bcdc86ac602bd04a9247532a.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/53981dd73de1c3d9dc7fd736a487e289.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/54079a8c0d978ec61aa270e9b78216a6.jpg')) >= 35
) AS merchant_game_covers_on_oss;
