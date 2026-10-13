package com.chengyinhub.business.service.impl;

import java.util.Date;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.ImPoll;
import com.chengyinhub.business.domain.ImPollOption;
import com.chengyinhub.business.domain.dto.ImPollCloseCommand;
import com.chengyinhub.business.domain.dto.ImPollCreateCommand;
import com.chengyinhub.business.domain.dto.ImPollVoteCommand;
import com.chengyinhub.business.mapper.ImPollMapper;
import com.chengyinhub.business.service.IImPollService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.support.CanonicalDigest;
import com.chengyinhub.common.exception.ServiceException;

@Service
public class ImPollServiceImpl implements IImPollService {
    @Autowired
    private ImPollMapper pollMapper;
    @Autowired
    private IImService imService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImPoll create(Long memberId, ImPollCreateCommand command) {
        requireCreate(command);
        imService.requireActiveGroupMembership(memberId, command.getConversationId());
        imService.checkUserGeneratedText(memberId, command.getQuestion() + " "
                + String.join(" ", command.getOptions()));
        String requestHash = hash(command);
        ImPoll poll = new ImPoll();
        poll.setConversationId(command.getConversationId());
        poll.setCreatorMemberId(memberId);
        poll.setClientPollKey(command.getClientPollKey().trim());
        poll.setRequestHash(requestHash);
        poll.setQuestion(command.getQuestion().trim());
        poll.setDeadlineAt(command.getDeadlineAt());
        try {
            if (pollMapper.insertPoll(poll) != 1) throw conflict("投票创建失败");
        } catch (DuplicateKeyException e) {
            ImPoll existing = pollMapper.selectByBusinessKey(command.getConversationId(), memberId,
                    poll.getClientPollKey());
            if (existing != null && requestHash.equals(existing.getRequestHash())) return load(existing, false, memberId);
            throw conflict("clientPollKey 已被不同投票占用");
        }
        for (int i = 0; i < command.getOptions().size(); i++) {
            ImPollOption option = new ImPollOption();
            option.setPollId(poll.getId());
            option.setPosition(i + 1);
            option.setContent(command.getOptions().get(i).trim());
            if (pollMapper.insertOption(option) != 1) throw conflict("投票选项创建失败");
        }
        if (pollMapper.insertAudienceFromConversation(poll.getId(), command.getConversationId()) < 1) {
            throw conflict("投票成员快照创建失败");
        }
        JSONObject card = new JSONObject();
        card.put("pollId", poll.getId());
        ImMessage message = imService.send(memberId, command.getConversationId(), null,
                ImMessage.MSG_POLL, "[投票]", card.toJSONString());
        if (message == null || message.getId() == null || pollMapper.attachMessage(poll.getId(), message.getId()) != 1) {
            throw conflict("投票卡片创建失败");
        }
        return load(pollMapper.selectById(poll.getId()), false, memberId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImPoll vote(Long memberId, ImPollVoteCommand command) {
        if (command == null || command.getPollId() == null || command.getPollId() <= 0
                || command.getOptionId() == null || command.getOptionId() <= 0) {
            throw badRequest("pollId 与 optionId 必须为正数");
        }
        ImPoll poll = requirePoll(command.getPollId());
        imService.requireActiveGroupMembership(memberId, poll.getConversationId());
        try {
            if (pollMapper.insertVoteIfOpen(poll.getId(), command.getOptionId(), memberId) != 1) {
                if (!imService.isActiveGroupMember(memberId, poll.getConversationId())) {
                    throw new ServiceException("已退出群聊，无权投票", 403);
                }
                throw conflict("投票已结束、已过截止时间或选项不存在");
            }
        } catch (DuplicateKeyException e) {
            throw conflict("每人只能投一票，首版不支持改票");
        }
        return load(pollMapper.selectById(poll.getId()), true, memberId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImPoll close(Long memberId, ImPollCloseCommand command) {
        if (command == null || command.getPollId() == null || command.getPollId() <= 0
                || command.getExpectedVersion() == null || command.getExpectedVersion() <= 0) {
            throw badRequest("pollId 与 expectedVersion 必须为正数");
        }
        ImPoll poll = requirePoll(command.getPollId());
        imService.requireActiveGroupMembership(memberId, poll.getConversationId());
        if (!memberId.equals(poll.getCreatorMemberId())) throw new ServiceException("仅发起人可结束投票", 403);
        long expected = command.getExpectedVersion();
        if (pollMapper.closePollCas(poll.getId(), memberId, expected) == 1) {
            return load(pollMapper.selectById(poll.getId()), true, memberId);
        }
        if (!imService.isActiveGroupMember(memberId, poll.getConversationId())) {
            throw new ServiceException("已退出群聊，无权结束投票", 403);
        }
        poll = pollMapper.selectById(poll.getId());
        if (poll != null && ImPoll.STATUS_CLOSED.equals(poll.getStatus())
                && Long.valueOf(expected + 1).equals(poll.getVersion())) return load(poll, true, memberId);
        throw conflict("投票版本冲突或已结束");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImPoll result(Long memberId, Long pollId) {
        ImPoll poll = requirePoll(pollId);
        boolean historicalAudience = memberId != null && pollMapper.countAudience(poll.getId(), memberId) == 1;
        if (!historicalAudience && !imService.isActiveGroupMember(memberId, poll.getConversationId())) {
            throw new ServiceException("无权查看该投票", 403);
        }
        return load(poll, true, memberId);
    }

    private ImPoll load(ImPoll poll, boolean results, Long memberId) {
        if (poll == null) throw new ServiceException("投票不存在", 404);
        setPollDetails(poll, results, memberId);
        if (ImPoll.STATUS_OPEN.equals(poll.getStatus()) && poll.getDeadlineAt() != null
                && !poll.getDeadlineAt().after(new Date())) {
            pollMapper.expirePollCas(poll.getId(), poll.getVersion());
            poll = pollMapper.selectById(poll.getId());
            if (poll == null) throw new ServiceException("投票不存在", 404);
            setPollDetails(poll, results, memberId);
        }
        return poll;
    }

    private void setPollDetails(ImPoll poll, boolean results, Long memberId) {
        List<ImPollOption> options = results ? pollMapper.selectResults(poll.getId()) : pollMapper.selectOptions(poll.getId());
        poll.setOptions(options);
        if (results) {
            long total = 0L;
            if (options != null) {
                for (ImPollOption option : options) {
                    if (option != null && option.getVoteCount() != null) total += option.getVoteCount();
                }
            }
            poll.setTotalVoters(total);
            poll.setMyOptionId(memberId == null ? null : pollMapper.selectMemberVoteOption(poll.getId(), memberId));
        }
    }

    private ImPoll requirePoll(Long pollId) {
        ImPoll poll = pollId == null ? null : pollMapper.selectById(pollId);
        if (poll == null) throw new ServiceException("投票不存在", 404);
        return poll;
    }

    private void requireCreate(ImPollCreateCommand command) {
        if (command == null || command.getConversationId() == null || command.getConversationId() <= 0) {
            throw badRequest("conversationId 必须为正数");
        }
        if (command.getClientPollKey() == null || command.getClientPollKey().trim().isEmpty()
                || command.getClientPollKey().trim().length() > 64) throw badRequest("clientPollKey 必须为 1-64 个字符");
        if (command.getQuestion() == null || command.getQuestion().trim().isEmpty()
                || command.getQuestion().trim().length() > 200) throw badRequest("question 必须为 1-200 个字符");
        List<String> options = command.getOptions();
        if (options == null || options.size() < 2 || options.size() > 10) throw badRequest("options 必须有 2-10 项");
        for (String option : options) {
            if (option == null || option.trim().isEmpty() || option.trim().length() > 100) {
                throw badRequest("每个选项必须为 1-100 个字符");
            }
        }
        if (command.getDeadlineAt() != null && !command.getDeadlineAt().after(new Date())) {
            throw badRequest("deadlineAt 必须晚于当前时间");
        }
    }

    private String hash(ImPollCreateCommand command) {
        String deadline = command.getDeadlineAt() == null ? "" : String.valueOf(command.getDeadlineAt().getTime());
        return CanonicalDigest.sha256Hex(CanonicalDigest.join(command.getQuestion().trim(),
                String.join("\u001e", command.getOptions()), deadline));
    }

    private ServiceException badRequest(String message) { return new ServiceException(message, 400); }
    private ServiceException conflict(String message) { return new ServiceException(message, 409); }
}
