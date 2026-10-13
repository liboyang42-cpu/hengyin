package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.dto.DispatchResult;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.impl.DispatchServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DispatchServiceImplTest {

    private static final Long TOPIC = 200L;
    private static final Long NODE = 400L;
    private static final Long REG = 10L;
    private static final Long MERCHANT = 88L;

    private ICmsRegistrationMerchantService registrationService;
    private ICmsTopicService topicService;
    private CmsTopicMapper topicMapper;
    private ClubMapper clubMapper;
    private MmsMerchantMapper merchantMapper;
    private DispatchServiceImpl service;

    @BeforeEach
    void setUp() {
        registrationService = mock(ICmsRegistrationMerchantService.class);
        topicService = mock(ICmsTopicService.class);
        topicMapper = mock(CmsTopicMapper.class);
        clubMapper = mock(ClubMapper.class);
        merchantMapper = mock(MmsMerchantMapper.class);
        service = new DispatchServiceImpl(
                registrationService, topicService, topicMapper, clubMapper, merchantMapper);
        when(merchantMapper.selectMmsMerchantByMemberIdForUpdate(MERCHANT)).thenReturn(activeMerchant());
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setDelFlag(0);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
    }

    @Test
    void inactiveMerchantCannotBeConfirmedAsWinner() {
        when(registrationService.selectCmsRegistrationMerchantById(REG)).thenReturn(reg(REG, NODE));
        MmsMerchant disabled = activeMerchant();
        disabled.setAccountStatus(2L);
        when(merchantMapper.selectMmsMerchantByMemberIdForUpdate(MERCHANT)).thenReturn(disabled);

        DispatchResult result = service.confirmRegistration(REG);

        assertEquals(DispatchResult.Code.MERCHANT_INACTIVE, result.getCode());
        verify(registrationService, never()).confirmWinnerIfPending(anyLong());
    }

    @Test
    void sameNodeWinnerBlocksBeforeCompareAndSet() {
        when(registrationService.selectCmsRegistrationMerchantById(REG)).thenReturn(reg(REG, NODE));
        when(registrationService.selectCmsRegistrationMerchantList(argThat(p -> p != null
                && NODE.equals(p.getNodeId())
                && p.getTemplateId() == null
                && Long.valueOf(1L).equals(p.getAuditStatus()))))
                .thenReturn(Collections.singletonList(reg(11L, NODE)));

        DispatchResult result = service.confirmRegistration(REG);

        assertEquals(DispatchResult.Code.SLOT_OCCUPIED, result.getCode());
        verify(registrationService, never()).confirmWinnerIfPending(anyLong());
    }

    @Test
    void noNodeSentinelIsNotACompetitionSlotAndDoesNotSweep() {
        CmsRegistrationMerchant freeRoam = reg(REG, 0L);
        when(registrationService.selectCmsRegistrationMerchantById(REG)).thenReturn(freeRoam);
        when(registrationService.confirmWinnerIfPending(REG)).thenReturn(1);

        DispatchResult result = service.confirmRegistration(REG);

        assertTrue(result.isSelected());
        verify(registrationService, never()).selectCmsRegistrationMerchantList(any());
        verify(registrationService, never()).rejectIfPending(anyLong(), any());
    }

    @Test
    void selectedCandidateSweepsOnlyPendingCandidatesOnSameNode() {
        when(registrationService.selectCmsRegistrationMerchantById(REG)).thenReturn(reg(REG, NODE));
        when(registrationService.selectCmsRegistrationMerchantList(argThat(p -> p != null
                && NODE.equals(p.getNodeId())
                && p.getTemplateId() == null
                && Long.valueOf(1L).equals(p.getAuditStatus()))))
                .thenReturn(Collections.emptyList());
        when(registrationService.confirmWinnerIfPending(REG)).thenReturn(1);
        when(registrationService.selectCmsRegistrationMerchantList(argThat(p -> p != null
                && NODE.equals(p.getNodeId())
                && p.getTemplateId() == null
                && Long.valueOf(0L).equals(p.getAuditStatus()))))
                .thenReturn(Collections.singletonList(reg(12L, NODE)));

        DispatchResult result = service.confirmRegistration(REG);

        assertTrue(result.isSelected());
        verify(registrationService).rejectIfPending(12L, "未选中：该地点已选定其他商家承接");
    }

    @Test
    void inviteAcceptanceFindsPendingMerchantRegistrationThenUsesSameRules() {
        CmsRegistrationMerchant candidate = reg(REG, NODE);
        when(registrationService.selectCmsRegistrationMerchantList(argThat(p -> p != null
                && TOPIC.equals(p.getTopicId())
                && MERCHANT.equals(p.getMemberId())
                && NODE.equals(p.getNodeId())
                && Long.valueOf(0L).equals(p.getAuditStatus()))))
                .thenReturn(Collections.singletonList(candidate));
        when(registrationService.selectCmsRegistrationMerchantById(REG)).thenReturn(candidate);
        when(registrationService.selectCmsRegistrationMerchantList(argThat(p -> p != null
                && Long.valueOf(1L).equals(p.getAuditStatus()))))
                .thenReturn(Collections.emptyList());
        when(registrationService.confirmWinnerIfPending(REG)).thenReturn(1);

        DispatchResult result = service.confirmNodeMerchant(TOPIC, NODE, MERCHANT);

        assertTrue(result.isSelected());
        assertEquals(REG, result.getRegistrationId());
    }

    @Test
    void missingTopicCannotBeDispatched() {
        when(registrationService.selectCmsRegistrationMerchantById(REG)).thenReturn(reg(REG, NODE));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(null);

        assertEquals(DispatchResult.Code.TOPIC_NOT_FOUND, service.confirmRegistration(REG).getCode());
        verify(registrationService, never()).confirmWinnerIfPending(anyLong());
    }

    @Test
    void dissolvedTopicParentCannotConfirmOrSweepCandidate() {
        when(registrationService.selectCmsRegistrationMerchantById(REG)).thenReturn(reg(REG, NODE));
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setClubId(73L);
        topic.setDelFlag(0);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
        when(clubMapper.selectClubByIdForUpdate(73L)).thenReturn(null);

        DispatchResult result = service.confirmRegistration(REG);

        assertEquals(DispatchResult.Code.CLUB_DISSOLVED, result.getCode());
        assertEquals("主题所属俱乐部已解散，不能确认候选", result.getMessage());
        verify(registrationService, never()).confirmWinnerIfPending(anyLong());
        verify(registrationService, never()).rejectIfPending(anyLong(), any());
    }

    @Test
    void databaseUniqueKeyRaceBecomesOccupiedResultInsteadOfServerError() {
        when(registrationService.selectCmsRegistrationMerchantById(REG)).thenReturn(reg(REG, NODE));
        when(registrationService.selectCmsRegistrationMerchantList(any())).thenReturn(Collections.emptyList());
        when(registrationService.confirmWinnerIfPending(REG))
                .thenThrow(new DuplicateKeyException("uk_reg_merchant_win"));

        DispatchResult result = service.confirmRegistration(REG);

        assertEquals(DispatchResult.Code.SLOT_OCCUPIED, result.getCode());
        verify(registrationService, never()).rejectIfPending(anyLong(), any());
    }

    @Test
    void rejectUsesPendingCompareAndSetInDispatchHub() {
        when(registrationService.selectCmsRegistrationMerchantById(REG)).thenReturn(reg(REG, NODE));
        when(registrationService.rejectIfPending(REG, "证据不足")).thenReturn(1);

        DispatchResult result = service.rejectRegistration(REG, "证据不足");

        assertTrue(result.isRejected());
        assertEquals(REG, result.getRegistrationId());
        verify(registrationService).rejectIfPending(REG, "证据不足");
        verify(registrationService, never()).updateCmsRegistrationMerchant(any());
    }

    @Test
    void concurrentWinnerMakesRejectStableFailure() {
        when(registrationService.selectCmsRegistrationMerchantById(REG)).thenReturn(reg(REG, NODE));
        when(registrationService.rejectIfPending(REG, "后台驳回")).thenReturn(0);

        DispatchResult result = service.rejectRegistration(REG, null);

        assertEquals(DispatchResult.Code.ALREADY_PROCESSED, result.getCode());
    }

    private CmsRegistrationMerchant reg(Long id, Long nodeId) {
        CmsRegistrationMerchant result = new CmsRegistrationMerchant();
        result.setId(id);
        result.setTopicId(TOPIC);
        result.setMemberId(MERCHANT);
        result.setNodeId(nodeId);
        result.setTemplateId(300L);
        result.setAuditStatus(0L);
        return result;
    }

    private MmsMerchant activeMerchant() {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setMemberId(MERCHANT);
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        return merchant;
    }
}
