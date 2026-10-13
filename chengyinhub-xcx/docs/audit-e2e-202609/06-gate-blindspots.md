# 阶段6 · 门禁盲区补扫(未挂线脚本 / CI 覆盖面 / 三条接缝)

## 先纠一处框架错误

代理报告按「有没有 npm script」判定 20 个 gate 脚本「未挂线」。**这个判据是错的**:这些脚本绝大多数由 CI 直接 `node` 调用,不需要 npm 入口。实测交叉核对:

| 脚本 | npm script | `ci/xcx-check.sh` | 结论 |
|---|---|---|---|
| undefined-token-gate | ✗ | ✓ `:89-90` | 已执法 |
| ui-theme-unit-lint | ✗ | ✓ `:119-125`(`--selftest` + `--theme` + `--units`) | 已执法 |
| component-attr-lint | ✗ | ✓ `:136-137` | 已执法 |
| wxml-handler-lint | ✗ | ✓ `:160-161` | 已执法 |
| js-orphan-handler-lint | ✗ | ✓ `:166-167` | 已执法 |
| setdata-dead-field-lint | ✗ | ✓ `:172-173` | 已执法 |
| a11y-tappable-name-lint / a11y-tap-target-lint | ✗ | ✓ `:181-193` | 已执法 |
| ui-validity-gates | ✗ | ✓ `:245-246` | 已执法(**当前为红**,见 F-GT-1) |
| build-action-ledger(`audit:actions`) | ✓ | ✓ `:222-223` | 双向已执法 |
| roam-redline-gate | ✗ | ✓ | 已执法 |
| cy-sheet-attr / component-path-resolve / component-wxss-selector / button-reset-specificity / pulldown-refresh / wxml-conditional / wxss-selector / a11y-* | ✗ | ✓ | 已执法 |
| **sticky-bottom-bar-gate** | ✗ | ✗ | **真孤儿**(见 F-GT-2) |
| state-visibility-gate | ✗ | ✗(直接) | **不是孤儿**:被 `scripts/ui-validity-gates.js:13` 收编为 U6 带基线执法;裸跑 39 条(27 RAW_ERROR_TEXT + 12 BLANK_STATE_BRANCH)是**基线内存量**,不是新增 |
| release-legal-gate | ✗ | ✗ | 纯 `module.exports` 无 main,真调用在 `scripts/preflight-release.js:8` —— 形态问题不是漏挂 |
| requestbody-dynamic-url | ✗ | ✗ | 同上,被 `tests/lint/requestbody-contract.js:31` require |

**注意一个容易踩的坑**:`npm run lint:ui` 跑的是 `tests/lint/ui-integrity.js`,**不是** `ui-validity-gates.js`。名字相近但两码事,阶段0 的 `lint:ui` 绿 ≠ `ui-validity-gates` 绿。

## 发现

### F-GT-1 [P1] 本分支现在开 PR 必挂 CI:U4/U5 两条红是本分支自己造成的

本地实跑:

```
$ node scripts/ui-validity-gates.js   → EXIT=1
U4:未通过（相对 7860bfa0f... 新增 2 个死 setData 字段）
U5:未通过（86 文件 / 233 个调用有 1 项白名单问题）
pages/publish/temp/index.js:638 setData 字段 themeName [A1] 完全死
pages/publish/temp/index.js:639 setData 字段 nodeName [A1] 完全死
```

判据没错、基线也没动过 —— 是**代码把 A2 变成了 A1**:

- 基线里登记的是 `pages/publish/temp/index.js::themeName::A2`、`::nodeName::A2`(`scripts/baselines/u4-dead-setdata-field.json:222,226`,master 与 HEAD 同一份)。
- A1/A2 的分界是 `jsReadsDataField()`(`scripts/setdata-dead-field-lint.js:117,44-45`):js 自己还读 = A2,谁都不读 = A1。
- master 的读者存在:`pages/publish/temp/index.js:839` `shopName: this.data.nodeName || ... || this.data.themeName`、`:842` `playStyle: this.data.themeName || ''`。
- HEAD 上这两行没了(`grep this.data.themeName` 零命中),删除者正是本分支的 `02f9105f1`(UI 全量审查四批落地)。
- CI 跑它:`ci/xcx-check.sh:245-246`。

