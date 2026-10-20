package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsPointsSetting;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.mapper.CmsPointsSettingMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper;
import com.chengyinhub.business.service.impl.PointsAwardServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublisherSettlementPointsAwardServiceTest {

    private CmsPointsSettingMapper pointsSettingMapper;
    private IUmsMemberService memberService;
    private UmsMemberMapper members;
    private UmsMemberPointsDetailMapper pointsDetailMapper;
    private PointsAwardServiceImpl service;

    @BeforeEach
    void setUp() {
        pointsSettingMapper = mock(CmsPointsSettingMapper.class);
        memberService = mock(IUmsMemberService.class);
        members = mock(UmsMemberMapper.class);
        pointsDetailMapper = mock(UmsMemberPointsDetailMapper.class);
        service = new PointsAwardServiceImpl();
        ReflectionTestUtils.setField(service, "cmsPointsSettingMapper", pointsSettingMapper);
        ReflectionTestUtils.setField(service, "umsMemberService", memberService);
        ReflectionTestUtils.setField(service, "umsMemberMapper", members);
        ReflectionTestUtils.setField(service, "umsMemberPointsDetailMapper", pointsDetailMapper);
    }

    @Test
    void awardPublisherSettlement_writesVersionedSnapshotAndVersionlessTopicKey() {
        CmsPointsSetting rule = rule(15L, 7L, "100");
        when(pointsSettingMapper.selectPublisherSettlementRules(15L))
                .thenReturn(Collections.singletonList(rule));
        when(memberService.lockMemberById(9L)).thenReturn(9L);
        when(members.selectPointsForUpdate(9L)).thenReturn(40L);
        when(pointsDetailMapper.insertPublisherSettlementPointsDetail(any())).thenReturn(1);
        when(memberService.addPoints(9L, 100)).thenReturn(true);

        assertEquals(100, service.awardPublisherSettlement(9L, 200L));

        ArgumentCaptor<UmsMemberPointsDetail> detail = ArgumentCaptor.forClass(UmsMemberPointsDetail.class);
        verify(pointsDetailMapper).insertPublisherSettlementPointsDetail(detail.capture());
        assertEquals("publisher-settlement:topic:200", detail.getValue().getRewardBusinessKey());
        assertEquals(15L, detail.getValue().getEventType());
        assertEquals(200L, detail.getValue().getEventId());
        assertEquals(15L, detail.getValue().getPointsRuleId());
        assertEquals(7L, detail.getValue().getPointsRuleVersion());
        assertEquals(0, new BigDecimal("100").compareTo(detail.getValue().getPointsRuleValue()));
        assertEquals(200L, detail.getValue().getPublisherSettlementTopicId());
        assertEquals(40L, detail.getValue().getCurrentPoints());
        assertEquals(140L, detail.getValue().getAfterPoints());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(memberService, members, pointsDetailMapper);
        order.verify(memberService).lockMemberById(9L);
        order.verify(members).selectPointsForUpdate(9L);
        order.verify(pointsDetailMapper).insertPublisherSettlementPointsDetail(any());
        // 路由锁：H8 必须走自己那条带 4 个快照列的专用语句，不许退回通用业务键写入口
        // （通用那条已经不引用新列，退回去会让 publisher_settlement_topic_id 永远为 NULL，
        //   ORDER 1/2 建的全局唯一键当场变成空索引）。
        verify(pointsDetailMapper, never()).insertUmsMemberPointsDetailWithBusinessKey(any());
    }

    @Test
    void publisherCurrentReadWithMissingOrNullPointRejectsBeforeWritingLedger() {
        when(pointsSettingMapper.selectPublisherSettlementRules(15L))
                .thenReturn(Collections.singletonList(rule(15L, 7L, "100")));
        when(memberService.lockMemberById(9L)).thenReturn(9L);
        when(members.selectPointsForUpdate(9L)).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.awardPublisherSettlement(9L, 200L));

        verify(pointsDetailMapper, never()).insertPublisherSettlementPointsDetail(any());
        verify(memberService, never()).addPoints(any(), anyInt());
    }

    @Test
    void h8ReadPathUsesTheDedicatedRuleQueryNotTheGenericList() {
        // 本用例根本没有注入 cmsPointsSettingService：一旦 H8 退回通用 selectCmsPointsSettingList，
        // 这里会 NPE 而不是绿 —— 通用查询不带 rule_version，拿回来的版本号恒为 null。
        when(pointsSettingMapper.selectPublisherSettlementRules(15L))
                .thenReturn(Collections.singletonList(rule(15L, 7L, "100")));
        when(memberService.lockMemberById(9L)).thenReturn(9L);
        when(members.selectPointsForUpdate(9L)).thenReturn(0L);
        when(pointsDetailMapper.insertPublisherSettlementPointsDetail(any())).thenReturn(1);
        when(memberService.addPoints(9L, 100)).thenReturn(true);

        assertEquals(100, service.awardPublisherSettlement(9L, 200L));

        verify(pointsSettingMapper).selectPublisherSettlementRules(15L);
    }

    @Test
    void nullRuleVersionFromADegradedReadStillFailsClosed() {
        // 缺列时的正确行为是 1054 上抛；万一有人把它降级成「查得到但 rule_version 为 null」，
        // requirePublisherSettlementRule 必须仍然拒绝发奖。
        when(pointsSettingMapper.selectPublisherSettlementRules(15L))
                .thenReturn(Collections.singletonList(rule(15L, null, "100")));

        assertThrows(ServiceException.class, () -> service.awardPublisherSettlement(9L, 200L));

        verify(memberService, never()).lockMemberById(any());
        verify(pointsDetailMapper, never()).insertPublisherSettlementPointsDetail(any());
    }

    @Test
    void duplicateTopicKeyAfterRuleVersionChanges_isStillNoop() {
        when(pointsSettingMapper.selectPublisherSettlementRules(15L))
                .thenReturn(Collections.singletonList(rule(15L, 8L, "120")));
        when(memberService.lockMemberById(9L)).thenReturn(9L);
        when(members.selectPointsForUpdate(9L)).thenReturn(140L);
        when(pointsDetailMapper.insertPublisherSettlementPointsDetail(any()))
                .thenThrow(new DuplicateKeyException("duplicate"));
        UmsMemberPointsDetail existing = new UmsMemberPointsDetail();
        existing.setMemberId(9L);
        existing.setEventType(15L);
        existing.setEventId(200L);
        existing.setRewardBusinessKey("publisher-settlement:topic:200");
        existing.setChangeType(1L);
        when(pointsDetailMapper.selectByRewardBusinessKey(
                9L, "publisher-settlement:topic:200")).thenReturn(existing);

        assertEquals(0, service.awardPublisherSettlement(9L, 200L));
        verify(memberService, never()).addPoints(any(), anyInt());
    }

    @Test
    void duplicateTopicAcrossDifferentMembers_isStillGlobalNoop() {
        when(pointsSettingMapper.selectPublisherSettlementRules(15L))
                .thenReturn(Collections.singletonList(rule(15L, 8L, "100")));
        when(memberService.lockMemberById(9L)).thenReturn(9L);
        when(members.selectPointsForUpdate(9L)).thenReturn(0L);
        when(pointsDetailMapper.insertPublisherSettlementPointsDetail(any()))
                .thenThrow(new DuplicateKeyException("uk_publisher_settlement_topic"));
        UmsMemberPointsDetail existing = new UmsMemberPointsDetail();
        existing.setMemberId(8L);
        existing.setEventType(15L);
        existing.setEventId(200L);
        existing.setRewardBusinessKey("publisher-settlement:topic:200");
        existing.setChangeType(1L);
        when(pointsDetailMapper.selectByEventTypeAndEventId(15L, 200L)).thenReturn(existing);

        assertEquals(0, service.awardPublisherSettlement(9L, 200L));
        verify(memberService, never()).addPoints(any(), anyInt());
    }

    @Test
    void missingDuplicateReadback_doesNotSwallowUnrelatedUniqueCollision() {
        when(pointsSettingMapper.selectPublisherSettlementRules(15L))
                .thenReturn(Collections.singletonList(rule(15L, 1L, "100")));
        when(memberService.lockMemberById(9L)).thenReturn(9L);
        when(members.selectPointsForUpdate(9L)).thenReturn(0L);
        when(pointsDetailMapper.insertPublisherSettlementPointsDetail(any()))
                .thenThrow(new DuplicateKeyException("other unique"));

        assertThrows(DuplicateKeyException.class,
                () -> service.awardPublisherSettlement(9L, 200L));
        verify(memberService, never()).addPoints(any(), anyInt());
    }

    @Test
    void missingOrAmbiguousRuleFailsClosedBeforeMemberOrLedgerWrites() {
        when(pointsSettingMapper.selectPublisherSettlementRules(15L))
                .thenReturn(Collections.emptyList(), Arrays.asList(
                        rule(15L, 1L, "100"), rule(16L, 1L, "100")));

        assertThrows(ServiceException.class,
                () -> service.awardPublisherSettlement(9L, 200L));
        assertThrows(ServiceException.class,
                () -> service.awardPublisherSettlement(9L, 200L));
        verify(memberService, never()).lockMemberById(any());
        verify(pointsDetailMapper, never()).insertUmsMemberPointsDetailWithBusinessKey(any());
    }

    private CmsPointsSetting rule(Long id, Long version, String value) {
        CmsPointsSetting rule = new CmsPointsSetting();
        rule.setId(id);
        rule.setType(1L);
        rule.setEventType(15L);
        rule.setStatus(1L);
        rule.setDelFlag(0L);
        rule.setRuleVersion(version);
        rule.setValue(new BigDecimal(value));
        return rule;
    }
}
