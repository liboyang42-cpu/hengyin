# 阶段0 · 基线复跑(重建件,原始输出已落 `logs/`)

上轮这份文件的原始日志没落盘(见 `report.html` 第四节「证据缺口」)。本轮重跑并把**每条命令的完整输出**存进 `logs/`,表里只写结论,细节以日志为准。

跑法:`bash docs/audit-e2e-202609/run-baseline.sh`(门禁)、`bash docs/audit-e2e-202609/run-automators.sh`(模拟器四条)。

## 一、CI 会跑的门禁

| 命令 | 结果 | 日志 | 关键数字 |
|---|---|---|---|
| `npm run test:unit` | 绿 EXIT=0 | `logs/test-unit.log`(483KB 全量) | 5412 pass / 0 fail / 2 skipped,135.8s |
| `npm run lint:ui` | 绿 EXIT=0 | `logs/lint-ui.log` | Legacy error 0 warn 0,UI-GATE-0 PASS |
| `npm run lint:scope` | **先红后绿** | `logs/lint-scope.log` | 红是我自己带的:`docs/audit-e2e-202609/event-contract.js` 用了 Node 全局 `process`(该目录**在**生产 JS 扫描范围内)。改成不碰 Node 全局后 `JS-SCOPE PASS: 497 个生产 JS,未解析标识符 0`(条数会随并行批的增删浮动,不是本轮漂移) |
| `npm run lint:requestbody` | 绿 EXIT=0(3 warn) | `logs/lint-requestbody.log` | 三条 warn 本轮全部人工核完,见下 |
| `npm run audit:actions` | 绿 EXIT=0 | `logs/audit-actions.log` | **3969 controls, 0 evidence results** —— 台账只管「接没接线」,0 条运行证据是常态,不代表验过能跑 |
| `npm run lint:danger-confirm` | 绿 EXIT=0 | `logs/danger-confirm.log` | 34 危险调用点,26 不可逆全部有确认弹窗 |
| `node scripts/ui-validity-gates.js` | **红 EXIT=1** | `logs/ui-validity.log` | U1/U3/U6 过,**U4/U5 红** → 编号 #11 |

`lint:ui` 绿 ≠ `ui-validity-gates` 绿:两个工具。这条坑见记忆「门禁执法地图」。

### U4/U5 红的归因(本轮钉死)

```
U4 FAIL:pages/publish/temp/index.js setData 顶层字段 nodeName 未被 WXML 消费
U4 FAIL:pages/publish/temp/index.js setData 顶层字段 themeName 未被 WXML 消费
U5 FAIL:调用数变化:pages/publish/temp/index.js(白名单 5,实际 4)
```

- 基线里登记的是 `::nodeName::A2`、`::themeName::A2`(`scripts/baselines/u4-dead-setdata-field.json:222,226`),A1/A2 分界 = JS 自己还读不读(`scripts/setdata-dead-field-lint.js:117,44-45`)。
- `origin/master:pages/publish/temp/index.js:510-511` 写、`:716/719` 读(`shopName: this.data.nodeName || …`、`playStyle: this.data.themeName`)→ master 上是 A2,不红。
- 本分支 `02f9105f1`(UI 全量审查四批落地)把 `onNodeAiGenerate` 整块删了(连同 `/api/ai/template/fill` 那次 `silentError:true` 调用),读者归零 → A2 跳 A1、U5 计数 5→4。**基线没动,代码变了。**
- 删除本身是干净的:`temp/index.wxml` 里 `cy-ai-assist`/`aiNode*` 也已全清,`tests/lint/ui-integrity-baseline.js:65` 记着这笔账(「onNodeAiGenerate / onNodeAiCancel 两个 handler 零绑定」)。所以这是**欠基线**,不是功能坏。

### requestbody 三条 warn 的核账结果(全部排除)

| warn | 结论 |
|---|---|
| `pages/merchant/reviews/index.js:151` `method=requestOptions.method` | 排除。该分支只带 query 参数、无 `data`;后端 `ApiMerchantReviewController.java:40-42` 用 `@RequestParam` 收 → urlencoded 默认头打不到 `@RequestBody`,不会 415 |
| `pages/merchant/reviews/index.js:160` 同上 | 排除,同因 |
| `components/cy/profile/index.js:55` `url=url` 动态 | 排除。`req(url,data)` 的 16 个落点本轮逐个对到后端签名:`/api/user/follow/action`(`ApiUmsMemberController.java:835-839` 裸参 `String follow_member_id`)、`/api/recommendation/list`(`ApiRecommendationController.java:27-28`)、`/api/registration/join_info`(`ApiRegistrationController.java:550-554`)、`/api/common/banner`(`ApiCommonController.java:191-196`)、`/api/creativesquare/list`/`like`(`ApiCreativeSquareController.java:630/883`)、`/api/points/result_list`(`ApiPointsController.java:40-42`)—— **全部裸参/`@RequestParam`,无一处 `@RequestBody`** |

