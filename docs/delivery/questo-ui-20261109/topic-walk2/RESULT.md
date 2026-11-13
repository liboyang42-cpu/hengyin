# 主题详情 DevTools 走查第二轮（topic-walk2）

叠在 `fix/topic-detail-chapter-guide-20261113` 之上，改动均在 `pages/topic`、`pages/templatedetail/topic-preview`、`utils/topic-route-preview.js`。

1. 锁定章节卡锁图标：`.story-cc.is-locked .story-cc__chev { transform: none; }`，不再吃展开态的 `rotate(-90deg)`。
2. 结局章标题：`chapterLabel/chapterHeading` 对「结局/终章/尾声/序章/第X章」开头的章名不加序号前缀；故事卡 label 为空时扇形卡正面用标题，章节卡不渲染空标签。
3. 路线地图取景：取景点本就含全部站点，问题是 `<map>` 未铺出时 include-points 被吞、中心取首站。现中心改为包围盒中心；切到路线页（400/1200ms）及路线数据到达后用 `mapContext.includePoints({points, padding})` 再取景一次。未新增控件绑定。
4. 第二屏简介：超过 20 字截断时补省略号，不再硬切。
5. 无封面占位：`display:block` 压掉了 `cy-cover-fb` 的居中，插画落在左上盖住返回键；改回 flex 并加顶部留白。
6. 章节导读：`chapterGuide` 在 `【题目】/【观察位置】/密码表/A=1` 等标记前截断，简介与文本块同口径；锁定与未锁定卡共用，样式 3 行省略，不会盖住卡头。
7. 主题模板预览：新增 `topic-preview/intro.js`，用页面已取到的 `/api/template/topic-template/info` 里的简介与章节名/导读，在标题下展示，沿用 `chapterHeading/chapterGuide`。

测试：`tests/unit/topic-detail-walk2.test.js`（新增）；`dw29-v22-v23-topic-detail`、`topic-preview-input-contract` 随逻辑对齐。`tests/unit/*.test.js` 全量仅剩 master 上已存在的 2 项失败（action-ledger inventory 重建、五处路线动态投影）。未跑 selftests，未改 ci/architecture 与 workflows。

补充：第二次提交把模板预览的章名/导读逻辑改为分包内同口径副本（分包互引资源门禁），标题字号改走 token（DS 门禁）。PR #823 CI 全绿（静态门、20 个单测分片、gitleaks 等）。

STATUS: DONE

## Rebase 1113

#822 已 squash 合入 master(1ca6d321),本分支合并 origin/master(无历史改写、无 force push);7 项走查修复均保留。

- `topic-motion.js` 冲突:保留 #822 的导读/进度,叠加本分支的题面标记前截断与结局类章名不加序号。`topic-chapter-guide/RESULT.md` 取 master 版。
- 生成物:`build-action-ledger.js --write` 后 `--check` 通过(6043 项);`build-route-matrix.js --check` 通过(173 路由)。
- `ui-integrity-baseline.js` 取 master 侧,仅 buildRouteMap 的 U4-DYNAMIC 锚点 2123→2134(本 PR 自身给该调用加了 `_fitRouteMap` 回调),u4Dynamic keyDigest 取门禁 `baselineKeyDigest()` 实算值,附一行带日期注释;条数与 ratchet 未改,无新增登记,未改门禁。
- 首轮 CI(e0bf093a)红两项,均为真实问题:模板预览 `intro.js` 跨分包 require `topic-motion.js`、`topic-preview/index.wxss` 硬编码 `29rpx`。324dc055 已改为分包内同口径副本(测试钉住一致)与 `var(--cy-font-body)`。
- 合并后头 3b848b9a:GitHub CI(含小程序静态门禁与全部单测分片)与 Funds MySQL IT 全绿,master 上无同类失败。
- 未跑 selftests,未改 ci/architecture 与 workflows。

STATUS: DONE
