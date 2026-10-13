package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.WxContentCallbackInbox;

/** MySQL inbox 是媒体审核回调业务幂等与 fencing 的权威；Redis 只做外围防重。 */
public interface IWxContentCallbackInboxService
{
    boolean register(String traceId, int bizType, Long bizId, String mediaUrl);

    boolean isRegistered(String traceId);

    boolean process(String traceId, boolean risky, Mutation mutation);

    interface Mutation
    {
        /** 在目标行已经 FOR UPDATE 后，确认当前媒体仍是当初送检的媒体。 */
        boolean isCurrent(WxContentCallbackInbox inbox);

        /** 只做数据库业务状态变更；返回值必须有真实 affected/already-applied 证据。 */
        boolean apply(WxContentCallbackInbox inbox, boolean risky);
    }
}
