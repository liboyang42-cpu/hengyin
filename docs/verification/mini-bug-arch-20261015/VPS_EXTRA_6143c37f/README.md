# E02 活动引用卡：4 项普通回归通过

固定源码 `6143c37f3c32753bd22208ace9c4d3c4520b9fd6`，父 `ff6c15679d3249987eb74ee150c1121a2effe250`；后续 `c35a46357cbbadfbcc61c881db46b6e51e7cc725` 仅原团队三份交付文档。实际源码差异仅 CommunityPostMapper.xml、新 E02_03Test 及原说明三路径，无 Service/共享接口/依赖/迁移变化。

生产 selectReferences 只过滤 ACTIVITY 引用且当前活动明确 publish_status=0 或 cancel_time 非空；保留正文、存储快照、原五字段投影、排序及 post/invalidated 过滤。报名资格变化不影响卡片，其他类型、缺目标行及未明确下架的 NULL 状态沿原行为。

原 212 在 **2026-10-17 12:33:34.889063Z—12:36:18.010531Z（UTC）**完成：**E02_03Test 1 类 4 实际 PASS / 0 FAIL / 0 ERROR / 0 SKIP**。fresh 1 XML 精确匹配类与实际调用数，guard 0/completed，BUILD SUCCESS。覆盖取消、下架、历史投影与报名变化负控、其他类型/缺行/NULL/原行过滤。正文与快照通过独立 JDBC 读回确认。旧 60/8/35/64 批次未重跑或继承。

任务 `/home/ubuntu/previews/cy-vps-extra-6143c37f-20261017`，源包 SHA256 `99654bc0bd1dc4bc0707395f1b0114c7d4d25474abe33eea1a4d04abb94009b2`，4328 源文件逐项 hash 与包一致。守卫 SHA256 `12374f742dcf1547c8eabf28c28403c94c8307fe20cba0a3a63d7f2b49764fba` 与原预算不变。精确容器 `0d2fd779e0cc1ecedfc7c16cb42e12c0142e31c58da39c70ef424a32adeda760` 经显式 chengyin-aws 全 ID 查询为空、退出 0。source/cache/包/日志/XML/selection/run/guard 保留。准备阶段一次过早调用因传输尚未结束、run.py 未到返回 Python exit2，没有启动守卫/Maven或测试；传输完成后才执行上述正式验证。

这些是生产 XML 在 H2 的有限合同回归，**不是真 MySQL 或运行 HTTP/设备/业务验收**。新增 SQL 的 cms_activity 读取依赖要求五个旧 MySQL scratch 夹具补齐既有表前提，精确路径见 summary；本次未修改或运行这些门禁，不算五个生产 BUG，也不宣称全部回归完成。保持现有入口，不新增；Redis/集成未验，迁移未执行、未合并部署。

逐报告和日志 hash 见 [summary.json](summary.json)。
