package com.chengyinhub.business.service;

/** App/小程序会员会话索引与撤销代次。 */
public interface IAppSessionRegistryService
{
    long currentOrCreateEpoch(Long memberId);

    boolean isCurrentEpoch(Long memberId, Long epoch);

    void registerToken(Long memberId, String token, long ttlMillis);

    void unregisterToken(Long memberId, String token);

    void revokeAll(Long memberId);

    String beginMemberMutation(Long memberId);

    void finishMemberMutation(Long memberId, String mutationId);

    void finishMemberMutationAfterTransaction(Long memberId, String mutationId);
}
