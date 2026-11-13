# DEC-UI-1 · 业主决定 D-38 / D-46 / D-56 / D-63 / D-78 / D-119 落地

分支 `claude/dec-ui-1-20261112`，基线 `origin/master` 1c80c5ae。只改各条决定点名的内容，其余版式、文案、颜色不动。

## 逐条

### D-38 紧急求助：不要
- 改动：删掉游玩页工具菜单里「紧急求助」一行与 `cy-option-sheet`；删除整个 `pages/play/utils/play-sos.js`（拨 110/120、复制位置）及 `index.js` 里的 `playSos` 引入、方法、`sosOpen/sosItems` 数据。后端接口未动。
- 文件：`pages/play/index.wxml`、`pages/play/index.js`、`pages/play/utils/play-sos.js`（删）。
- 测试：`f-compliance-launch.test.js` 原「紧急求助」合同改为断言入口与代码已不存在；`action-ledger.test.js` 台账数字与控件 id 位移（6025→6024、tap 3029→3028、local tap 2650→2649、选章行 354→353 等）。

### D-46 章节开场和完成屏：点了才能进
- 改动：`chapter-flip` 去掉 `END_HOLD_MS`（5.2 s 完成屏自动溶解）与 `START_AUTO_MS`（3.6 s 开场自动进正文）两条自动路径，只在玩家点按钮（或屏上任意处，原有行为）时前进；2026-09-24 的注释已改写为 D-46。
- 文件：`pages/play/components/chapter-flip/index.js`。
- 测试：`l-play-20261110.test.js`、`chapter-flip-round3.test.js` 原「停留后自己进 / 5.2 秒自己吹散」改为「不点不进、点了才进」。

### D-63 失败图落地不抖
- 改动：`cy-illus` 风格 B 失败图删掉落地后的左右摆动（`.ib--fail.e1–e3 .x-tile`），时间表 `enter` 由 `[640,750,975,1000]` 改为 `[1000]`（原 e4 的裂开改名 e1，时刻不变）；落地下落、裂开、云飘来保持。四个分包副本同步。
- 文件：`pages/play`、`subpackageJourney`、`subpackageMember`、`subpackageP3` 下 `components/cy-illus/{index.wxss,art-b.js}`（`art-b.js` 的原型逐字段落未动，sha 契约仍过）。
- 测试：新增 `dec-ui-1-20261112.test.js`。

### D-56 翻页卡：左右滑也能翻
- 改动：指认嫌疑人翻页卡组加 `bindtouchstart/touchend`：横向位移 ≥ 40px 且 ≥ 纵向的 1.5 倍才算，左滑下一位、右滑上一位；仍走原 `is-out / is-in` 过渡，没有新 `@keyframes`；点按、箭头、「再想想」照旧；卡内竖向滚动不受影响。
- 文件：`pages/play/components/playkit-accusation/{index.js,index.wxml}`。
- 测试：`dec-ui-1-20261112.test.js`（左滑、右滑、短滑、竖滑、循环、点按）。

### D-78 没有封面不能保存
- 前端：存云端草稿（手动）时无封面 → 不发请求，主题详情弹窗里封面行下出现既有红字（`cy-inline-error kind="field"`，复用发布校验已有的 `errors.imgUrl`），并提示「请上传主题封面」，同时打开详情弹窗；自动保存静默跳过（本机自动保存不受影响）；上传封面后清红字。发布原本就由校验袋拦（`请上传主题封面`）。
- 后端：`ApiTopicController` `/create` 草稿同样要封面（原来只拦发布）；`/update` 对本人主题，本次传空封面或已存封面为空时拒绝，业务提示同为「请上传主题封面」；归属校验不变。
- 文件：`pages/publish/fabu/{index.js,topic-detail-sheet.wxml}`、`ApiTopicController.java`。
- 测试：`fabu-server-draft.test.js` 新增 D-78 用例、其固件与 `fabu-cloud-autosave.test.js` 固件补封面；Java `TopicCreateBusinessErrorPassthroughTest` 新增「草稿无封面被拒」。Java 测试按规定不在本地跑，交 CI。

### D-119 金额也写 0
- 改动：`money.wxs` 的 `amount` 空值 `—`→`¥0`、`amount2` 空值 `—`→`¥0.00`（全仓 wxml 引用随之）；JS 里显示用的金额空值：合作结算详情三处分润、订单详情金额、视频悬赏应付、对账行已执行调整、主办层级每人次上限（`0`）、定价页固定费（`0.00`）。
- 保持「—」：非金额的未设置项（如分成比例、结算窗口等）；未改的还有结账页应付总额 `pay === null`（报价加载中 / 被拒时写 ¥0.00 会被读成免费，需业主另行确认），以及开发布票务里「开售前定价」一行。
- 测试：`blank-value-display-contract`、`money-two-decimals-r3`、`coop-settlement-detail-state-contract`、`order-center-entry` 中钉住旧「—」的断言按 D-119 改为 0。

## 门禁
- `node --test chengyinhub-xcx/tests/unit/*.test.js`：红的只有上面按决定更新过的合同，更新后全绿（17488 条；全套最后一次整跑 3 条红均为台账数字，已修并单独复跑 `action-ledger.test.js` 48/48）。
- `ui-integrity.js`：UI-GATE-0:PASS，基线无新增（`fabu/index.js` 新代码放在文件末尾的函数里，避免挪动既有 U4 基线行号）。
- 台账 `--write` 后 `--check`：PASS 6024 controls；路线矩阵 `--check`：PASS 171 routes / 27 scenes。
- WXS 语法门禁、WXSS 选择器 / 注释门禁、危险确认门禁（53 点）通过；keyframes 棘轮由单测覆盖，全绿，无新增关键帧。
- 未运行 `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`；未改 `ci/architecture/*`、`.github/workflows/*`。

## 截图
走查 rig 需要 Docker 后端，云端容器按规定不起，前后对比截图未能生成。建议在开发者工具里看：游玩页工具菜单（无紧急求助）、章节开场 / 完成屏（不再自动前进）、失败图（落地不抖）、指认卡（左右滑）、主题详情（无封面存草稿的红字）、金额空值（¥0.00）。

STATUS: PARTIAL - 无前后对比截图（走查 rig 在云端无法运行）；Java 测试待 CI
