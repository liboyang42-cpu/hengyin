# A3/A4/A5维护窄合同

基线：交接分支；仅A业务文件。Java/MySQL/Redis未运行，必须用授权VPS或既有CI补验。

## 维护扫描

退款：`selectStalePayoutScanUpperBound(minutes)`固定本轮最大ID；`selectStalePayoutProcessingAfter(minutes, afterId, maxId, limit)`按ID升序、严格大于游标、不超过上界，每页200。游标在尝试业务操作前推进；单项异常不阻断后页。保留原处理/派发租约及确定性退款号，不改变未知结果政策。退款现有Mapper.java未列所有权，由A parent授权本业务扩展。

出码：parent提供`getPaidMissingQrcodeScanUpperBound()`与`listPaidMissingQrcodeIdsAfter(long,long,int)`，沿原近7天/已支付/无码条件并过滤不可用探索票；task每页100、固定MAX，单项失败仍前进。重试继续由generateVerificationQrcode当前资格与CAS处理。

任务bean同步单轮，保存跨轮ID游标；退款每轮最多1000、出码最多500，每页200/100，未达MAX停轮保留游标，空页/到MAX才归零。重启游标归零；若任务频繁重启仍存在运行公平断点。固定MAX不把扫描中新增对象无限纳入本轮。

## 原来源奖励券（协调中）

拟A新增`grantCouponBySource(memberId,couponId,sourceType,sourceId)`：稳定原来源唯一收据，原成员/券不可改变，领取计数、券历史及回执同事务；回放返回原historyId；失败保留原来源且不造新对象。C拥有漫游drop原来源与触发，需要固定来源和回放契约后接入口。主题grantOnce现已有同事务唯一日志，不另造协议或撤销后永久阻止再次原规则通关。

## 通知（协调中）

原报名Checkout直接sendSystem并吞异常、replay不重试；需B提供原事件意图/幂等投递合同。A保存原registration身份事件、B投递消息，未知不可盲重发。本文件不授权修改B投递层。

## 订阅/原账

active列表需同时`start_date <= now()`及`end_date > now()`（null继续原无限边界）；恰好结束拒绝当前权益。Settlement保持纯账本，历史分成快照与现有CAS提款不改政策；只修确定写入丢失，真实数据库守恒未验。

计划文件：RefundReconcileTask.java、RefundApplicationMapper.java/xml、VerificationQrcodeRepairTask.java、MerchantSubscriptionMapper.xml；对应JUnit测试；本目录文档。券/通知新业务文件在合同确认后追加。