## 二、CI 不跑的四条模拟器冒烟(编号 #12)

| 命令 | 结果 | 日志 |
|---|---|---|
| `npm run test:automator`(login-smoke) | **绿** | `logs/automator-.log` — `route = pages/merchant/index/index` |
| `npm run test:automator:merchant-ledger` | **绿** | `logs/automator-merchant-ledger.log` — 但它靠 `page.setData` 灌假数据断言渲染,见 #32 |
| `npm run test:automator:ai-entries` | 红(环境) | `logs/automator-ai-entries.log` — `pageStack 在 5000ms 内未返回` |
| `npm run test:automator:stamp-camera-crop` | 红 | `logs/automator-stamp-camera-crop.log` — `reLaunch` 处断连 |
| `npm run test:dead-ui` | 红(环境) | `logs/dead-ui.log` — 同上「拒绝假绿」 |

后三条是**同一个症状**(DevTools 已连、逻辑层页面栈拿不到)。本轮 DevTools 与并行 UI 会话共用一个实例,复跑时正好撞上它被占走;这三条本轮**未验**,不算代码结论。

## 三、未挂线脚本(补跑留证)

| 脚本 | 结果 | 日志 |
|---|---|---|
| `node scripts/sticky-bottom-bar-gate.js` | 红 EXIT=1,5 条 | `logs/sticky-bottom-bar-gate.log` — 与上轮同一批(`.pop__catch`/`.ds`/`.fx-bg`/`.fx-hero`/`cy-proto-cam`),复现稳定 → 编号 #28 |
| `node scripts/roam-redline-gate.js` | 绿 EXIT=0 | `logs/roam-redline-gate.log` |

## 四、事件契约探针(本轮新尺子,可复跑)

`node docs/audit-e2e-202609/event-contract.js` → `logs/event-contract.log`
扫 317 个 wxml / 495 个 js,`<component is>` 动态引用 0 个,得 **23 条**「组件抛了但没有任何宿主监听」。逐条分诊见 `04-playkit-event-contract.md`(A 类真缺陷 6 / B 类组件已自消化 7 / C 类渲染条件进不去的死能力 8 / D 类无害中继 2)。
首版探针曾报 24 条,多出来的那条是尺子自己的假阳性:`bind:tap` 与 `bindtap` 在 WXML 里等价,只认带冒号的写法会把 `pages/club/detail/index.wxml:330` 的 `catchtap` 判成没接 —— 修尺子后从 24 降到 23。上轮记的 A1=24/A2=38/B=0 里只有这个数量级能对上,其余两档口径没留文档,本轮不再引用。

## 五、收尾复跑(计划书要求:证明本轮纯只读)

七条门禁逐条复跑,输出留 `logs/verify-readonly.log`。结果与**归因**:

| 命令 | 收尾这次 | 归因 |
|---|---|---|
| `lint:ui` / `lint:scope` / `lint:requestbody` / `audit:actions` / `lint:danger-confirm` | **全绿** | 与阶段0 一致。`audit:actions` 控件数从 3969 变 3966,是并行 UI 批删了控件,`--check` 仍 PASS |
| `npm run test:unit` | **红 3 条 EXIT=1** | **三条全是并行 UI 批的在途改动,不是本轮产生** —— 本轮唯一写入是 `docs/audit-e2e-202609/`(`git status` 里只有这一条 `??`) |
| `npm run test:dead-ui` | 红 FATAL「DevTools 已连接但未出现逻辑层页面栈,pageStack 在 5000ms 内未返回」 | DevTools 与并行会话共用一个实例被占走;与那三条 automator 冒烟同一类环境问题 |

三条单测红的逐条定位(下一轮的账,别记进本审查):

1. `tests/unit/action-ledger.test.js:813` —— 期望 3966、实得 3967:并行批新加控件后**没重建台账**。修法即记忆里那条「改 JS 必重跑 `audit:actions`」。
2. `tests/unit/action-ledger.test.js:959` —— 同因(「release 前重建 inventory」负控在如实报账)。
3. `tests/unit/nav-route-exists.test.js:190` —— `/pages/topic/pricing/partner/index` 该从孤儿页豁免表里删:`git diff` 有一条新增 `url: '/pages/topic/pricing/partner/index?topicId='`(落点 `pages/topic/pricing/index.js:180`),跳转刚被接上,豁免表过期了。

> **本轮没有造成任何基线漂移。** 当前分支的红灯有两类来源:本分支自己的 U4/U5 欠账(#11),与并行 UI 批的在途改动(上面三条)。开修复批前先让并行批落定,否则台账类断言会一直抖。