影响:不是「门禁坏」而是「分支欠账」。修复批要么把 `themeName/nodeName` 连同 setData 一起删干净(`onLoad` 里 `:638-639` 两处入参解码),要么补 `--update-baseline` 并说明理由。U5 的白名单漂移(`白名单 5,实际 4`)同文件同批,一并收窄。

### F-GT-2 [P2] sticky-bottom-bar-gate 是唯一的真孤儿,且它当前是红的

全仓 `grep "sticky-bottom-bar-gate"` 在 `ci/`、`package.json`、其他脚本里**零命中**(只有脚本自身)。本地实跑:

```
$ node scripts/sticky-bottom-bar-gate.js → EXIT=1
底栏不透明门禁不通过(扫了 338 个 wxss):
  ✗ components/cy/popover/index.wxss {.pop__catch}
  ✗ pages/play/components/playkit-dailysign/index.wxss {.ds}
  ✗ pages/play/index.wxss {.fx-bg} {.fx-hero}
  ✗ pages/roam/index.wxss {cy-proto-cam}
```

裁决点:这 5 条是真要修的透底,还是规则误判(`.fx-bg`/`.fx-hero` 听名字像**背景层**而不是底栏,`cy-proto-cam` 像相机浮层)。挂线之前得先裁决,否则一挂就常驻红。

### F-GT-3 [P1] 四条 automator 冒烟全在 CI 之外,红的那个没人看见

`package.json` 里有 `test:automator` / `test:automator:merchant-ledger` / `test:automator:ai-entries` / `test:automator:stamp-camera-crop`,但 `grep -n "automator\|stamp-camera" ci/xcx-check.sh` = **零命中**。

后果实测:`test:automator:stamp-camera-crop` 目前是**红的**(EXIT=1,已在 `04-stamp-camera-crop.log` 留证),因为不在 CI 里,它红多久都没人知道。另外三条未挂 script 的冒烟(`tests/automator/merchant-aftercare-smoke.js`、`tests/automator/publish-flow.js`)同理。

裁决点:模拟器测试要 DevTools 服务端口,挂进 CI 有基础设施成本;折中是把「有确定性断言、不依赖登录态」的那几条(如 stamp-camera-crop)先挂,并先修红。

## 三条接缝现状(上轮根因追查)

| 接缝 | 上轮结论 | 本轮 |
|---|---|---|
| 事件契约 `triggerEvent` ↔ 宿主监听 无 lint | 缺 | **本轮已造出尺子**:`docs/audit-e2e-202609/event-contract.js`(可复跑),扫 317 wxml / 495 js,得 **23 条「组件抛了但没有任何宿主监听」**。逐条分诊在 `04-playkit-event-contract.md`:真缺陷 6 条(=F-PK-1~4)、组件内部已自消化 7 条、渲染条件进不去的死能力 8 条、无害中继 2 条。⚠️ 挂线前必须先裁那 8 条死能力,否则一挂就常驻 8 条红。尺子自身有两个必须带来的正则教训:`bindtap` 无冒号等价写法(首版因此多报 1 条)、`{{x > 0}}` 里的 `>` 会截断属性串 |
| nav-route 与 tabBar 集合匹配 | 疑缺 | 已有单测覆盖:`tests/unit/nav-route-exists.test.js`(内含 topicadd 到期注释 `:172`),且随 `test:unit` 进 CI。不算接缝 |
| requestbody 双向对账 | 疑单向 | **已是双向**:`tests/lint/requestbody-contract.js:31` 收动态 url 解析,`:124` 明确「跨模块反向读 `../chengyinhub-admin`」。口径闭环 |
