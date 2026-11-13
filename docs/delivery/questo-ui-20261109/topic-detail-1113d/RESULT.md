# 主题详情页五处问题修复（1113d）

分支 `fix/topic-detail-1113d`，未碰地图 includePoints（pages/topic/index 地图部分不动）。

## 1. 章数不再把结局算进去
- 改动：`pages/topic/utils/topic-motion.js`（新增 `isEndingChapter`、`chapterCountText`，`storyMeta` 与 `buyChips` 改用）。
- 做法：结局的判法和章名标题代码一致（章名以「结局」开头）。总章数减去结局数得到故事章数，有结局写成「4 章 · 3 个结局」。故事页扇面副标题和自玩门票购买半屏的小牌都走这一处。
- 注意：后端只下发总章数，没有单独的结局数，结局数只能从已下发的章节列表里认。未购前列表只含预览章时，认不到的结局不会被减掉。
- 测试：`tests/unit/topic-story-meta-chapter-count.test.js` 新增 4+3、3+3 和购买小牌三条。

## 2. 故事页底部浮动按钮压住解锁卡
- 改动：`pages/topic/index/index.wxss`，解锁卡外层底部留白改为浮动按钮高度加安全区（168rpx + safe-area）。只加留白，不改浮动按钮行为。

## 3. 解锁卡文案
- 改动：`pages/topic/index/index.wxml`。没有商家数据时只写「解锁后可看全部故事线、答题揭秘」，有商家才带「与到店权益」。
- 测试：`tests/unit/topic-story-paywall-contract.test.js` 同步。

## 4. 通关进度条标签
- 改动：`pages/topic/components/cy/route-progress/index.js` 与 `index.wxml`。
- 做法：圆点全留。7 站以内标签照旧；多于 7 站只显示首、尾和当前站（当前站离首尾至少隔一格时才显示）。
- 测试：新增 `tests/unit/route-progress-label-fit.test.js`。

## 5. 票夹落在该主题的票
- 改动：`pages/topic/index/index.js`（三处进票夹入口带 `topicId`）、`subpackageMember/signup/index.js`（读取 `topicId`，主题票列表读完后落到该主题的主题票，只定位一次；找不到保持原顺序）。
- 测试：`tests/unit/l-after-items.test.js` 新增 `topicTicketIndex` 用例。

## 连带更新的生成物
- `scripts/uiaudit/route-matrix.json`：票夹页新增参数 `topicId`，按脚本 `--json` 重建。
- `scripts/uiaudit/action-ledger.json`：按 `build-action-ledger.js --write` 重建，`--check` 通过。
- 没有改 ui-integrity 基线，没有改 ci/architecture 和工作流。为了不挪基线行号，改动都做成了行数不变。

## 本地检查结果
- 全部单测（`node --test tests/unit/**/*.test.js`）：17589 项全部通过。
- `tests/lint/ui-integrity.js --warn-as-error`：UI-GATE-0 PASS。
- `scripts/js-scope-lint.js`：PASS。
- 主包大小：纯文本小改，无新增资源；CI 的主包与静态检查通过。

## CI（PR #826，提交 a3e65168）
全部 33 项检查：成功或按范围跳过（后端、迁移、管理端相关），无失败。含 20 个小程序单测分片、Mini program static gates、Fast gates、Secret scan。

STATUS: DONE
