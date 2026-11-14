# 主题详情章节导读（topic-chapter-guide）

改动均在 `pages/topic`，复用 `utils/topic-motion.js` 的纯函数，页面只调用。

1. 路线进度：`progressFallback(info)` 按整条路线（含未解锁预告章站点，且不小于 `locationCount`）给总数与站点标签，完成数恒为 0，真实进度仍只来自 `/api/play/nodes`。
2. 故事卡正文：`chapterGuide(chapter)` 取章节简介，没有则取首个文本块开头，最多约 60 字并按句截断，样式限 3 行省略；解锁与未解锁章节同口径，详情页不再出现整章剧情。
3. 章节卡图片：`pickChapterImages` 按「章节图 → 本章站点图（站点图、商家图、玩法图）→ 主题封面」取图，优先选前面卡片没用过的图，有图就不会出现空卡；被后端回填成主题封面的玩法图不当作本章图。
4. 地图底部说明：改为单行省略并抬高，避开地图自带标识。

测试：`tests/unit/topic-detail-chapter-guide.test.js`（新增，覆盖进度、导读、配图）；`topic-story-paywall-contract.test.js` 里旧的「锁定章节不计入进度」断言改为新口径。

CI：PR #822 最新提交上全部检查通过（含 20 个小程序单测分片、静态门禁、gitleaks）。台账与 ui-integrity 基线仅因行号位移重算（action-ledger 用其 --write 工具，U4-DYNAMIC 锚点 2125→2123）。


STATUS: DONE
