package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.ImPoll;
import com.chengyinhub.business.domain.dto.ImPollCloseCommand;
import com.chengyinhub.business.domain.dto.ImPollCreateCommand;
import com.chengyinhub.business.domain.dto.ImPollVoteCommand;
import com.chengyinhub.business.mapper.ImPollMapper;
import com.chengyinhub.business.service.impl.ImPollServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class ImPollServiceImplTest {
    @Test
    void creationChecksTheRealtimeGroupMembershipSeamBeforePersistingAndEmitsAPollCard() {
        ImPollMapper mapper = mock(ImPollMapper.class);
        IImService im = mock(IImService.class);
        ImPollServiceImpl service = service(mapper, im);
        ImPollCreateCommand command = createCommand();
        when(mapper.insertPoll(any(ImPoll.class))).thenAnswer(invocation -> {
            ((ImPoll) invocation.getArgument(0)).setId(31L);
            return 1;
        });
        when(mapper.insertOption(any())).thenReturn(1);
        when(mapper.insertAudienceFromConversation(31L, 12L)).thenReturn(2);
        ImMessage message = new ImMessage();
        message.setId(44L);
        when(im.send(7L, 12L, null, ImMessage.MSG_POLL, "[投票]", "{\"pollId\":31}"))
                .thenReturn(message);
        when(mapper.attachMessage(31L, 44L)).thenReturn(1);
        when(mapper.selectById(31L)).thenReturn(poll(31L, 12L, 7L, ImPoll.STATUS_OPEN, 1L));
        when(mapper.selectOptions(31L)).thenReturn(Collections.emptyList());

        ImPoll created = service.create(7L, command);

        assertEquals(Long.valueOf(31L), created.getId(), "创建结果必须回读数据库生成的 pollId");
        verify(im).requireActiveGroupMembership(7L, 12L);
        verify(mapper).attachMessage(31L, 44L);
        verify(mapper).insertAudienceFromConversation(31L, 12L);
    }

    @Test
    void duplicateVoteHasAnExplicitNoRevoteConflictInsteadOfSilentSuccess() {
        ImPollMapper mapper = mock(ImPollMapper.class);
        IImService im = mock(IImService.class);
        ImPollServiceImpl service = service(mapper, im);
        when(mapper.selectById(31L)).thenReturn(poll(31L, 12L, 7L, ImPoll.STATUS_OPEN, 1L));
        doThrow(new DuplicateKeyException("uk_im_poll_vote_member"))
                .when(mapper).insertVoteIfOpen(31L, 5L, 9L);
        ImPollVoteCommand command = new ImPollVoteCommand();
        command.setPollId(31L);
        command.setOptionId(5L);

        ServiceException conflict = assertThrows(ServiceException.class, () -> service.vote(9L, command));

        assertEquals(Integer.valueOf(409), conflict.getCode(), "一人一票唯一键冲突必须翻译为业务冲突码");
        assertEquals("每人只能投一票，首版不支持改票", conflict.getMessage(),
                "首版不允许改票的语义必须明确返回");
        verify(im).requireActiveGroupMembership(9L, 12L);
    }

    @Test
    void closeUsesExpectedVersionAndRejectsAConcurrentWinner() {
        ImPollMapper mapper = mock(ImPollMapper.class);
        IImService im = mock(IImService.class);
        ImPollServiceImpl service = service(mapper, im);
        ImPoll open = poll(31L, 12L, 7L, ImPoll.STATUS_OPEN, 3L);
        when(mapper.selectById(31L)).thenReturn(open);
        when(mapper.closePollCas(31L, 7L, 3L)).thenReturn(0);
        when(im.isActiveGroupMember(7L, 12L)).thenReturn(true);
        ImPollCloseCommand command = new ImPollCloseCommand();
        command.setPollId(31L);
        command.setExpectedVersion(3L);

        ServiceException conflict = assertThrows(ServiceException.class, () -> service.close(7L, command));

        assertEquals(Integer.valueOf(409), conflict.getCode(), "CAS 输家必须返回版本冲突，不能覆盖赢家");
        assertEquals("投票版本冲突或已结束", conflict.getMessage());
        verify(mapper).closePollCas(31L, 7L, 3L);
    }

    @Test
    void voteThatLosesRealtimeMembershipFenceReturnsForbiddenInsteadOfConflict() {
        ImPollMapper mapper = mock(ImPollMapper.class);
        IImService im = mock(IImService.class);
        ImPollServiceImpl service = service(mapper, im);
        when(mapper.selectById(31L)).thenReturn(poll(31L, 12L, 7L, ImPoll.STATUS_OPEN, 1L));
        when(mapper.insertVoteIfOpen(31L, 5L, 9L)).thenReturn(0);
        when(im.isActiveGroupMember(9L, 12L)).thenReturn(false);
        ImPollVoteCommand command = new ImPollVoteCommand();
        command.setPollId(31L);
        command.setOptionId(5L);

        ServiceException forbidden = assertThrows(ServiceException.class, () -> service.vote(9L, command));

        assertEquals(Integer.valueOf(403), forbidden.getCode(), "原子成员闸拦截必须返回权限错误而非状态冲突");
        assertEquals("已退出群聊，无权投票", forbidden.getMessage(), "权限失败必须可辨别");
        verify(im).isActiveGroupMember(9L, 12L);
    }

    @Test
    void historicalAudienceCanReadAfterExitAndReceivesReferenceDrivenResultSummary() {
        ImPollMapper mapper = mock(ImPollMapper.class);
        IImService im = mock(IImService.class);
        ImPollServiceImpl service = service(mapper, im);
        when(mapper.selectById(31L)).thenReturn(poll(31L, 12L, 7L, ImPoll.STATUS_CLOSED, 2L));
        when(mapper.countAudience(31L, 9L)).thenReturn(1);
        com.chengyinhub.business.domain.ImPollOption first = option(5L, 2L);
        com.chengyinhub.business.domain.ImPollOption second = option(6L, 1L);
        when(mapper.selectResults(31L)).thenReturn(Arrays.asList(first, second));
        when(mapper.selectMemberVoteOption(31L, 9L)).thenReturn(5L);

        ImPoll result = service.result(9L, 31L);

        assertEquals(Long.valueOf(3L), result.getTotalVoters(), "聊天卡片结果必须返回总投票人数");
        assertEquals(Long.valueOf(5L), result.getMyOptionId(), "结果必须标出当前用户的已选项");
        verify(im, never()).isActiveGroupMember(9L, 12L);
    }

    @Test
    void memberWhoJoinedAfterCreationCanReadCurrentGroupPoll() {
        ImPollMapper mapper = mock(ImPollMapper.class);
        IImService im = mock(IImService.class);
        ImPollServiceImpl service = service(mapper, im);
        when(mapper.selectById(31L)).thenReturn(poll(31L, 12L, 7L, ImPoll.STATUS_OPEN, 1L));
        when(mapper.countAudience(31L, 10L)).thenReturn(0);
        when(im.isActiveGroupMember(10L, 12L)).thenReturn(true);
        when(mapper.selectResults(31L)).thenReturn(Collections.emptyList());

        ImPoll result = service.result(10L, 31L);

        assertEquals(Long.valueOf(0L), result.getTotalVoters(), "零票结果必须显式返回 0 而非空对象");
        verify(im).isActiveGroupMember(10L, 12L);
    }

    private ImPollServiceImpl service(ImPollMapper mapper, IImService im) {
        ImPollServiceImpl service = new ImPollServiceImpl();
        ReflectionTestUtils.setField(service, "pollMapper", mapper);
        ReflectionTestUtils.setField(service, "imService", im);
        return service;
    }

    private ImPollCreateCommand createCommand() {
        ImPollCreateCommand command = new ImPollCreateCommand();
        command.setConversationId(12L);
        command.setClientPollKey("route-vote-1");
        command.setQuestion("周末走哪条路线？");
        command.setOptions(Arrays.asList("苏州河", "老城厢"));
        return command;
    }

    private ImPoll poll(Long id, Long conversationId, Long creatorId, String status, Long version) {
        ImPoll poll = new ImPoll();
        poll.setId(id);
        poll.setConversationId(conversationId);
        poll.setCreatorMemberId(creatorId);
        poll.setStatus(status);
        poll.setVersion(version);
        return poll;
    }

    private com.chengyinhub.business.domain.ImPollOption option(Long id, Long votes) {
        com.chengyinhub.business.domain.ImPollOption option = new com.chengyinhub.business.domain.ImPollOption();
        option.setId(id);
        option.setVoteCount(votes);
        return option;
    }
}
