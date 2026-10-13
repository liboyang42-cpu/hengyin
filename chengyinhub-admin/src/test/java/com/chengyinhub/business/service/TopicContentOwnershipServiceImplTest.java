package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.TopicContentOwnershipServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 内容槽改判影响票款归属，必须有独立生命周期与主体有效性闸门。 */
class TopicContentOwnershipServiceImplTest {

    private ICmsTopicService topicService;
    private IProductRoleService productRoleService;
    private ClubMapper clubMapper;
    private UmsMemberMapper memberMapper;
    private ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;

    private ITopicContentOwnershipService service;

    @BeforeEach
    void setUp() {
        topicService = mock(ICmsTopicService.class);
        productRoleService = mock(IProductRoleService.class);
        clubMapper = mock(ClubMapper.class);
        memberMapper = mock(UmsMemberMapper.class);
        topicOperationsScopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        when(topicOperationsScopeAuthorizer.canViewTopic(anyLong())).thenReturn(true);
        service = new TopicContentOwnershipServiceImpl(topicService, productRoleService, clubMapper, memberMapper,
                topicOperationsScopeAuthorizer);
    }

    @Test
    void allowsAuditedReassignmentBeforeExecution() {
        when(topicService.selectCmsTopicById(7L)).thenReturn(topic(3));
        when(productRoleService.reassignContentSlot(7L, "PLATFORM", null)).thenReturn(true);

        assertTrue(service.reassignContentSlot(7L, "PLATFORM", null));

        verify(productRoleService).reassignContentSlot(7L, "PLATFORM", null);
    }


    @Test
    void rejectsOutOfScopeTopicBeforeAnyLookupOrRoleMutation() {
        when(topicOperationsScopeAuthorizer.canViewTopic(9L)).thenReturn(false);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.reassignContentSlot(9L, "CLUB", 10L));

        assertEquals("主题不存在或当前账号无操作范围", error.getMessage());
        verify(topicService, never()).selectCmsTopicById(9L);
        verify(productRoleService, never()).reassignContentSlot(9L, "CLUB", 10L);
    }

    @Test
    void allowsOnlyActiveExistingClubOrMemberAsContentSubject() {
        when(topicService.selectCmsTopicById(7L)).thenReturn(topic(3));
        when(clubMapper.selectClubById(11L)).thenReturn(activeClub());
        when(memberMapper.selectUmsMemberById(12L)).thenReturn(activeMember());
        when(productRoleService.reassignContentSlot(7L, "CLUB", 11L)).thenReturn(true);
        when(productRoleService.reassignContentSlot(7L, "MEMBER", 12L)).thenReturn(true);

        assertTrue(service.reassignContentSlot(7L, "CLUB", 11L));
        assertTrue(service.reassignContentSlot(7L, "MEMBER", 12L));

        verify(clubMapper).selectClubById(11L);
        verify(memberMapper).selectUmsMemberById(12L);
        verify(productRoleService).reassignContentSlot(7L, "CLUB", 11L);
        verify(productRoleService).reassignContentSlot(7L, "MEMBER", 12L);
    }

    @Test
    void rejectsMissingOrInactiveContentSubjectBeforeRoleMutation() {
        when(topicService.selectCmsTopicById(7L)).thenReturn(topic(3));

        IllegalArgumentException missingClub = assertThrows(IllegalArgumentException.class,
                () -> service.reassignContentSlot(7L, "CLUB", 11L));
        assertEquals("内容主体俱乐部不存在、未启用或已删除", missingClub.getMessage());

        Club disabledClub = activeClub();
        disabledClub.setStatus(0L);
        when(clubMapper.selectClubById(12L)).thenReturn(disabledClub);
        assertThrows(IllegalArgumentException.class,
                () -> service.reassignContentSlot(7L, "CLUB", 12L));

        UmsMember deletedMember = activeMember();
        deletedMember.setDelFlag(2);
        when(memberMapper.selectUmsMemberById(13L)).thenReturn(deletedMember);
        IllegalArgumentException deletedMemberError = assertThrows(IllegalArgumentException.class,
                () -> service.reassignContentSlot(7L, "MEMBER", 13L));
        assertEquals("内容主体会员不存在、已停用或已删除", deletedMemberError.getMessage());

        verify(productRoleService, never()).reassignContentSlot(7L, "CLUB", 11L);
        verify(productRoleService, never()).reassignContentSlot(7L, "CLUB", 12L);
        verify(productRoleService, never()).reassignContentSlot(7L, "MEMBER", 13L);
    }

    @Test
    void rejectsInvalidContentSubjectShapeBeforeRoleMutation() {
        when(topicService.selectCmsTopicById(7L)).thenReturn(topic(3));

        assertThrows(IllegalArgumentException.class,
                () -> service.reassignContentSlot(7L, "PLATFORM", 99L));

        verify(productRoleService, never()).reassignContentSlot(7L, "PLATFORM", 99L);
    }

    @Test
    void rejectsReassignmentAfterExecutionStarts() {
        when(topicService.selectCmsTopicById(7L)).thenReturn(topic(4));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.reassignContentSlot(7L, "PLATFORM", null));

        assertEquals("执行或结算阶段不得改判内容归属；请走财务治理流程", error.getMessage());
        verify(productRoleService, never()).reassignContentSlot(7L, "PLATFORM", null);
    }

    @Test
    void rejectsDeletedTopicInsteadOfCallingRoleMutation() {
        CmsTopic deleted = topic(3);
        deleted.setDelFlag(2);
        when(topicService.selectCmsTopicById(7L)).thenReturn(deleted);

        assertThrows(IllegalArgumentException.class,
                () -> service.reassignContentSlot(7L, "MEMBER", 11L));

        verify(productRoleService, never()).reassignContentSlot(7L, "MEMBER", 11L);
    }

    private static CmsTopic topic(int lifecycle) {
        CmsTopic topic = new CmsTopic();
        topic.setId(7L);
        topic.setLifecycle(lifecycle);
        return topic;
    }

    private static Club activeClub() {
        Club club = new Club();
        club.setStatus(1L);
        club.setDelFlag(0);
        return club;
    }

    private static UmsMember activeMember() {
        UmsMember member = new UmsMember();
        member.setStatus(1L);
        member.setDelFlag(0);
        return member;
    }
}
