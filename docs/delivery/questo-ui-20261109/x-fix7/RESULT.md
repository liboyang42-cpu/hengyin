# x-fix7：验收第五轮收尾（5 项）

分支 `launch/questo-20261110`，依据 `ACCEPT-ISSUES-R5-20261112.md`。每项一提交、推送后再做下一项。N-5（免费自助玩姓名/手机号规则）按业主决定未改。

| 编号 | 原因 | 最小修法 | 文件 | 测试 |
|---|---|---|---|---|
| N-18 | 俱乐部详情「管理」tab（`loadMessages`）对所有主理人都请求商家专用的 `/api/coop/list`，没有商家身份时控制台 403 | 请求前用 `roleGuard.isMerchantView()`（或页面已有的 `isMerchantViewer`）判商家身份；没有则不请求，按「没有合作邀约」落 ready 并刷新发出邀约过滤。x-fix3 的 403 当无邀约处理保留 | pages/club/detail/index.js | x-fix7-n18-coop-list-gate；club-invite-feedback-contract、club-detail-ia-contract 补商家身份前置并加无商家身份不请求的断言 |
| R5-1 | 主理人在局未准备时进玩页：`casebook` 对「没有局」抛 ServiceException、`/api/game/session/view` 对非 CLUB 主理人/总监抛 `GAME_SESSION_NOT_PREPARED`，都被包成 code 500 | `casebook` 没有局回空案件册（四个空列表，200）；`session/view` 对 `GAME_SESSION_NOT_PREPARED` 回 `AjaxResult.warn`（601，`data.reasonCode`），其他错误与 command 等接口不变。小程序本就把该 reasonCode 当「缺席」不报错、案件册空数据不出入口，沿用既有等待态 | PlayJourneyReadServiceImpl.java、ApiGameSessionController.java | Java `PlayJourneyReadServiceImplTest`（缺局改为断言空案件册）、`ApiGameSessionControllerTest`（新增未准备回 601）；mini x-fix7-r5-1-not-started |
| R4-5 / N-11 | 真正根因：每个解码字 `.cf__c` 自带 `white-space: pre`，父级的 `normal / break-all` 管不到字间折行，长标题整行排到卡外，两端被屏幕边切成「纸…」「…字」字影（x-fix6 去掉 overflow:hidden 后更明显）。与 WebGL 无关 | 通关卡内 `.cf--finale .cf__c` 改 `pre-wrap`（可折行、保留空格），并只裁横向 `overflow-x: clip`；不恢复会切半底部标签的 `overflow:hidden` | pages/play/components/chapter-flip/index.wxss | x-fix7-finale-ghost-glyph |
| P-6 | ① 膜 PNG 边缘有约 3800 个半透明白毛边像素（alpha 1–199），左下最多，深色底上显出毛边；② 包面没有主题名（g21 第三轮曾移除） | ① 把这些边缘像素置空（尺寸仍 330×577，包面比例不变）；② 按业主本轮要求，按原型方式印主题名（黑字、包高 26.5% 起，`topicName || chapter.name || modeLabel`），仍不印章数副标和操作提示 | pages/play/assets/pack-foil.png、pages/play/index.wxml/.wxss | x-fix7-pack-foil-edge；play-free-explore-shop-card-g21 的 g21 断言改为「只许主题名一个文字节点」 |
| P-5 | 站点详情滚动内容尾垫只有 48rpx，首屏内容长时到店三步落在固定「到店打卡」条之下 | `.fx-hero__pad` 高度改为 `var(--cy-btn-h) + 124rpx + env(safe-area-inset-bottom)`（= 底栏盒高，按钮高 + 上下内边距 + 安全区），滚到底时三步整条在底栏之上；旧注释的「只垫 48rpx」推算同步改写 | pages/play/index.wxss | x-fix7-hero-steps-above-bar |

## 与任务要求的差异 / 说明

- R4-5：任务说“找装饰层溢出并裁到卡内或隐藏”。实测溢出的不是装饰层而是正文字被 `pre` 排出卡外，所以改成折行并只裁横向，而不是隐藏解码层。DevTools 无 WebGL、未能真机复现，改动由 CSS 规则测试覆盖，需真机/DevTools 复拍确认。
- P-6 标题与 g21 第三轮「包面不印主题名」冲突，按本轮业主指令改并同步 g21 断言；章数副标与「轻点拆开」仍保持不出现。
- P-5 的尾垫比 x-fix 之前注释里“重复计一次”的结论更大，按业主指令执行；末行离按钮会比样机更远，如觉得过大可退回。
- 本地环境无微信 DevTools，P-5/P-6/R4-5 的视觉效果未做截图验证，仅有规则级测试。

## 门禁结果

- 全量单测（仓库根 `node --test chengyinhub-xcx/tests/unit/*.test.js`）：首轮 17371 项，通过 17370，失败 1（`club-detail-ia-contract`：该页测试夹具无商家身份，N-18 后不再请求 `/api/coop/list`，已补前置并加反向断言）；修复后整仓重跑：**17371 项，通过 17371，失败 0**。
- 动作台账 `build-action-ledger.js --write`：PASS 6028 控件（行号随改动位移，已写入）；路由矩阵 `--check`：PASS 171 路由 / 27 场景。
- danger-confirm：selftest 通过，PASS（53 个调用点）。
- ui-integrity：**UI-GATE-0:PASS**，无新增基线登记。
- keyframes 棘轮：随整仓单测通过，本轮无新增 `@keyframes`。
- WXS 语法门禁：PASS（14 个 .wxs，4 段内联）；新 WXSS 门禁、选择器门禁、组件选择器门禁均 PASS。
- 主包：源码侧 `mainpkg-size-report` 合计 3233KB（×0.633 ≈ 2046 KiB，与上轮持平，本轮未动主包文件；pack-foil.png 在分包）。
- 未改 `ci/architecture/*`、`ci.yml`；未跑 `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`；未标 ready、未合并。
- Java：`mvn -q -DskipTests package` 通过；`PlayJourneyReadServiceImplTest`（20 项）、`ApiGameSessionControllerTest`（18 项）通过。

STATUS: DONE（待 PR #779 CI 复核）
