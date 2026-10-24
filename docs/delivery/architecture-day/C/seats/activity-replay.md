# 活动扫码：原终态事实优先于当前新使用拒绝

代码候选基于 `b7e73458dae0ecefccfb2b79934b3497d833f014`，工作目录 `/workspace/hengyin-c-seat-verification`、分支 `codex/architecture-day-c-seat-verification`。本片不改 A 的下单/退款/反核销 guard，不改 G 金额算法，不改 schema、客户端生产代码、CI 或生产环境。

## 触发与范围

原 `scanDynamicCode → scanQrCode → resolveScanContext` 在 paymentStatus≠2 时直接返回，尚未调用 `RegistrationSeatService`。俱乐部时间窗也在原席位回执分支之前返回；过期二维码在 dynamic/group 两个入口更早返回。对已经提交的 registration31/seat1，当前付款变为4，即使当前 actor81仍是 activity61主办方，原 API 也只能返回“未完成支付或已退款”，无法返回已发生的原 Receipt。相同事实通过现有 `/api/verify/seat/receipt` 可读，故这是 HTTP 编排次序缺口。

这条具体 Java 反例已写在 `RegistrationSeatReplayBoundaryTest.refundedCurrentPaymentCannotEraseAnAuthorizedCommittedActivitySeat`：真实 Controller + 真实 C SeatService，Mapper/商家资格为明确替身。**尚未执行 Java**，不把源码推导标为业务红测实跑。允许的 Node 源码接线合同在修改前实际1 fail/0 skip（拒绝分支没有原回执回读），修改后通过；这只证明接线，不证明 Java/SQL/JWT。

仅真实签名的 activity/topic 席位身份可进入新终态回读。普通静态码、普通 activity/topic 动态码、优惠券、团码、据点码不被当作席位回执，也不补造历史 seat。原普通码到期拒绝不变。

## 最小实现与授权

- `VerifyDynCodeUtil.verify` 默认行为保持：原当前时间、原 HMAC、原到期边界仍用于所有新使用。新增明确的 `verifyForReceipt` 只复用原格式/HMAC解析签名身份，可识别过期传输；它本身不授予核销或读权限。没有新票码、secret、nonce规则或依赖。
- `RegistrationSeatService.verifierSeatReceipt(registrationId,seatNo,activityId,actor,activityTicket)` 以 READ_COMMITTED 可写短事务复用原 `redeemSeat(...,execute=false)`。先验证实际 owner_type/订单归属和当前主办方/商家VERIFY权限；主题席位还保留当前俱乐部主理人、topic/activity与原团绑定；再沿原 topic→activity→club→registration 锁序、完整当前行及原席位读取终态。它不复用购票人自读捷径，不初始化/CAS/审计/记账/发奖励。
- `ApiRegistrationController.replayOriginalSeat` 只在五处原拒绝返回点尝试上述终态读：dynamic过期、group过期、activity通用前置闸、俱乐部时间窗、generic动态码ServiceException。只有当前授权的原终态存在才返回同一 Receipt；无终态继续原拒绝；依赖未知返回安全520，不推断 NOT_EXECUTED、不泄露SQL。
- 当前商家主体不匹配、权限撤销、活动主办方移交后的旧 actor、错误团、错误 owner_type、无登录和篡改签名都不能借原回执获得 ACK。新主办方读到的仍是原 verifier/verifiedAt，不改历史归因。
- buyer的 `/seat/receipt` 原自读权限保留，不能扩充为核销权限；显式活动scope不匹配现在拒绝，原终态缺id/席位/活动/核销人/时间也不返回成功。
- 泛 `scanQrCode(topic,明确topic_seat,activity)` 不再掉入旧整单商家核销，而转交既有 scoped group entry；其新使用仍重新做原当前 TTL/俱乐部权限，缺场次拒绝。否则即使显式席位码也可能被当成整单处理。

正常已支付/原时间窗允许的 activity 路径及首席原订单副作用完全保留，未预先绕过新使用检查。普通合法重放原有 queued growth 调用不删；仅拒绝分支的终态回读零新权益/费用/积分。新席位仍受付款、报名、取消、结算、人工退款、时间窗及原唯一事实/CAS校验，未核销不计费、不乘 order_num。A退款实现没有修改order_num的源码调用；本片不发明部分退款数量或新账务政策。

## 测试与交付边界

新增19个 `RegistrationSeatReplayBoundaryTest` 用例，覆盖退款、窗口移动、传输到期、三入口读回、无终态、新使用仍拒绝/合法次席仍正常、买家只读不等于核销、商家代办/撤销/外店、主办方移交保留原归因、错误scope/type、签名篡改/非席位码、原成长行为、残缺事实、缺真实actor、DB未知。真实C服务与原HMAC方法执行由root控制Java环境安排；本地没有运行。

`VerifyDynCodeUtilTest`保留原9条并新增2条：只读身份可超过传输TTL但默认verify仍拒绝，expiry边界不变；修改reg/seat/expiry/nonce或secret仍拒绝。新增Java合计21条均**未执行**。旧Endpoint16/Service19/MySQL7正文不改；真实RR/授权/数据库验收仍待root。

本片实际 Node 三个文件 **37/37 PASS，0 fail/skip**：源码合同6（包括逐个删除五处回读的负控）、既有席位工具/VM23、共享扫码8。逐文件数据在 `activity-replay-node-results.json`。所有变更Java只做了括号/字符串/注释词法平衡检查，不冒称编译；JS语法、diff检查通过。未跑 Java/Maven、DB、HTTP/JWT、DevTools、完整回归或 CI，没有部署/上传/DDL。

## 集成与回退

精确变更生产文件仅 ApiRegistrationController、RegistrationSeatService、VerifyDynCodeUtil；新增测试/报告另列于提交diff。root取本片增量保留 A366 与其他owner改动，不能用旧树覆盖。新签名读API只被本Controller原回执读及主题席位路由使用，不得接入新的核销动作。无需迁移；回退也不能删除任何席位/核销/审计事实，既有 UNKNOWN 与 receipt 读口须保留。root应在组合源统一执行新增21条、原席位相关类与真实存储/启动验收，不能沿用父候选的435 Node或此前系统编译结果为本片背书。
