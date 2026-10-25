Root composition: the u5-ceiling proposal is DECLINED/NOT_APPLIED. Preserve the original42 U5 reason keys and cap. Library reads use existing default client errors and keep original inline retry/context. C's3 consumed debt removals and exact same map patch relocation are retained; final map anchor8695 after removing one earlier flag line. Author and root-composed hashes are recorded separately.

# C · 119d 小程序静态 metadata 精确增量

parent `119d3f87bbf89162d47db837c404b361915d1b81`。仅修改 `chengyinhub-xcx/tests/lint/ui-integrity-baseline.js` 和本 C 证据目录；页面生产 JS/WXML、E invite、silent-error-allowlist、ledger/generator、实际 checker 文件均未修改。

按已提供的 miniprogram-development skill 与就近 AGENTS 保持最小影响；此次授权仅静态 metadata。未运行 Node、JS parser、控制、UI gate、微信、编译或 CI；没有截图/真机验收。执行范围只有文字读取/修改、JSON、SHA256 与 Git。

## 三种变更分别举证

| 类型 | 精确键 | 当前真实消费/来源 |
|---|---|---|
| 删除已还债 U4-DEBT | `pages/activity/baoming/baoming.js#activityId` | `getActivityInfo:649–677` 实际参数校验及 `/api/activity/info` 的原场次 ID；`handlePayment:1450` 的登录续接 target 显式读取 `this.data.activityId` |
| 删除已还债 U4-DEBT | `pages/activity/baoming/baoming.js#waitlistOfferId` | `handlePayment:1450` 的原账号/原场次/原 offer 续接 target 显式读取；`1500/1521` 参与候补支付闸、`1654/1684` 进入原 quote/create payload；不改原 offer/token 规则 |
| 删除已还债 U4-DEBT | `pages/square/detail/index.js#replyId` | `addReplyComment:264` 的原帖/原回复登录续接 target；`:267` 拒绝失效/已删回复；`:291` 写原 `reply_id` payload |
| 同语义移锚 | `pages/publish/fabu/index.js:8666→8696` | `buildRouteMap` 仍调用 `routeMapView.buildRouteMapView` 并 `setData(view.patch)`。与公共 fa5c 的旧8666比较，锚点上下各7行完整15行逐字相同；摘要与布尔结果在 evidence.json。producer 实际文件为 `pages/publish/utils/publish/route-map-view.js`，`:203–214` 固定四键，未选节点只添固定 `mapCenter`；原登记理由逐字保留 |
| 新读理由登记 | `pages/publish/fabu/index.js:8486` | 公共完整库分页 POST 只读；`current` 校验未卸载/账号/scope/登录epoch/token；局部 `fail` 置 `publicTempState=error` 且清列表。WXML `:986` cy-inline-error 绑定 `getPublicTempList` 重读；`showTemp:8404` 也仅在 error 时重读。分页/重复坏ID等失败不伪装 ready，无采用写入 |
| 新读理由登记 | `pages/template/index.js:564` | 整库分页 POST 只读；`current` 校验 seq/keyword/account/scope/登录epoch，`fail` 只写当前 game tab 的 errorMsg，finished/current 控制 loading。WXML `:60–61` 未加载列表的错误卡调用 `retryTemplateLoad:607→loadTab→getGameRows`；切到未加载 tab 时 `:491` 已清旧投影并设 listLoaded=false，旧tab失败不能冒充新tab状态 |

上述只登记原已存在的错误承接；没有删除错误、增加通配或把 UNKNOWN 当可重发。原 U1/原 U3、其他所有理由（含声音/席位原身份恢复）保持不变。

## 数量、摘要与唯一共享接缝

键摘要完全沿原 `baselineKeyDigest`：对精确键按序排序，以 `\n` 连接（无末尾换行），UTF-8 SHA256；只是静态 JSON 键/哈希计算，不执行 JS 或门禁。

| 集合 | 119d | C增量后 | keyDigest |
|---|---:|---:|---|
| U4-DEBT | 126 | 123 | `f375335de9bb4b957b74a224725b7e11c6980462227cf98217b087a6fb0bf41a` |
| U4-DYNAMIC | 67 | 67 | `0e35d463de88f05eb632cc978046798b78401867e05759ac4a3c0b8e782b63d4` |
| U5 | 42 | 44 | `4e05a135f31ffd5131486dbca90a30940eb45aa573617ef02053c7e18c96773f` |

U4 只减少债务/平移原调用，实际 frozen ceilings 不增加。U5 的原 map 在119d确有42个唯一键（旧摘要匹配），并非38；本次两条真实新增理由必须显式计为44，不能假称仍42、删其他有效理由或将两条改全局豁免。baseline 的对应 ratchet 同步44；实际 checker `ui-integrity.js` 仍为42，**本增量单独不声称门禁已闭合**。

`u5-ceiling.review.patch` 只给 root 唯一共享接线：在两条理由确认后，实际 checker 的 U5 常量42→44。C没有修改实际 checker，不修改任何 matcher/零负控/UNKNOWN围栏。若 root 的“不扩cap”裁决同样禁止这两条精确新增，则应暂缓本 U5 slice并返回明确替代接缝，不能以当前不一致宣称可通过。U4 slice独立成立。

## E invite：只有原parent上下文建议，未登记最终键

本增量不写 E 的源或名单，不猜 E 最终行号。parent 的 `pages/coop/invite/index.js` 有两条可交 root 对照 E 最终源的候选：

- parent `:425`，`loadSupplyTopic` 的 `/api/topic/list` 本人 scope 分页只读。`:406–417` session/hidden/unloaded/epoch/原scope闸及恢复session处理，局部 fail 写 supplyTopicState/error；成功检查当前主题 ID 和真实 productType；不是创建主题/据点授权。
- parent `:468`，`refreshSupplyStations` 的 `/api/coop/supply-stations` GET，固定原 topicId/merchantMemberId；`:445–466` 当前主题/商家scope/请求owner/epoch/页面闸，局部 fail 通过同一 update 写目标行 supplyState/error并释放该请求标记。`:478` 连接 fail 与 successStatusAbnormal。WXML 的原 supply 错误与重试由 E 最终源负责保留。

这些只是原 source/context 来源，不是两条已纳入键或最终数量。root应按 E 最终后像登记精确键、重算合成 U5 计数/摘要/共享常量，不能直接把本C-only的44/digest当合成值。
