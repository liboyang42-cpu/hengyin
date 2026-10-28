# 给 G：可直接取用的逐席位事实合同

首批公共源 `1fbd8d83340a7b2fab82b6b971f8d0a89ed6dd49`；其21条用例为服务11、入口6、MySQL4，均仅编写未运行。当前后续活动接线保持下列签名/返回字段/身份键不变，活动报名按原owner_type=2和真实activity_id关联，不造group行。

`com.chengyinhub.business.service.RegistrationSeatService.verifiedSeatsForGroup(Long topicId, Long activityId, Date exclusiveCutoff)` → `List<com.chengyinhub.business.domain.RegistrationSeat>`。

字段：Long id/registrationId/topicId/activityId/verifierMemberId；Integer seatNo/status；Date verifiedAt。表`cms_registration_seat`主键id，唯一`uk_registration_seat(registration_id,seat_no)`。业务身份不因码的nonce/TTL变化而释放。

只读出status1且verifiedAt严格小于cutoff的真实席位；未签发/签发未用均不计费。MANDATORY可写事务，调用方先按原资金锁序持有topic/activity和相关报名锁，再读seat。全部新FOR UPDATE都useCache=false/flushCache=true。不得在readOnly事务调用锁读，不以第一页冒充全量，不乘order_num。

主题票用原group binding证明activity归属；活动票用原报名owner_type=2/owner_id=activityId和真实cms_activity.topic_id。历史多人整单核销无匹配seat证明时抛409待人工，不用0或购买数量伪装确定履约。普通独立活动topicId=NULL，不拿activityId充topicId，因此不会进入主题合作费查询。

不要把报名级group事实再与seat相加。单席旧事实沿原合同，多席费用仅按稳定实际seat ID一次；退款后原发生事实保留，费用资格/原分项冲回仍由G冻结算法处理。C不改G金额算法。

本会话协作工具只能看到自身，没有G会话投递接口；此文件与源码一并公开，G可直接fetch。root不需要翻译接口或补造事实。
