# D：canonical 广播历史迁移范围修复

结论：D 两条 UPDATE 已具备明确业务范围，并在 ALTER 前通过 SQL 主键冲突守卫拒绝异常历史数据；迁移后再以同类守卫验证分类及派生统计。未改门禁/白名单，不用 WHERE 1=1，不过滤后静默接受未知channel。当前仅静态验证通过，Java/MySQL未运行，未执行DDL或CI。

## 基线与独占范围

远端父身份必须为 F 8aaa141c5990b089749d4364fa2472748a69d9ab，tree f607c2dc33e33062ac4c7cc5e7e26c18a21dc34b。目录 /workspace/architecture-day-d-migration、分支 codex/architecture-day-d-migration-scope。Shell fetch 仍因 proxy port8080不可达失败；使用公共GitHub API读取基线差异、补齐151个blob并逐一验证，git write-tree精确复现F树后才修改。本地HEAD仍为公共2ec68899099a1e250df0949a8383bf85b5d3ffe0；远端发布指定F为唯一parent，不以本地HEAD冒充F提交。已冻结D/F/频控/fixture分支未改写。

已读F报告及原始safety-full输出：D第2/3条UPDATE无顶层WHERE；A两个CREATE TRIGGER缺自定义delimiter。前者由本候选修复，后者属A，未擅改。

只改 canonical chengyinhub-admin/sql/migration_20261023_d_official_broadcast_delivery.sql、D专属IT、新静态合同、D两项custody摘要及本目录。其他生产Java、A资金SQL、共享CI/registry/gate/baseline、scheduler及依赖声明不改。

## 精确历史scope与守卫

1. 全部广播发布/worker/恢复任务先停。SQL preflight检查所有已有日志：channel必须逐字节为inapp或subscribe，NULL/未知/大小写/尾空格异常均拒绝；reached/clicked必须0或1且非NULL；日志/广播/member身份为正，日志必须有广播父行；广播status必须为0/1/2/3且非NULL。异常由临时guard表已有id=1再次插入触发SQL错误，**发生在ALTER前**，不把未知channel加进成功分类，不删坏行。原有效时间可以NULL，不伪造时间。
2. 六个原候选新增列及默认UNKNOWN不变。日志UPDATE顶层WHERE要求 delivery_state逐字节UNKNOWN，delivery_title/delivery_sub/failure_code/message_id/reached_time全部NULL，并且channel/flags符合已审历史域；仅这批尚未赋予新证据的历史行可分类。inapp且旧reached=1→SUCCESS；其余→UNKNOWN/LEGACY_UNVERIFIED。原id/broadcast_id/member_id/channel/reached/clicked/create_time不重写，不将UNKNOWN改PENDING/FAILED。
3. 广播UPDATE顶层WHERE要求存在其保留日志，且 reached_count与成功站内数不一致（NULL安全比较），或旧status=2而存在未成功通道。只修派生count；只有旧sent含未确认通道才改pending。沿原候选语义，草稿/已撤**status不变**，其有日志证据的派生count仍可校正。无日志广播没有足够原收据，本次不按空集重写其count/status，留原值交人工核对，不当作新空受众快照。
4. SQL postflight检查所有历史分类与failure_code是否符合上述证据，title/sub/message/time仍为NULL，所有有日志广播的成功站内计数和sent状态收敛。漏行/半转换或意外新worker写入造成不符会再触发guard主键错误；成功才drop本连接临时guard表。SQL层执行守卫可被现有JDBC ResourceDatabasePopulator实际执行，不依赖它会忽略的runner注释断言。没有持久新增guard表/通用框架。

守卫不是停worker的替代；DDL会隐式提交，异常不能声称整份迁移已回滚。原六列部分出现、ALTER后异常或postflight失败时继续停广播，保留所有证据，按已批恢复/前向修复计划处理；禁止盲重跑、drop列/删日志或新旧worker混跑。无外部发送/经济写入。

