## 两队针对性复测公开证据签收（覆盖旧待报告快照）

CORE `0d63043ad06775bc3dc52fb925620ef36d1081a7`：原8失败类157/157针对性PASS；EXTRA `e72eff7e9ec6309262e55c7cdca56afdcdb86dc0`：原2失败类56/56针对性PASS。两组失败/错误/跳过均0，exact类集合/新鲜XML、guard退出0/completed和逐份日志/源码/XML哈希已在独立证据公开；C00完整读回PR24@0e9695fc47689aa73cb1f4a6e8395f0a703ff2d7的两组README与summary。原CORE f7的483项失败、EXTRA 474的305项失败历史保留；本次未在新SHA全量重跑，未继承未改类或后续新源码成绩。

证据：[CORE157](https://github.com/liboyang42-cpu/hengyin/blob/0e9695fc47689aa73cb1f4a6e8395f0a703ff2d7/docs/verification/mini-bug-arch-20261015/VPS_CORE_0d63043a/README.md)；[EXTRA56](https://github.com/liboyang42-cpu/hengyin/blob/0e9695fc47689aa73cb1f4a6e8395f0a703ff2d7/docs/verification/mini-bug-arch-20261015/VPS_EXTRA_e72eff7e/README.md)。两组修复槽可继续真实授权事项，不等待验收占空位。C00仅维护总控账；各队源码/交付账仍归原作者，只补证据链接，不反灌版本。

第三队PR72冻结源码2babbe283ed2bfd2bf94859e0f5a589f12dcf9bd的两普通类当前本地VPS212执行中，结果未回，计划24声明不作执行数；11项真MySQL另验。真库/Redis、运行中HTTP真实身份、设备、迁移部署和合并仍未验收。Cloud未新增手动验证/CI重跑/VPS连接。

## CORE针对性VPS复测签收（157项）

本地唯一VPS验证者实际回报：CORE `0d63043ad06775bc3dc52fb925620ef36d1081a7` 的8个修复类157项全部PASS，0失败/0错误/0跳过，exact XML类集合和新鲜度已核，212 guard exit0/completed。原13个失败方法按针对性复测闭环，修复槽现可领取下一真实授权项；其他独占源码不等验证。此次只有8类157实际执行，未重跑75个未修改类，不称新483全跑通过；f7的83类483项470PASS/5FAIL/8ERROR原历史保留。f7..0d仅8测试类与修复说明，无生产变化。

证据层级：本轮TARGETED_PASS已从本地直接回报补齐为公开证据读回；PR24新VPS_CORE_0d63043a证据现已公开并由C00完整读回，精确commit与哈希见最新签收。C00/Cloud未手动测试。8个gated/JDBC、真MySQL/Redis、运行时HTTP真实身份、设备、迁移/部署/合并仍未验收；EXTRA e72两类56项正在本地串行复测，未接结果；第三PR72候选2babbe28仍独立NOT_RUN，均不混本次成绩。

# VPS首轮失败修复交接

原候选 `f7e0e52346d0aec248e6288f1f5ed4aaae66ac4c` 的83类483项实跑470通过/5失败/8错误/0跳过，**FAIL**。独立公开证据PR24@6e7a22025192710fa5edfd8be5a10ed425edc0ee的README/summary已由C00完整读回；8门禁/JDBC类另外待测，不能将其计入483。

最新源码 `0d63043ad06775bc3dc52fb925620ef36d1081a7`，父链 `f7e→b423114b(C03)→603dab12(C04)→0d63043a(C06)`。三原作者依次接单，前者冻结停写后下一人获得唯一修复槽；生产文件零修改，旧断言/负控/UNKNOWN保留，未删用例或跳过，Cloud未运行验证。新SHA SOURCE_DELIVERED_UNVERIFIED/NOT_RUN。

|组|原作者|修复公共SHA|根因与边界|
|---|---|---|---|
|C03|feature_points_rules_sol|b423114b6eb33ca50b32a3b92147b3834aeb5c8c|nullable Long自动拆箱stub与真实全局HTTP异常handler边界；仍500|
|C04|a02_sol|603dab127d850e8ac0b131522bb2f4351b508385|两current读精确顺序、when前预计算、真实cash原行准入；保80/20及回滚|
|C06|final_review_sol|0d63043ad06775bc3dc52fb925620ef36d1081a7|doReturn避免saved前置执行、schemaVersion1合法规则、局部CAS/ForUpdate current fixture|

## 本地聚焦复测

单独8失败类源声明157方法（非通过数），完整FQN与未来命令在同名JSON。C06_29方法改名versionedFrozenObjectPreservesDefaultRulesInsteadOfLiveCheckEnabled，原无版本{}合法说法已撤回；validator不动。原83类全量/8gated/JDBC后续仍由本地按现行212与批准真库通道排，不把网络none擅改，不等CI或新造替代环境。

13方法根因在当前调用链均指向fixture/边界失配，不将它们说成13个已修生产bug；最终仍以本地新SHA真实XML结果为准，未通过不能签收。

每队1修复+5独立源码继续，修复槽现空闲待下一真实反馈；没有具体任务不假称5线满载。新Cloud独立开发包另按原工单/锁外真实caller核定，不把历史178/105重派。
