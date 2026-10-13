# 保留免费与原资格的范围修正

用户明确纠正「玩法为什么付款」。本包只恢复原资格，不引入玩法付款或新收费政策。

| 既有路径 | 基线原资格 | 本次行为 |
| --- | --- | --- |
| ApiPlayRunSession.save/read/clear | 本人身份+原scope+时间结构，没有订单闸 | 完整保留基线，曾试写的当前报名门已撤回；独立保存不会要求订单或付款 |
| ApiPlayProgress.resolveCtx 活动/自玩→answer/checkin/photo/sensor等 | 原registrationStatus=2+member/owner，自玩expires；普通活动原起止时间窗 | 当前activity→registration锁读保持相同资格；普通免费报名payment=0仍接受，只有原pk3订单按已定支付/活动结束规则拒新使用 |
| TopicRouteRuntime原scope和新建/推进 | 原registrationStatus=2+member/owner，自玩expires | 保原免费资格；当前锁读不把所有报名改成已付，原pk3仅新用加结束/缺日期闸，已存在局历史读不调用新用闸 |
| AdvancedGameRuntime.activity start | 基线hasPaidRegistration查询已经要求已付（payment=2），不是本次新增 | 原来源保留并锁读当前对象；act/completeGate沿原局与原报名资格恢复，不发起支付/创设付费要求 |
| GameSessionRuntime PLAYER命令 | 基线requirePlayer→hasPaidRegistration，真实原报名源 | 原来源保留并在新动作中锁读；view/receipt继续原历史资格，A已撤对hasPaid的到期收紧 |
| NPC shop/voice新请求 | 基线hasCurrentNpcRegistration | 新问继续原资格，原requestId+原node+原user答案只读可恢复，避免过期丢回包重发；数据导出历史仍可读 |

正控/负控已编写：CurrentPlayRegistrationTest 普通免费payment0、原付费消费者独立约束、pk3恰好结束/缺日期补齐/PAYING=1/退款旧快照/owner变化；ApiPlayCurrentUseBoundaryTest 经实际answer入口验证免费报名到原题面检查、pk3结束/退款拒绝；既有ApiPlayRunSessionControllerTest无订单存档原正控保留，文件源码与交接基线一致。

这些Java用例尚未运行，不把编写或静态语法解析称为正控已通过；真正JUnit和真实数据库锁竞争等待专用VPS。没有新增付费UI、普通票支付政策、鉴权角色策略或原手续费/配额变化。