## 测试与实际输出

- 先写6个Python源合同，原SQL6/6失败；实际执行修复后6/6通过，含分别删除两条UPDATE外层WHERE的负控，嵌套WHERE不能掩盖缺范围。
- 与现有ci.test_migration_gates共136项实际通过、零跳过；D check_sql_text返回[]，真实splitter得到8条语句。
- F原224项加D新6项，共230项实际执行；228通过，1failure+1error，原因与F相同：全局SQL库存还包含A触发器unsupported shape。再次audit_inventory仅剩A的migration_20261023_cash_withdraw_fund_fact.sql；无D残留。不能宣称全局safety已绿。
- git diff --check通过。原7个IT测试身份保留，本候选9项；root合并db5新增并发后预期10项。registry预期数及新鲜XML由root/G统一核对，不由D改共享接线。
- 新MySQL回归源码一：分别放NULL/fax/INAPP/inapp尾空格，断言SQL preflight拒绝、六列仍不存在、原日志及主状态/计数逐字保留。新回归二：合法站内成功/未确认、订阅旧reached=1、NULL时间、草稿/已撤、无日志和NULL派生count，验证保守分类、原flags/身份/时间保持以及真正有证据的派生范围。
- 新回归使用原IT已获批localhost连接配置，在独立随机cy_broadcast_d_guard_<uuid>专用库执行canonical原脚本，finally只删除该次自己创建的库；主IT已迁移fixture不被改坏。没有复制/打印凭据，没有Mock/H2代替MySQL。
- **未运行**：本次Java编译/JUnit/MySQL两项追加、真实DDL/迁移演练/故障恢复、CI dispatch/rerun、生产查询。协调者报告的原7项通过和wave2原8项运行不扩展为本改版迁移已验证。

## 必要只读预检及后验（未执行）

批准窗口先停全部广播写者，核对VERSION/DATABASE、SHOW CREATE TABLE两表、InnoDB、原uk_bc_member_channel精确三列以及六个新字段全不存在。按SQL preflight同一谓词读取异常数量，出现任何异常先处理决策阻塞，不删行凑通过。读取既有channel/reached/clicked分组、主status及无日志广播数量；保存脱敏原始身份/flags/时间审计摘要和目标派生差异数。不得以生产备份内容进入公共仓库。

受控演练必须实际运行这份canonical脚本和追加两项MySQL回归，确认preflight失败时未ALTER/回填，确认有效数据原列不变；成功后核对六列结构/默认、SUCCESS/UNKNOWN分布、LEGACY_UNVERIFIED及有日志广播的站内count，不删除临时失败证据伪装成功。应用回读与恢复任务只处理新合法PENDING，不重投历史UNKNOWN。最终联合源码更新custody/resource身份后重跑layout/safety/ledger与fresh XML。

本次D/CANDIDATE.sha256只刷新变动的SQL及IT两项，其他11项保留；root已应用db5/928时须按最终联合字节重算相关D身份，不覆盖新Service/测试。F目录、A候选及历史输出全部保持。

## 后续signup精确所有权（本候选不实施）

用户后续正式授权的切片为 CmsRegistrationServiceImpl.registerPaymentAfterCommit 及 signupNoticeExtra（当前位于RegistrationCheckoutServiceImpl）与专属tests。仅增加必要ISystemNotificationIntentService注入，在原付款事务提交前生产registration-signup:<原首位registrationId>意图，afterCommit只唤醒原intent，移除该事件旧sendSystem。completePaymentSuccess及其他资金算法仍A所有，ImServiceImpl完全不改；只调用现有findIntentId/enqueue与deliverSystemNotification。G已接R7/R1注册，本轮不重复改调度。实施前须读取最新联合SHA/E冻结实现，不能以本F快照覆盖后续候选；若必须改授权两方法之外的调用点会先报精确扩展点。
