package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ImPoll;
import com.chengyinhub.business.domain.dto.ImPollCloseCommand;
import com.chengyinhub.business.domain.dto.ImPollCreateCommand;
import com.chengyinhub.business.domain.dto.ImPollVoteCommand;

public interface IImPollService {
    ImPoll create(Long memberId, ImPollCreateCommand command);
    ImPoll vote(Long memberId, ImPollVoteCommand command);
    ImPoll close(Long memberId, ImPollCloseCommand command);
    ImPoll result(Long memberId, Long pollId);

    /** 创建原请求回读（client_poll_key）：NOT_FOUND 或 APPLIED + 原投票与请求指纹；只读。 */
    default java.util.Map<String, Object> createReceipt(Long memberId, Long conversationId, String clientPollKey)
    {
        throw new UnsupportedOperationException("createReceipt");
    }
}
