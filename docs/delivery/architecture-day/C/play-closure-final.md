# C：最终 UI 锚点与 ThemeRuntime 原命令夹具收口

结论：两项 UI 登记仅迁移原调用位置，未扩大基线；最终源码 UI-GATE-0 实跑 PASS。ThemeRuntime 的三个 CI error 均对应夹具重放时把原 expected 改成 0，已保存并复用原命令，补齐异语义拒绝及零变化断言。Java/MySQL 未运行，不能报告这些 IT 已通过。

## 基线、范围与候选

- 公共仓库 `liboyang42-cpu/hengyin`，独立工作树 `/workspace/hengyin-c`，分支 `codex/architecture-day-c-play-command`。
- 本 delta parent：`9da43f76ddd54bfdf0b24a538a1b80d47c5384bd`；该 parent 已含 Photo 四阶段与 ClubLead 两处锁读 flags。a480/9da 及更早冻结候选不重写。
- 代码只改 `chengyinhub-xcx/tests/lint/ui-integrity-baseline.js`、`chengyinhub-admin/src/test/java/com/chengyinhub/business/it/ThemeRuntimeMySqlIT.java`；其余为本任务交付证据。不修改 root 联合控件台账、E/G mapper 或 root 独占 Photo 文件。
- root 参考真实 CI：af712/run40030174071，后续 MyBatis 阶段 387 条、0 failure、3 error。下文是源码定位和夹具修正，不替代该阶段重新实跑。

## UI 两项原语义核对

以公共基线 `2ec68899099a1e250df0949a8383bf85b5d3ffe0` 对照最终 9da 页面：`_settleStory()` 和 `loadMilestone()` 两个完整方法逐字节相同，包含原锚点语句。方法 SHA-256 和消费路径见 `evidence/ui-gate-closure/source-proof.json`。

- U4：1620 → **1708**，仍只生成 `chapterParas[i]` / `chapStory.lines[i]` 的 `in/dir/d` 路径，原数组身份和章节模式守卫保留；WXML 真消费两个 namespace。
- U5：7688 → **7776**。这是最终 Photo 页面位置；af712 的 7772 尚未叠加 Photo 的 4 行。原 `/api/play/my-completed` 读取仍先清空 milestone，失败/无效结果保持 null，WXML `wx:if="{{milestone}}"` 隐藏轴；迟到、切号和页面代次检查未变。
- 删除旧键，原理由原样保留；u4Dynamic 62→62、u5Silent 39→39，所有 ratchet/ceiling 不变。仅对应两项排序键 SHA-256 摘要重算，其他 baseline 内容的结构等价断言通过。详见 `baseline-proof.json`。

实际命令及输出保存在同目录：

- `node tests/lint/ui-integrity.js --warn-as-error`：修前 exit 1，精确四项红；修后 exit 0，**UI-GATE-0:PASS**。
- `node tests/lint/ui-integrity.js --selftest --warn-as-error`：exit 0；U1–U5、未知来源、存量过期/增长/换键摘要、WARN-AS-ERROR 等原变异负控通过，未修改门禁。
- 原 `ui-integrity-selftest-cli.test.js`：初次 sandbox 执行 1 pass/2 failure，两个子进程正例输出为空，原 TAP 保留；批准本地 Node 子进程执行后复跑 **3/3 pass、0 skip**，包括“默认 CLI 策略误用必须判红”的负控。不能把两次执行合称一次全绿。
- 归档输出只去除行尾空格，未改计数或失败内容。

## 三个 MyBatis error 的命令链

`r5FeedbackStartsOnlyAfterSettlementAndNeverResettlesTheMainResult` 的 PASS/FAIL 两个参数：首次 `CHOOSE` 原 expected 为 1，旧重放却传 0。生产 `act` 在执行内部 feedback 前冻结原 action/payload/expected，`logAction` 记录同一 requestFingerprint；回放 409 符合合同，未发现子动作覆盖原身份的源码路径。

夹具现在从原 judge 回包冻结 `choiceExpected`，冻结 `optionId=continue`，首次和重放使用相同 actor/session/key/action/payload/expected；比较完整序列化回执。真实 PlayAttemptLog 必须是 attemptType=8，指纹必须等于原 CHOOSE helper 算出的值。新增同 key 改 expected、optionId、action 的三项精确 409 负控，并核对 session 版本/状态/积分、路线状态及原 receipt ID/hash/body 全部不变。原 `hp+1`、主结算 lastOutcome 不再结算、新 key 终态拒绝及 ENDED 分支保留。

`r5PersonalStepsUsesVerifiedBaselineReplaysAndRollsBackFailedRestore`：首次 `syncZeroHpSteps` 使用实际 route version，旧重放同 key 改成 0。调用映射为 `gameplayActionInternal(... ZERO_HP_STEPS ...)`，原 receiptId 绑定 version/operation/episode/原 weRun；生产拒绝符合合同。

夹具冻结 `baselineExpected` 与原认证数据，同 key 回放完整原结果；断言真实 TopicRouteAction 的 outcomeCode=ZERO_HP_STEPS。新增改 expected、认证输入、operation（ZERO_HP_SYNC）的三项精确 409 负控，并核对路线和原 receipt 无变化。其他会员认证拒绝、基线不计进度、后续增量、触发器失败回滚、恢复 hp=4/used=1、敏感字段不落状态等原断言保留。完成恢复的认证输入也只生成一次，失败后使用同一原命令恢复。

## 锁读、迁移与未验证

最终源码 ClubLeadMapper.selectByActivityForUpdate / lockClubOwner 均继承 9da 的 `useCache=false`、`flushCache=true` 和真实 `FOR UPDATE`，静态核对见 `club-lock-flags.json`。9da 的六项 MyBatis 配置参数场景及已有并发 IT 仍待受控 Java/MySQL；没有修改全局 MyBatis，也未触碰 E/G mapper。

本 delta 无新 schema、无 DDL 执行，无生产访问。集成时只取差异，保留 root 的 ThemeRuntime request_fingerprint migration 补列及 IT schema 清理，不用 C 整文件覆盖 root 联合树。无需回退业务数据；回退此 delta 不得改回缺身份/弱化指纹的生产路径。

明确未运行：Java 编译/JUnit、上述 MyBatis 387 阶段、Photo 编排真实存储、微信模拟器/真机。root 应在合并 9da 与本 delta、保留共享依赖和联合台账后安排受控 CI，结果出来前不合并部署。
