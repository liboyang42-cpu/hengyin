# 主题详情：全部章节介绍 / 真地图 / 站点图回退（2026-11-13）

## 改动
1. **全部章节介绍**（`ApiTopicController.topicInfo`、`TopicChapterVO`）
   - 未解锁章节（下标 ≥ `unlockedChapterCount`）不再省略，改发预告 VO：`id/topicId/name/description/sortId/totalTime/totalMileage/imgArr(封面)/locationCount` + `locked=true`；`blocks`/`blocksJson` 为空，无 `routeGeometry`、音视频、氛围。
   - 预告节点仅 `id/name/address/latitude/longitude/imgUrl/sortId/businessTime`（无描述、hook、模板、打卡码、商家、答案）。
   - 分支图 teaser 规则（无节点）与承接方白名单不变；`totalChapterCount/unlockedChapterCount/storyLocked` 语义不变。
   - 小程序：故事页每章一张卡，锁定章节常开显示介绍、显示锁图标，点按走 `selfPlayBuy`（既有购票路径），不展开、不开玩；路线页列出全部站点；`_loadProgress` 回退计数与 `unlockedChapterCount` 兜底都排除 `locked` 章节。
2. **真地图默认**：有任意有效坐标即 `routeReal=true` 直接渲染 `<map>`；仅无坐标时用 `<cy-route-sketch>` 兜底。
3. **站点图回退**：`icon_line.jpg` → 主题封面 `img.first(info.imgUrl)`，仍为空则用深色中性块（`.item-pic--blank`）。全仓玩家侧仅此一处（其余为注释/自动化脚本）。
4. **章节标题**：`motion.chapterHeading`，名称已带「第X章/终章/序章/尾声」时不再加前缀。

## 测试
- Java：`ApiTemplateSecretsGateTest.topic_info_to_user_hides_story_after_first_chapter_without_ticket` 改为新契约（2 章，第 2 章 locked，无 blocks/模板/商家/节点描述）。按车道规则不在本地跑 Java，交 PR CI。
- 小程序：`tests/unit/topic-story-paywall-contract.test.js` 新增 3 例；topic-* / route-map-quality / uiux-player-state-adoption 等相关单测 332 通过 0 失败。
- `action-ledger.json` 已 `--write` 重建（页面行号漂移）；`ui-integrity-baseline.js` 中 `index.js:2121`→`2125`。

## CI(PR #819,head 87641cff)
全部通过:Backend tests(含 ApiTemplateSecretsGateTest 新契约)、Mini program static gates / static checks、Mini program unit shard 1–20、Fast gates、Migration layout/safety、Funds MySQL IT、Followup 39、redis recovery、gitleaks。
过程中两次 ui-integrity 红:U4-DYNAMIC keyDigest 因 `topic/index.js` 行号位移(2121→2125)及合入 master 后键集合变化需按门禁算法重算,已重算并推送;action ledger 本地与 CI 均 PASS。

STATUS: DONE
