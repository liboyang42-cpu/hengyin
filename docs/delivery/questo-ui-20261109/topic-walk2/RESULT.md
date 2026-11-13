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

CI 结果见 PR。

STATUS: PARTIAL - 待 CI 结果
