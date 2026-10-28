# 活动多人票后端接线增量

基于冻结1fbd；独占票码/席位服务及ApiVerify/Registration指定符号，不改A的下单/退款实现或G金额。当前代码新增活动多人票实际核销及独立原席位回读；小程序源尚未改，不能报整个前后端闭环完成。

- `/dyncode/issue`沿原买主/支付检查，单席原样；多人缺seatNo返回`requiresSeatSelection:true,registrationId,seatCount`，**没有code/qrcodeUrl**。明确seatNo后复用v1 HMAC，type为`activity_seat_N`或`topic_seat_N`，原报名ID/稳定seatId不变。
- 活动席位动态码回到原`scanQrCode(activity)`：原主办方/商家VERIFY、支付及俱乐部开场60分钟窗先行。多人旧整单码明确拒绝。团入口收到活动席位也只转原活动分支，并先核对当前activityId与原订单ownerId一致，不落D22主题团结算。
- 原event11积分仍只在registrationFirstTime时由原Controller在事务后发放；每次合法原票重扫仍调用既有幂等成长投递。Receipt追加registrationFirstTime仅用于原副作用路由，保留原8参构造，不是费用计数依据。
- 席位service在READ_COMMITTED/REQUIRED中按topic(如有)→activity→registration→seat锁回读，再复用原商家主体校验。首次明确席位调用原`verifyAndSettle(current,originalOwner,originalOwner,null,actor,originalRole)`；后续席位不再整单入账。没有改金额/费率/收款人，没有order_num乘费。主题团的原payee=null路径不变。
- 原60分钟算法机械移入现有seat service一个static helper；原Controller边界方法仍同签名/原Ajax文本委托；锁后新增效果也复用它，不复制第二份时间规则。
- `/api/verify/seat/receipt`仅锁读原报名/席位；买主或原范围内当前核销方授权后回原seatId/time。没有终态返回520 UNKNOWN，不执行/初始化/改号。已执行回执先于新使用支付/取消/结算闸；当前退款/取消不把原发生事实抹掉。
- 活动席位写链异常不在旧Controller catch中泄漏SQL：记录内部栈，响应520并要求按原席位查证；不设置intentNotExecuted、不自动换号。
- 独立活动允许topicId=NULL，活动ID仍是实际activity_id。新增后续原子ALTER只调整新表CHECK适配该真实scope，不回填、不删除原事实；G正topic查询不会将NULL充成功。

A共享边界：`activity-reverse-owner.review.patch`只是精确review补丁，**未应用共享源**。现有活动整单撤销核销会清聚合状态/标旧台账撤销，不能静默留下逐席位事实而假装整单从未核销。建议有真实seat事实时转席位售后核验，待A/G串行联动原分项冲回。该patch引入required RegistrationSeatMapper，相关直接构造fixture需显式注入；不以生产nullable fallback过测试。RefundService现有“已用转人工”保留，不猜部分席位退款金额。root/A必须审查应用或给出同等实际冲回接线，才能启用活动席位生产入口。

当前编写服务18+入口16+MySQL6=40条（此前21保留：入口“缺席位”从直接报错升级为明确选择响应，仍断言不出码/不执行；service缺席位拒绝负控保留）。新增实际活动HTTP路径的Owner/商家范围/时间窗/旧整单拒绝，原收款人和员工身份，独立活动NULL主题，UNKNOWN回读零写、取消/退款后原回执、SQL失败不泄漏，真实Mapper活动归属/独立活动CHECK等。未运行Java编译/40测试/MySQL/CI，未执行DDL；由root同批组合回归，无新CI批次。

静态检查：新Mapper XML/所有锁读cache双flag、actor/原收款人传递、唯一键不变、无金额乘法、A/G共享源码零变动、review.patch可应用性和diff检查。秘密扫描限定候选文件。

小程序准备已只读定位：`scene-qr-ticket`和play.ensureTicket两处发行caller，lead-ticket-scan及merchant-verification分类器/UNKNOWN workflow需接明确选择与绑定原reg/seat/id回读。已读xcx/AGENTS；指定miniprogram-development skill在完整cloud/executor技能目录、仓库、/workspace/.agents、/workspace/.codex、/opt、/tmp均未找到，skills.read也返回不可用。遵照用户“先读”要求，没有绕过该前提编辑小程序。需协调方提供此skill公共位置；禁止DevTools/上传/发布的要求保持。
