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
}
