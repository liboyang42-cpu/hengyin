# d192 已有小程序 CI 失败收口

父源：`d19276f56b3eb2a34ed8fab06e41ea8ea654a0d0`，独立分支 `codex/architecture-day-c-mini-gates-d192`。仅处理 run40122522919 shard11 的15个未知多写及 static U4/U5/U6；不重做已集成声音3579/ASR289e，不改 Java/资金/登录运行时。

## 源码闭环

- 14个注销组件控件此前因 `methods.X.call(this)` 与 `initialData()` 导入保守地合并整个 `utils/deregister-flow.js`（17879字节）。生成器现在验证真实导出、顶层无额外副作用及单一return委托，再按真实方法/本地helper/this调用闭包展开；未知导出、附加入口动作、顶层副作用直接拒绝，不吞API。完整共享模块仍进入sourceFilesFor/fingerprint。
- 注销apply仍完整保留 `/api/compliance/consents` POST→原apply POST；验证失败只到同意，apply拒绝/超时后只读原status GET，不再写apply。其互斥分支逐项登记；少同意接口或新增其他业务API会拒绝。sendSms/cancel/loadStatus/UI-only控件不混入其他操作。
- 余下据点码onRetry：真实仅issue写入；导入DYN_TTL_MS是字面常量，不是调用verification scanner。仅在真实const Literal+纯模块/原导出可证明且没有把常量当函数调用时不合并scanner；未知/可调用值仍走保守路径。没有忽略真实核销或声音enroll/consent。
- 两页声音错误用已有cy-inline-error承接；节点补真实组件声明，UNKNOWN错误恢复CTA仅调用refreshVoice查询原请求，未改变journal/原身份/确认新建逻辑。
- U4：3条旧精确key换到当前1711/1167/2009，原理由不变；新增5处route-preview投影的逐调用理由。只接受固定八字段，服务端不供键；七个展示字段直接消费，routeIsRoad为同源固定道路判定元数据，未伪称单独可见。未改其他owner页面/共享投影源码。
- U5：原milestone7778→7813，原理由不变；新增citynode原结果读回519、带队原席位命令4865、seatRecovery62的具体理由，逐一追到workflow UNKNOWN可见提示，读不到保留原号、不得当未执行或自动二发。没有全局前缀/静默豁免。

## root 单一持有的冻结配置（本补丁不改数值/摘要/生成JSON）

精确key集合需由root按上面实际登记串行审定；当前冻结门禁仍会拒绝旧数值/摘要，不能宣称UI-GATE-0总通过。证据登记与冻结配置分开，不自动放宽上限。

| 集合 | 当前逐项key数 | 排序key SHA-256 |
|---|---:|---|
| u4Dynamic | 67 | f7477935d7b05fc137d466d6136ecd6cce305c132b6ceca301b2806cf37e6675 |
| u5Silent | 42 | d96346f098b9b6d546e4ee9af888984021d1a27e7d98ee280812ff3be2d2e4ac |

根端同步位置：`tests/lint/ui-integrity-baseline.js` 的ratchet/keyDigest及 `tests/lint/ui-integrity.js` 的NON_INCREASING_CEILING；现有62/39、65/39数值保持原样。action-ledger.json与台账snapshot常量均未写入；两声音错误组件新增action绑定，root重建最终库存后取实际计数，不借旧数。

## 配套源码与实际证据

新增两个直接小测试类共6例（NOT_RUN）：共享17KB注销owner范围/哈希依赖、同意及原status互斥链+缺接口/额外写负控、TTL实际常量/调用未知值负控；固定路线字段及精确U4删除理由负控；原席位恢复逐调用U5理由删除负控；既有错误组件与替回裸文本必红负控。原action-ledger.test.js零未知多写断言及全部旧断言均未修改。

实际仅做源码静态解析、Node --check、git diff --check：内存buildLedger记录未知多写0（`/tmp/c-mini-final-source-contracts.txt`，无--write）；已知失败6个文件的scanU4/scanU5均空违规、两声音WXML analyzeWxml均空违规（`/tmp/c-mini-ui-targets.txt`）。源码重建早于最终仅增加的拒绝性解析检查与理由文字，不作为最终source测试验收。

未运行任何回归测试、Java、数据库、CI、DevTools、供应商外呼或DDL；由root最终统一验收。回退仅本解析/证据/错误组件增量，不撤原意图与权限保护。

## root 集成补记

本轮root已逐项核对证据并同步67/42及其精确digest；这替代上述“根端尚未同步”的时间点说明。最终台账5121/localtap2185/event2541，按完整variant对照后冻结。注销apply新增真实external能力分类，write效果/有序请求/权威写验收均保留；saveDraft与profile旧variant无此次语义改变。未运行最终组合测试，仍NOT_RUN。
