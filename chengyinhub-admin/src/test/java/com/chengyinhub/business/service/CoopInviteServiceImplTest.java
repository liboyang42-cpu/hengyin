package com.chengyinhub.business.service;

import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.business.domain.CoopCreditLog;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.CoopPerk;
import com.chengyinhub.business.domain.CoopPerkTemplate;
import com.chengyinhub.business.domain.CoopReview;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopClubApply;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CoopCreditLogMapper;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopPerkMapper;
import com.chengyinhub.business.mapper.CoopPerkTemplateMapper;
import com.chengyinhub.business.mapper.CoopReviewMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.impl.CoopInviteServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CoopInviteServiceImpl 核心逻辑单测(B1-4/B1-5/双死线/信用),mock mapper,不依赖 DB。
 */
class CoopInviteServiceImplTest {

    // In-memory persistence behavior for the new locked original-invite/order funnel; not a DB proof.
    private final java.util.Map<Long,CoopInvite> fixtureRows = new java.util.HashMap<>();
    private CoopInviteMapper coopInviteMapper;
    private CoopOrderMapper coopOrderMapper;
    private CoopCreditLogMapper coopCreditLogMapper;
    private CoopInviteServiceImpl svc;

    private com.chengyinhub.business.mapper.ClubMapper clubMapper;
    private com.chengyinhub.business.mapper.CmsTopicMapper cmsTopicMapper;
    private CoopPerkMapper coopPerkMapper;
    private CoopPerkTemplateMapper coopPerkTemplateMapper;
    private CoopReviewMapper coopReviewMapper;
    private MmsMerchantMapper mmsMerchantMapper;
    private ICoopClubApplyService coopClubApplyService;
    private INotifyService notifyService;
    private com.chengyinhub.business.service.impl.WechatPaymentIntentStore paymentIntentStore;
    private org.springframework.jdbc.core.JdbcTemplate paymentIntentJdbc;
    private com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService verifiedPaymentFacts;

    @BeforeEach
    void setUp() {
        coopInviteMapper = mock(CoopInviteMapper.class);
        coopOrderMapper = mock(CoopOrderMapper.class);
        coopCreditLogMapper = mock(CoopCreditLogMapper.class);
        clubMapper = mock(com.chengyinhub.business.mapper.ClubMapper.class);
        cmsTopicMapper = mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        when(coopInviteMapper.selectCoopInviteById(any())).thenAnswer(call -> {
            CoopInvite row = coopInviteMapper.selectCoopInviteByIdForUpdate(call.getArgument(0));
            if (row != null) fixtureRows.put(row.getId(), row);
            return row;
        });
        org.mockito.Mockito.doAnswer(call -> {
            CoopInvite update = call.getArgument(0);
            if (update == null) return 1; // Mockito matcher evaluation when an old case overrides this stub.
            CoopInvite row = fixtureRows.get(update.getId());
            if (row != null && update.getStatus() != null) row.setStatus(update.getStatus());
            return 1;
        }).when(coopInviteMapper).updateCoopInvite(any());
        when(coopInviteMapper.selectOriginalCoopOrdersForUpdate(any(), any())).thenReturn(Collections.emptyList());
        org.mockito.Mockito.doAnswer(call -> {
            CoopOrder row = call.getArgument(0); row.setId(800L); return 1;
        }).when(coopOrderMapper).insertCoopOrder(any());
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(any())).thenAnswer(call ->
                cmsTopicMapper.selectCmsTopicById(call.getArgument(0)));
        svc = new CoopInviteServiceImpl();
        // Real B retry guard/order locator; only JDBC results and provider-proof read are unit doubles.
        paymentIntentJdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        verifiedPaymentFacts = mock(com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService.class);
        var paymentOrderOwner = new com.chengyinhub.business.service.impl.JdbcWechatPaymentOrderOwner();
        ReflectionTestUtils.setField(paymentOrderOwner, "paymentIntentJdbc", paymentIntentJdbc);
        paymentIntentStore = new com.chengyinhub.business.service.impl.WechatPaymentIntentStore();
        ReflectionTestUtils.setField(paymentIntentStore, "paymentIntentJdbc", paymentIntentJdbc);
        ReflectionTestUtils.setField(paymentIntentStore, "businessOwner", paymentOrderOwner);
        ReflectionTestUtils.setField(paymentIntentStore, "verifiedFacts", verifiedPaymentFacts);
        ReflectionTestUtils.setField(svc, "paymentIntentStore", paymentIntentStore);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "commercialReviewService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.ITopicCommercialReviewService.class));
        ReflectionTestUtils.setField(svc, "depositRefundService", mock(com.chengyinhub.business.service.impl.CoopDepositRefundService.class));
        ReflectionTestUtils.setField(svc, "coopInviteMapper", coopInviteMapper);
        ReflectionTestUtils.setField(svc, "coopOrderMapper", coopOrderMapper);
        ReflectionTestUtils.setField(svc, "coopCreditLogMapper", coopCreditLogMapper);
        ReflectionTestUtils.setField(svc, "clubMapper", clubMapper);
        // [资损·冻结阵容] 注入 cmsTopicMapper;默认 selectCmsTopicById 返回 null → lifecycle 闸跳过,不影响既有用例。
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", cmsTopicMapper);
        ReflectionTestUtils.setField(svc, "clubCoopWindowPolicy",
                new com.chengyinhub.business.service.support.ClubCoopWindowPolicy(
                        mock(com.chengyinhub.business.service.support.CommercialTopicPolicy.class)));
        coopPerkMapper = mock(CoopPerkMapper.class);
        coopPerkTemplateMapper = mock(CoopPerkTemplateMapper.class);
        coopReviewMapper = mock(CoopReviewMapper.class);
        mmsMerchantMapper = mock(MmsMerchantMapper.class);
        coopClubApplyService = mock(ICoopClubApplyService.class);
        notifyService = mock(INotifyService.class);
        ReflectionTestUtils.setField(svc, "coopPerkMapper", coopPerkMapper);
        ReflectionTestUtils.setField(svc, "coopPerkTemplateMapper", coopPerkTemplateMapper);
        ReflectionTestUtils.setField(svc, "coopReviewMapper", coopReviewMapper);
        ReflectionTestUtils.setField(svc, "mmsMerchantMapper", mmsMerchantMapper);
        when(mmsMerchantMapper.selectMmsMerchantByMemberId(any())).thenAnswer(invocation -> {
            MmsMerchant merchant = new MmsMerchant();
            merchant.setMemberId(invocation.getArgument(0));
            merchant.setStatus(1L);
            merchant.setAccountStatus(1L);
            merchant.setDelFlag(0);
            return merchant;
        });
        when(mmsMerchantMapper.selectMmsMerchantByMemberIdForUpdate(any())).thenAnswer(invocation -> {
            MmsMerchant merchant = new MmsMerchant();
            merchant.setMemberId(invocation.getArgument(0));
            merchant.setStatus(1L);
            merchant.setAccountStatus(1L);
            merchant.setDelFlag(0);
            return merchant;
        });
        ReflectionTestUtils.setField(svc, "coopClubApplyService", coopClubApplyService);
        ReflectionTestUtils.setField(svc, "notifyService", notifyService);
    }

    /** 造一个 type1 俱乐部邀约(to_id=clubId,主理人=leaderId) */
    private CoopInvite clubInvite(Long id, Integer status, Long clubId, Long leaderId, Integer depositStatus) {
        CoopInvite c = new CoopInvite();
        c.setId(id); c.setDelFlag(0); c.setStatus(status); c.setInviteType(1); c.setToType("club"); c.setToId(clubId);
        c.setFromType("merchant"); c.setFromId(300L);
        c.setDepositStatus(depositStatus);
        com.chengyinhub.business.domain.Club club = new com.chengyinhub.business.domain.Club();
        club.setId(clubId); club.setMemberId(leaderId);
        when(clubMapper.selectClubById(clubId)).thenReturn(club);
        when(clubMapper.selectClubByIdForUpdate(clubId)).thenReturn(club);
        return c;
    }

    private CoopInvite inv(Long id, Integer status) {
        CoopInvite c = new CoopInvite();
        c.setId(id);
        c.setDelFlag(0);
        c.setStatus(status);
        c.setInviteType(0);
        c.setFromType("talent");
        c.setFromId(100L);
        c.setToType("merchant");
        c.setToId(200L);
        return c;
    }

    @Test
    void reviewRejectsMemberWhoseIdOnlyCollidesWithClubPrimaryKey() {
        CoopInvite accepted = clubInvite(1L, 1, 77L, 7001L, 0);
        accepted.setTopicId(7L);
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(Collections.singletonList(accepted));
        CoopReview forged = new CoopReview();
        forged.setTopicId(7L);
        forged.setFromId(77L);
        forged.setToId(300L);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> svc.saveReview(forged));

        assertTrue(ex.getMessage().contains("合作双方"));
        verify(coopReviewMapper, never()).insertCoopReview(any());
    }

    @Test
    void reviewAllowsCanonicalClubOwnerAndMerchantPair() {
        CoopInvite accepted = clubInvite(1L, 1, 77L, 7001L, 0);
        accepted.setTopicId(7L);
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(Collections.singletonList(accepted));
        CoopReview review = new CoopReview();
        review.setTopicId(7L);
        review.setFromId(7001L);
        review.setToId(300L);

        svc.saveReview(review);

        verify(coopReviewMapper).insertCoopReview(review);
    }

    @Test
    void insertInvite_rejectsForgedTypeTargetMatrixBeforePersistence() {
        CoopInvite forged = inv(1L, 0);
        forged.setToType("club");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.insertCoopInvite(forged));

        assertTrue(ex.getMessage().contains("邀请类型"));
        verify(coopInviteMapper, never()).insertCoopInvite(any());
    }

    @Test
    void insertInvite_allowsClubPublisherToInviteExecutionClubWithoutMerchantGate() {
        CoopInvite invite = clubInvite(null, 0, 8001L, 100L, 0);
        invite.setFromType("club");
        invite.setFromId(7001L);
        invite.setTopicId(7L);
        CmsTopic topic = new CmsTopic();
        topic.setId(7L);
        topic.setDelFlag(0);
        topic.setClubId(7001L);
        topic.setLifecycle(1);
        topic.setRecruitDeadline(futureRecruitDeadline());
        topic.setDelFlag(0);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(topic);
        com.chengyinhub.business.domain.Club publisher = new com.chengyinhub.business.domain.Club();
        publisher.setId(7001L);
        when(clubMapper.selectClubByIdForUpdate(7001L)).thenReturn(publisher);
        when(coopInviteMapper.insertCoopInvite(invite)).thenReturn(1);

        assertEquals(1, svc.insertCoopInvite(invite));

        verify(mmsMerchantMapper, never()).selectMmsMerchantByMemberIdForUpdate(any());
        verify(coopInviteMapper).insertCoopInvite(invite);
    }

    // ---- CU-M-96 同一主题对同一对方不允许并存两条待确认邀约(2026-09-25 总控裁决「禁止重复」) ----

    /**
     * 按 {@code CoopInviteMapper.xml} 里 selectCoopInviteList 的动态 {@code <where>} 口径
     * (topic_id / to_type / to_id / status 逐条 AND,非空才加条件)在内存里模拟命中,
     * 让「探针少带一个判据」直接表现为误拒或误放,而不是被 any() 桩糊过去。
     */
    private void stubInviteTableWith(CoopInvite... stored) {
        List<CoopInvite> rows = Arrays.asList(stored);
        when(coopInviteMapper.selectCoopInviteList(any())).thenAnswer(call -> {
            CoopInvite q = call.getArgument(0);
            List<CoopInvite> hits = new ArrayList<CoopInvite>();
            for (CoopInvite r : rows) {
                if (q == null
                        || (q.getTopicId() == null || q.getTopicId().equals(r.getTopicId()))
                        && (q.getToType() == null || q.getToType().equals(r.getToType()))
                        && (q.getToId() == null || q.getToId().equals(r.getToId()))
                        && (q.getStatus() == null || q.getStatus().equals(r.getStatus()))) {
                    hits.add(r);
                }
            }
            return hits;
        });
    }

    /** 库里已有的一条邀约:talent(100) → merchant(toId),挂在主题 7 上,刚发出去、TTL 未过。 */
    private CoopInvite storedInvite(Integer status, Long toId) {
        CoopInvite r = inv(901L, status);
        r.setTopicId(7L);
        r.setToId(toId);
        r.setCreateTime(new Date());
        r.setExpireTime(new Date(System.currentTimeMillis() + 3600_000L));
        return r;
    }

    /** 本次要发出的邀约(同一个 talent → merchant 200 / 主题 7)。 */
    private CoopInvite outboundInvite() {
        CoopInvite invite = inv(null, 0);
        invite.setTopicId(7L);
        return invite;
    }

    @Test
    void insertInvite_rejectsDuplicatePendingInviteToSameTarget() {
        stubOpenTopic(7L);
        stubInviteTableWith(storedInvite(0, 200L));

        ServiceException ex = assertThrows(ServiceException.class, () -> svc.insertCoopInvite(outboundInvite()));

        assertEquals("你已向对方发过这个主题的邀约,等对方处理后再发", ex.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode().intValue());
        verify(coopInviteMapper, never()).insertCoopInvite(any());
    }

    @Test
    void insertInvite_allowsResendOnceCounterpartyHandledThePreviousInvite() {
        // 0 之外的每个终态都要放行:1已接受 / 2已拒绝 / 3已取消(以及 4被顶替 / 5已过期)。
        // 用同一个 svc 逐轮换桩,clearInvocations 把上一轮的 insert 计数清零。
        stubOpenTopic(7L);
        for (int resolved : new int[]{1, 2, 3, 4, 5}) {
            stubInviteTableWith(storedInvite(resolved, 200L));
            CoopInvite invite = outboundInvite();
            when(coopInviteMapper.insertCoopInvite(invite)).thenReturn(1);

            try {
                assertEquals(1, svc.insertCoopInvite(invite), "status=" + resolved + " 已不是待确认,应当允许重发");
            } catch (RuntimeException e) {
                throw new AssertionError("status=" + resolved + " 不该被重复邀约闸挡住,实际抛了:" + e, e);
            }
            org.mockito.Mockito.clearInvocations(coopInviteMapper);
        }
    }

    @Test
    void insertInvite_allowsSameTargetAnotherTopicAndAnotherTargetSameTopic() {
        stubOpenTopic(7L);
        CoopInvite invite = outboundInvite();
        when(coopInviteMapper.insertCoopInvite(invite)).thenReturn(1);
        // 判据是 topicId+toType+toId(+nodeId)相同:只换掉其中一个都不能挡。
        stubInviteTableWith(storedInvite(0, 201L));   // 同主题、另一个商家

        assertEquals(1, svc.insertCoopInvite(invite));
    }

    @Test
    void insertInvite_duplicateGateComparesNodeIdInJava_notThroughTheDynamicWhere() {
        // 官方邀约是按节点发的(createOfficialMerchantInvites 带 request.getNodeId())。
        // CoopInviteMapper.xml 的动态 <where> 只在 nodeId 非空时追加条件,所以探针不能带 nodeId
        // 去查(带 null 等于「不限节点」),否则「同主题同商家、接的是另外一站」会被误判成重复。
        // 这里两条都指向商家 200 / 主题 7,只有节点不同 ⇒ 必须放行。
        stubOpenTopic(7L);
        CoopInvite stored = storedInvite(0, 200L);
        stored.setNodeId(500L);
        stubInviteTableWith(stored);
        CoopInvite invite = outboundInvite();
        invite.setNodeId(600L);                    // 另一站
        when(coopInviteMapper.insertCoopInvite(invite)).thenReturn(1);

        assertEquals(1, svc.insertCoopInvite(invite),
                "同一待确认邀约挂在另一个节点上不构成重复");
    }

    @Test
    void insertInvite_duplicateGateStillBlocksTheSameNode() {
        // 上一负控的对称面:节点相同(且都是 600)就仍是重复。去掉 nodeId 这道比较必须让这条判红,
        // 否则「加了 nodeId 比较」等于把重复闸整个放开了。
        stubOpenTopic(7L);
        CoopInvite stored = storedInvite(0, 200L);
        stored.setNodeId(600L);
        stubInviteTableWith(stored);
        CoopInvite invite = outboundInvite();
        invite.setNodeId(600L);

        ServiceException ex = assertThrows(ServiceException.class, () -> svc.insertCoopInvite(invite));
        assertEquals("你已向对方发过这个主题的邀约,等对方处理后再发", ex.getMessage());
        verify(coopInviteMapper, never()).insertCoopInvite(any());
    }

    @Test
    void insertInvite_topicLevelPendingInviteDoesNotBlockANodeLevelInviteToTheSameTarget() {
        // 两个方向都要成立:库里那条是主题级(nodeId=null),这次要发的是某一站的节点邀约 ⇒ 放行。
        // 这条钉的是「null 与非 null 不算相同」——用 == 比会 NPE,用 equals 会把 null 当成任意匹配。
        stubOpenTopic(7L);
        stubInviteTableWith(storedInvite(0, 200L));   // nodeId 为 null 的主题级待确认
        CoopInvite invite = outboundInvite();
        invite.setNodeId(600L);
        when(coopInviteMapper.insertCoopInvite(invite)).thenReturn(1);

        assertEquals(1, svc.insertCoopInvite(invite));
    }

    @Test
    void insertInvite_duplicateGateDoesNotLetAStalePendingRowBlockResendForever() {
        // 库里那条 status=0 其实早过了 TTL。闸的读必须走本服务的 selectCoopInviteList
        // (查询前 expirePendingInvites 幂等终态化),否则这一条会永久挡住重发,
        // 而页面上它早已不可接受。
        stubOpenTopic(7L);
        CoopInvite stale = storedInvite(0, 200L);
        stale.setCreateTime(new Date(System.currentTimeMillis() - 49L * 3600_000L));
        stubInviteTableWith(stale);
        when(coopInviteMapper.expirePendingInvites(any(Date.class))).thenAnswer(call -> {
            Date now = call.getArgument(0);
            stale.setStatus(5);   // 与 expirePendingInvites 的 where 同口径:pending 且 create_time<=now-48h ⇒ 5
            return 1;
        });
        CoopInvite invite = outboundInvite();
        when(coopInviteMapper.insertCoopInvite(invite)).thenReturn(1);

        assertEquals(1, svc.insertCoopInvite(invite));
    }

    @Test
    void officialMerchantInvites_rejectWholeBatchWhenAnyRecipientAlreadyHasPendingInvite() {
        stubOpenTopic(7L);
        stubInviteTableWith(storedInvite(0, 201L));
        com.chengyinhub.business.domain.dto.OfficialMerchantInviteRequest request =
                new com.chengyinhub.business.domain.dto.OfficialMerchantInviteRequest();
        request.setTopicId(7L);
        request.setMerchantIds(Arrays.asList(200L, 201L));
        request.setShareMode(0);
        request.setFixedFee(new BigDecimal("12.00"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.createOfficialMerchantInvites(100L, request));

        assertEquals("你已向对方发过这个主题的邀约,等对方处理后再发", ex.getMessage());
        // 整批 fail-closed:不能一家已邀过就把另一家照发,那样对方看到半截名单无从判断。
        verify(coopInviteMapper, never()).insertCoopInvite(any());
    }

    private com.chengyinhub.business.domain.dto.OfficialMerchantInviteRequest officialNodeInviteRequest() {
        stubOpenTopic(7L);
        com.chengyinhub.business.mapper.CmsTopicNodeMapper nodeMapper =
                mock(com.chengyinhub.business.mapper.CmsTopicNodeMapper.class);
        ReflectionTestUtils.setField(svc, "cmsTopicNodeMapper", nodeMapper);
        com.chengyinhub.business.domain.CmsTopicNode node = new com.chengyinhub.business.domain.CmsTopicNode();
        node.setId(600L);
        node.setTopicId(7L);
        when(nodeMapper.selectCmsTopicNodeById(600L)).thenReturn(node);
        com.chengyinhub.business.domain.dto.OfficialMerchantInviteRequest request =
                new com.chengyinhub.business.domain.dto.OfficialMerchantInviteRequest();
        request.setTopicId(7L);
        request.setNodeId(600L);
        request.setMerchantIds(Arrays.asList(200L, 201L));
        return request;
    }

    @Test
    void officialMerchantInvites_rejectWholeBatchWhenSameNodeAlreadyPending() {
        com.chengyinhub.business.domain.dto.OfficialMerchantInviteRequest request = officialNodeInviteRequest();
        CoopInvite stored = storedInvite(0, 201L);
        stored.setInviteType(CoopInvite.TYPE_OFFICIAL_MERCHANT);
        stored.setFromType(CoopInvite.FROM_OFFICIAL);
        stored.setNodeId(600L);
        stubInviteTableWith(stored);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.createOfficialMerchantInvites(100L, request));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode().intValue());
        verify(coopInviteMapper, never()).insertCoopInvite(any());
    }

    @Test
    void officialMerchantInvites_topicLevelPendingDoesNotBlockNodeInvite() {
        com.chengyinhub.business.domain.dto.OfficialMerchantInviteRequest request = officialNodeInviteRequest();
        stubInviteTableWith(storedInvite(0, 201L));

        assertEquals(2, svc.createOfficialMerchantInvites(100L, request).size());

        ArgumentCaptor<CoopInvite> inserted = ArgumentCaptor.forClass(CoopInvite.class);
        verify(coopInviteMapper, times(2)).insertCoopInvite(inserted.capture());
        assertEquals(Long.valueOf(600L), inserted.getAllValues().get(0).getNodeId());
        assertEquals(Long.valueOf(600L), inserted.getAllValues().get(1).getNodeId());
    }

    @Test
    void acceptInvite_revalidatesTypeTargetMatrixBeforeCreatingOrder() {
        CoopInvite forged = inv(1L, 0);
        forged.setToType("club");
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(forged);

        assertThrows(IllegalStateException.class, () -> svc.acceptInviteWithLock(1L, null));

        verify(coopOrderMapper, never()).insertCoopOrder(any());
    }

    @Test
    void acceptType1_rejectsInactiveInitiatingMerchant() {
        CoopInvite invite = clubInvite(1L, 0, 8001L, 100L, 0);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(invite);
        when(mmsMerchantMapper.selectMmsMerchantByMemberIdForUpdate(300L)).thenReturn(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.acceptInviteWithLock(1L, null));

        assertTrue(ex.getMessage().contains("审核通过"));
        verify(coopOrderMapper, never()).insertCoopOrder(any());
    }

    @Test
    void insertType0_rejectsInactiveInitiatingMerchantBeforePersistence() {
        stubOpenTopic(7L);
        CoopInvite invite = outboundInvite();
        invite.setFromType("merchant");
        when(mmsMerchantMapper.selectMmsMerchantByMemberIdForUpdate(100L)).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.insertCoopInvite(invite));

        assertTrue(error.getMessage().contains("审核通过且启用"));
        verify(coopInviteMapper, never()).insertCoopInvite(any());
    }

    @Test
    void acceptType0_rejectsInactiveInitiatingMerchantBeforeCreatingOrder() {
        CoopInvite invite = inv(1L, 0);
        invite.setFromType("merchant");
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(invite);
        when(mmsMerchantMapper.selectMmsMerchantByMemberIdForUpdate(100L)).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.acceptInviteWithLock(1L, null));

        assertTrue(error.getMessage().contains("审核通过且启用"));
        verify(coopInviteMapper, never()).updateCoopInvite(any());
        verify(coopOrderMapper, never()).insertCoopOrder(any());
    }

    @Test
    void insertType0_allowsActiveInitiatingMerchantAndInvitee() {
        stubOpenTopic(7L);
        CoopInvite invite = outboundInvite();
        invite.setFromType("merchant");
        when(coopInviteMapper.insertCoopInvite(invite)).thenReturn(1);

        assertEquals(1, svc.insertCoopInvite(invite));

        verify(mmsMerchantMapper).selectMmsMerchantByMemberIdForUpdate(100L);
        verify(mmsMerchantMapper).selectMmsMerchantByMemberIdForUpdate(200L);
        verify(coopInviteMapper).insertCoopInvite(invite);
    }

    // ---- acceptInviteWithLock ----

    @Test
    void rejectPendingInviteLocksAndRefusesToOverwriteConcurrentAccept() {
        CoopInvite accepted = inv(1L, 1);
        accepted.setToType("merchant");
        accepted.setToId(201L);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(accepted);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.rejectPendingInviteWithLock(1L, 201L, "无法承接"));

        assertTrue(error.getMessage().contains("状态已变化"));
        verify(coopInviteMapper, never()).updateCoopInvite(any());
    }

    @Test
    void pendingDecisionsRequireNonblankReasonBeforeWriting() {
        CoopInvite pending = inv(1L, 0);
        pending.setFromType("talent");
        pending.setFromId(101L);
        pending.setToType("merchant");
        pending.setToId(201L);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(pending);
        when(coopInviteMapper.updateCoopInvite(any())).thenReturn(1);

        for (String reason : Arrays.asList(null, "", " \t\n ")) {
            assertEquals("请填写拒绝原因", assertThrows(IllegalStateException.class,
                    () -> svc.rejectPendingInviteWithLock(1L, 201L, reason)).getMessage());
            assertEquals("请填写撤回理由", assertThrows(IllegalStateException.class,
                    () -> svc.cancelPendingInviteWithLock(1L, 101L, reason)).getMessage());
        }
        verify(coopInviteMapper, never()).updateCoopInvite(any());
    }

    @Test
    void pendingRejectionPersistsTrimmedReason() {
        CoopInvite pending = inv(1L, 0);
        pending.setToType("merchant");
        pending.setToId(201L);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(pending);
        when(coopInviteMapper.updateCoopInvite(any())).thenReturn(1);

        svc.rejectPendingInviteWithLock(1L, 201L, "  无法承接  ");

        verify(coopInviteMapper).updateCoopInvite(org.mockito.ArgumentMatchers.argThat(update ->
                Integer.valueOf(2).equals(update.getStatus())
                        && "无法承接".equals(update.getHandleReason())));
    }

    @Test
    void cancelPendingInviteLocksAndChecksResolvedInitiatorOwner() {
        CoopInvite pending = inv(1L, 0);
        pending.setFromType("talent");
        pending.setFromId(101L);
        pending.setToType("merchant");
        pending.setToId(201L);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(pending);
        when(coopInviteMapper.updateCoopInvite(any())).thenReturn(1);

        svc.cancelPendingInviteWithLock(1L, 101L, "  场地临时关闭  ");

        // CU-C-86:撤回理由落 handle_reason(去首尾空白),双方详情才读得回
        verify(coopInviteMapper).updateCoopInvite(org.mockito.ArgumentMatchers.argThat(update ->
                Long.valueOf(1L).equals(update.getId())
                        && Integer.valueOf(3).equals(update.getStatus())
                        && "场地临时关闭".equals(update.getHandleReason())));
    }

    @Test
    void cancelPendingInviteRejectsOverlongReasonBeforeWriting() {
        CoopInvite pending = inv(1L, 0);
        pending.setFromType("talent");
        pending.setFromId(101L);
        pending.setToType("merchant");
        pending.setToId(201L);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(pending);
        StringBuilder longReason = new StringBuilder();
        for (int i = 0; i < 256; i++) longReason.append('字');

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.cancelPendingInviteWithLock(1L, 101L, longReason.toString()));

        assertTrue(error.getMessage().contains("255"));
        verify(coopInviteMapper, never()).updateCoopInvite(any());
    }

    @Test
    void rawClubIdCollisionCannotRejectInviteAsClubOwner() {
        CoopInvite pending = clubInvite(1L, 0, 77L, 7001L, 0);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(pending);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.rejectPendingInviteWithLock(1L, 77L, "不参与"));

        assertTrue(error.getMessage().contains("仅受邀方"));
        verify(coopInviteMapper, never()).updateCoopInvite(any());
    }

    @Test
    void acceptMerchantInviteRechecksActiveMerchantInsideTransaction() {
        CoopInvite target = inv(1L, 0);
        target.setGameId(9L);
        target.setToType("merchant");
        target.setToId(201L);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(Collections.emptyList());
        MmsMerchant disabled = new MmsMerchant();
        disabled.setMemberId(201L);
        disabled.setStatus(1L);
        disabled.setAccountStatus(2L);
        disabled.setDelFlag(0);
        when(mmsMerchantMapper.selectMmsMerchantByMemberIdForUpdate(201L)).thenReturn(disabled);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.acceptInviteWithLock(1L, null));

        assertTrue(error.getMessage().contains("审核通过且启用"));
        verify(coopInviteMapper, never()).updateCoopInvite(any());
        verify(coopOrderMapper, never()).insertCoopOrder(any());
    }

    @Test
    void accept_rejectsWhenNotPending() {
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(inv(1L, 1));
        assertThrows(IllegalStateException.class, () -> svc.acceptInviteWithLock(1L, null));
    }

    @Test
    void accept_legacyType2_isReadOnlyAndNeverTerminalized() {
        CoopInvite legacy = inv(1L, 0);
        legacy.setInviteType(CoopInvite.TYPE_LEGACY_MERCHANT_NODE);
        legacy.setCreateTime(new Date(System.currentTimeMillis() - 49L * 60L * 60L * 1000L));
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(legacy);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.acceptInviteWithLock(1L, 9L));

        assertTrue(error.getMessage().contains("仅供查看"));
        verify(coopInviteMapper, never()).updateCoopInvite(any());
        verify(coopOrderMapper, never()).insertCoopOrder(any());
    }

    /**
     * 48h 终态闸：不能只依赖列表或 Quartz 扫描。用户可以拿旧页面直接点接受，
     * 所以接受事务拿到邀约行锁后必须按服务端时间再次判定，并把待确认原子落成已过期。
     */
    @Test
    void accept_expiredPendingInvite_isTerminalizedAndRejected() {
        CoopInvite expired = inv(1L, 0);
        expired.setGameId(5L);
        expired.setCreateTime(new Date(System.currentTimeMillis() - 49L * 60L * 60L * 1000L));
        expired.setExpireTime(new Date(System.currentTimeMillis() + 24L * 60L * 60L * 1000L));
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(expired);
        when(coopInviteMapper.updateCoopInvite(any())).thenReturn(1);
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(Collections.<CoopInvite>emptyList());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.acceptInviteWithLock(1L, null));

        assertTrue(error.getMessage().contains("已过期"),
                "该红在【48h 过期】这道闸上，不能被相邻的“已处理”异常接住，实际:" + error.getMessage());
        ArgumentCaptor<CoopInvite> updateCaptor = ArgumentCaptor.forClass(CoopInvite.class);
        verify(coopInviteMapper).updateCoopInvite(updateCaptor.capture());
        assertEquals(Integer.valueOf(5), updateCaptor.getValue().getStatus(),
                "过期邀约必须写入终态 status=5");
        verify(coopOrderMapper, never()).insertCoopOrder(any());
    }

    @Test
    void accept_expiredTerminalWrite_isNotRolledBackWithRejection() throws Exception {
        for (java.lang.reflect.Method method : Arrays.asList(
                CoopInviteServiceImpl.class.getMethod("acceptInviteWithLock", Long.class, Long.class),
                CoopInviteServiceImpl.class.getMethod("acceptOfficialInviteWithLock", Long.class, Long.class),
                CoopInviteServiceImpl.class.getMethod("rejectOfficialInviteWithLock", Long.class, Long.class, String.class))) {
            Transactional transaction = method.getAnnotation(Transactional.class);
            assertNotNull(transaction, method.getName() + " 必须保持事务边界");
            assertTrue(Arrays.asList(transaction.noRollbackFor()).contains(CoopInviteExpiredException.class),
                    method.getName() + " 拒绝过期邀约时 status=5 必须提交，不能被同一异常回滚");
        }
    }

    @Test
    void selectList_terminalizesExpiredRowsBeforeReading() {
        List<String> calls = new ArrayList<String>();
        when(coopInviteMapper.expirePendingInvites(any())).thenAnswer(invocation -> {
            calls.add("expire");
            return 1;
        });
        when(coopInviteMapper.selectCoopInviteList(any())).thenAnswer(invocation -> {
            calls.add("select");
            CoopInvite expired = inv(1L, 5);
            return Collections.singletonList(expired);
        });

        List<CoopInvite> rows = svc.selectCoopInviteList(new CoopInvite());

        assertEquals(Arrays.asList("expire", "select"), calls,
                "列表必须先幂等写入过期终态再读回");
        assertEquals(Integer.valueOf(5), rows.get(0).getStatus());
    }

    @Test
    void insert_pendingInviteCapsDeadlineAt48Hours() {
        CoopInvite invite = inv(null, null);
        invite.setExpireTime(new Date(System.currentTimeMillis() + 10L * 24L * 60L * 60L * 1000L));
        long before = System.currentTimeMillis();

        svc.insertCoopInvite(invite);

        long after = System.currentTimeMillis();
        long actual = invite.getExpireTime().getTime();
        assertTrue(actual >= before + 48L * 60L * 60L * 1000L
                        && actual <= after + 48L * 60L * 60L * 1000L,
                "待确认邀约最迟应在服务端创建时间+48h过期，实际:" + new Date(actual));
    }

    /** 负控：普通 service 入口收到伪造 fromType=official + shareMode=1 必须在落库前拒绝。 */
    @Test
    void insert_rejectsForgedOfficialSalesPercentAtServiceGate() {
        CoopInvite forged = inv(null, 0);
        forged.setFromType(CoopInvite.FROM_OFFICIAL);
        forged.setShareMode(1);
        forged.setShareRate(new BigDecimal("20"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.insertCoopInvite(forged));
        assertEquals("官方邀约必须通过官方入口创建", error.getMessage(),
                "伪造官方来源必须被 service 必经闸拒绝，不能依赖 Controller 隐藏字段");
        verify(coopInviteMapper, never()).insertCoopInvite(any());
    }

    @Test
    void insert_rejectsRetiredType2AtServiceGate() {
        CoopInvite legacy = inv(null, 0);
        legacy.setInviteType(CoopInvite.TYPE_LEGACY_MERCHANT_NODE);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.insertCoopInvite(legacy));

        assertTrue(error.getMessage().contains("仅供查看"));
        verify(coopInviteMapper, never()).insertCoopInvite(any());
    }

    @Test
    void update_legacyType2_isReadOnly() {
        CoopInvite legacy = inv(1L, 0);
        legacy.setInviteType(CoopInvite.TYPE_LEGACY_MERCHANT_NODE);
        when(coopInviteMapper.selectCoopInviteById(1L)).thenReturn(legacy);
        CoopInvite update = inv(1L, 2);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.updateCoopInvite(update));

        assertTrue(error.getMessage().contains("仅供查看"));
        verify(coopInviteMapper, never()).updateCoopInvite(any());
    }

    @Test
    void insertInvite_rejectsDissolvedTopicParentBeforePersistence() {
        CoopInvite invite = inv(null, null);
        invite.setTopicId(7L);
        invite.setToType("merchant");
        invite.setToId(201L);
        CmsTopic topic = new CmsTopic();
        topic.setId(7L);
        topic.setDelFlag(0);
        topic.setLifecycle(1);
        topic.setClubId(73L);
        topic.setDelFlag(0);
        topic.setRecruitDeadline(futureRecruitDeadline());
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(topic);
        when(clubMapper.selectClubByIdForUpdate(73L)).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.insertCoopInvite(invite));

        assertEquals("关联俱乐部已解散，不能发起合作邀请", error.getMessage(),
                "该红必须指认到普通邀约写入的解散互斥闸");
        verify(coopInviteMapper, never()).insertCoopInvite(any());
    }

    /**
     * CU-M-48:「一主题一俱乐部」原来只在接受侧判,发起侧不判就能造出一条永远接不上的待确认邀约
     * (对方点了接受必被拒,列表长期挂待确认)。这里必须两道闸同判据、同文案。
     */
    @Test
    void insert_rejectsSecondClubInviteOnTopicThatAlreadyHasClub() {
        // 同主题已有一个 status=1 的俱乐部合作
        CoopInvite acceptedClub = inv(100L, 1);
        acceptedClub.setToType("club");
        when(coopInviteMapper.selectCoopInviteList(any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(acceptedClub)));
        stubOpenTopic(7L);

        // 发起侧:再向同一主题发一条 club 邀约
        CoopInvite invite = clubInvite(null, 0, 8001L, 100L, 0);
        invite.setTopicId(7L);
        IllegalStateException insertError = assertThrows(IllegalStateException.class,
                () -> svc.insertCoopInvite(invite));
        assertTrue(insertError.getMessage().contains("一个主题只能有一个组织方"),
                "该红必须落在【一主题一俱乐部】这道闸上,实际:" + insertError.getMessage());
        verify(coopInviteMapper, never()).insertCoopInvite(any());

        // 接受侧同一主题:文案必须一字不差,否则前端(pages/coop/invite/index.js 回显 msg)会出现两套说法
        CoopInvite target = clubInvite(1L, 0, 8001L, 100L, 0);
        target.setTopicId(7L);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        IllegalStateException acceptError = assertThrows(IllegalStateException.class,
                () -> svc.acceptInviteWithLock(1L, null));
        assertEquals(acceptError.getMessage(), insertError.getMessage(),
                "发起侧与接受侧必须是同一句文案");
    }

    /** 正控:同主题已接受的都是商家(不是俱乐部)—— 第一家居俱乐部仍必须能发出去。 */
    @Test
    void insert_allowsFirstClubInviteWhenNoClubAcceptedYet() {
        List<CoopInvite> merchants = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            CoopInvite a = inv(100L + i, 1);
            a.setToType("merchant");
            merchants.add(a);
        }
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(merchants);
        stubOpenTopic(7L);

        CoopInvite invite = clubInvite(null, 0, 8001L, 100L, 0);
        invite.setTopicId(7L);
        when(coopInviteMapper.insertCoopInvite(invite)).thenReturn(1);

        assertEquals(1, svc.insertCoopInvite(invite));
        verify(coopInviteMapper).insertCoopInvite(invite);
    }

    /** 边界:有俱乐部的主题仍可邀商家 —— 这道闸只拦「本次目标就是 club」,不误伤其他 toType。 */
    @Test
    void insert_allowsMerchantInviteOnTopicThatAlreadyHasClub() {
        CoopInvite acceptedClub = inv(100L, 1);
        acceptedClub.setToType("club");
        when(coopInviteMapper.selectCoopInviteList(any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(acceptedClub)));
        stubOpenTopic(7L);

        CoopInvite invite = inv(null, 0);
        invite.setTopicId(7L);
        when(coopInviteMapper.insertCoopInvite(invite)).thenReturn(1);

        assertEquals(1, svc.insertCoopInvite(invite));
        verify(coopInviteMapper).insertCoopInvite(invite);
    }

    @Test
    void insertInvite_rejectsFrozenPricingRosterForMerchantAndClubBeforeWriting() {
        CmsTopic pricing = new CmsTopic();
        pricing.setId(7L);
        pricing.setDelFlag(0);
        pricing.setLifecycle(TopicLifecycleState.PRICING.getCode());
        pricing.setRecruitDeadline(futureRecruitDeadline());
        pricing.setDelFlag(0);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(pricing);
        CoopInvite club = clubInvite(null, 0, 77L, 42L, 0);
        club.setTopicId(7L);

        for (CoopInvite invite : Arrays.asList(outboundInvite(), club)) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> svc.insertCoopInvite(invite));
            assertTrue(error.getMessage().contains("锁价"), error.getMessage());
        }
        verify(coopInviteMapper, never()).insertCoopInvite(any());
    }

    /** 未锁价、仍在招商期的主题(发起侧闸与接受侧共用同一个主题预置) */
    private void stubOpenTopic(Long topicId) {
        CmsTopic open = new CmsTopic();
        open.setId(topicId);
        open.setLifecycle(1);
        open.setRecruitDeadline(futureRecruitDeadline());
        open.setDelFlag(0);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId)).thenReturn(open);
    }

    @Test
    void accept_topicLevel_rejectsWhenTopicAlreadyPriced() {
        // [资损·冻结阵容] 主题已锁价(lifecycle≥3)后禁止再接受合作邀请入伙,
        // 否则新增分成商家使结算阵容超定价假设→margin翻负→守恒拒结→款项悬空。
        CoopInvite target = inv(1L, 0);
        target.setTopicId(7L);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        com.chengyinhub.business.domain.CmsTopic priced = new com.chengyinhub.business.domain.CmsTopic();
        priced.setId(7L);
        priced.setLifecycle(3); // 已锁价/售票
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(priced);

        assertThrows(IllegalStateException.class, () -> svc.acceptInviteWithLock(1L, null));
        verify(coopOrderMapper, times(0)).insertCoopOrder(any());
    }

    @Test
    void accept_outboundClubTopic_rejectsAfterDissolutionBeforeCreatingOrder() {
        CoopInvite target = inv(1L, 0);
        target.setTopicId(7L);
        target.setToType("merchant");
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        CmsTopic topic = new CmsTopic();
        topic.setId(7L);
        topic.setDelFlag(0);
        topic.setClubId(88L);
        topic.setLifecycle(1);
        topic.setRecruitDeadline(futureRecruitDeadline());
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(topic);
        when(clubMapper.selectClubByIdForUpdate(88L)).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.acceptInviteWithLock(1L, null));

        assertTrue(error.getMessage().contains("关联俱乐部已解散"),
                "该红必须指认到合作写入的解散互斥闸，实际:" + error.getMessage());
        verify(coopOrderMapper, never()).insertCoopOrder(any());
    }

    /**
     * 【施工图 Task 3 Step 6】主题合作方已满 → 不得再接受。
     *
     * <p><b>为什么这条闸值钱</b>:§3.6 定的路是「报名进候选池 → 发布者发 type0 邀约(带条款)
     * → 商家接受 → coop_order」。四道闸(主题锁价禁入 / 主题级合作方上限 / 被邀商家封顶 5 家 / 保证金)
     * <b>全长在 acceptInviteWithLock 这一步上</b>。这正是不能从报名里抄条款直接建单的原因 ——
     * 那条路会把四道闸整个绕开(定稿 §6.5-C V8 点名的「两条路口径不一」)。
     *
     * <p>⚠️ <b>必须断言错误文案</b>,不能只 assertThrows:这里前后紧挨着两道都抛
     * IllegalStateException 的闸(主题合作方上限 / 被邀商家封顶 5)。只断言「抛了」的话,
     * 拆掉任意一道另一道都会接住 ⇒ 测试恒绿、测不出任何东西。
     * (实测过:第一版没断言文案,把 `merchants >= 5` 整个拆掉,27 条测试照样全绿。)
     *
     * <p>cap 由 dynamicTopicSlotCap 按发布者履约率浮动(8/5/2),样本不足时默认 5 —— 本用例走默认。
     */
    @Test
    void accept_topicLevel_rejectsWhenSlotCapReached() {
        CoopInvite target = inv(1L, 0);
        target.setTopicId(7L);
        target.setToType("merchant");
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        com.chengyinhub.business.domain.CmsTopic open = new com.chengyinhub.business.domain.CmsTopic();
        open.setId(7L);
        open.setDelFlag(0);
        open.setLifecycle(1);   // 未锁价:把「锁价闸」排除掉,让容量闸成为唯一会拦的东西
        open.setRecruitDeadline(futureRecruitDeadline());
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(open);

        // 该主题已有 5 家接受,默认 cap=5 ⇒ 已满
        java.util.List<CoopInvite> five = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            CoopInvite a = inv(100L + i, 1);
            a.setToType("merchant");
            five.add(a);
        }
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(five);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.acceptInviteWithLock(1L, null));
        assertTrue(ex.getMessage().contains("合作方已满"),
                "该红在【主题合作方上限】这道闸上,实际:" + ex.getMessage());
        verify(coopOrderMapper, times(0)).insertCoopOrder(any());
    }

    /**
     * ★一主题只能有一个俱乐部(2026-08-05 用户拍板)。
     *
     * 不是配额问题,是分账基数不可分割:结算里俱乐部走 LEAD_FEE,基数写死成
     * 「全主题核销数 / 全主题销售额」(CmsTopicServiceImpl 的 isClub 分支)。
     * 两个俱乐部各 20% 不是分掉 20%,而是各自按整份销售额拿 20% ——
     * 同一批玩家被算了两次带队费。
     *
     * 既有的 90% 总和封顶挡不住:两家各 20% 合计 40%,完全合法地通过。
     * 所以必须断言文案指明是这道闸(紧挨着还有 cap / 商家封顶 / 90% 三道同型异常)。
     */
    @Test
    void accept_topicLevel_rejectsSecondClub() {
        CoopInvite target = clubInvite(1L, 0, 8001L, 100L, 0);
        target.setTopicId(7L);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        com.chengyinhub.business.domain.CmsTopic open = new com.chengyinhub.business.domain.CmsTopic();
        open.setId(7L);
        open.setDelFlag(0);
        open.setLifecycle(1);
        open.setRecruitDeadline(futureRecruitDeadline());
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(open);

        // 只有 1 家已接受的俱乐部:cap(5)和商家封顶(5)都远没到,90% 也不涉及 ——
        // 能拦住的只可能是新加的这道闸
        CoopInvite existingClub = inv(100L, 1);
        existingClub.setToType("club");
        when(coopInviteMapper.selectCoopInviteList(any()))
                .thenReturn(new java.util.ArrayList<>(java.util.Collections.singletonList(existingClub)));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.acceptInviteWithLock(1L, null));
        assertTrue(ex.getMessage().contains("一个主题只能有一个组织方"),
                "该红在【一主题一俱乐部】这道闸上,实际:" + ex.getMessage());
        verify(coopOrderMapper, times(0)).insertCoopOrder(any());
    }

    /** 已有的是商家、这次邀的是俱乐部 ⇒ 必须放行,否则上面那条闸是「见俱乐部就拒」的假闸。 */
    @Test
    void accept_topicLevel_allowsFirstClubAlongsideMerchants() {
        CoopInvite target = clubInvite(1L, 0, 8001L, 100L, 0);
        target.setTopicId(7L);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        com.chengyinhub.business.domain.CmsTopic open = new com.chengyinhub.business.domain.CmsTopic();
        open.setId(7L);
        open.setDelFlag(0);
        open.setLifecycle(1);
        open.setRecruitDeadline(futureRecruitDeadline());
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(open);

        java.util.List<CoopInvite> merchants = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            CoopInvite a = inv(100L + i, 1);
            a.setToType("merchant");
            merchants.add(a);
        }
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(merchants);

        svc.acceptInviteWithLock(1L, null);

        verify(coopOrderMapper, times(1)).insertCoopOrder(any());
    }

    @Test
    void accept_clubPublisherInviteUsesTopicPublisherForDynamicCapacity() {
        CoopInvite target = clubInvite(1L, 0, 8001L, 100L, 0);
        target.setFromType("club");
        target.setFromId(7001L);
        target.setTopicId(7L);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        CmsTopic open = new CmsTopic();
        open.setId(7L);
        open.setDelFlag(0);
        open.setMemberId(9003L);
        open.setClubId(7001L);
        open.setLifecycle(1);
        open.setRecruitDeadline(futureRecruitDeadline());
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(open);
        com.chengyinhub.business.domain.Club publisher = new com.chengyinhub.business.domain.Club();
        publisher.setId(7001L);
        when(clubMapper.selectClubByIdForUpdate(7001L)).thenReturn(publisher);
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(Collections.emptyList());
        CoopInviteServiceImpl observed = org.mockito.Mockito.spy(svc);
        org.mockito.Mockito.doReturn(5).when(observed).dynamicTopicSlotCap(any());

        observed.acceptInviteWithLock(1L, null);

        verify(observed, times(2)).dynamicTopicSlotCap(9003L);
        verify(observed, never()).dynamicTopicSlotCap(7001L);
        verify(coopOrderMapper).insertCoopOrder(any());
    }

    /** 负控的另一半:没满就必须放行 —— 否则上面那条恒绿也证明不了闸的存在 */
    @Test
    void accept_topicLevel_allowsWhenUnderCap() {
        CoopInvite target = inv(1L, 0);
        target.setTopicId(7L);
        target.setToType("merchant");
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        com.chengyinhub.business.domain.CmsTopic open = new com.chengyinhub.business.domain.CmsTopic();
        open.setId(7L);
        open.setDelFlag(0);
        open.setLifecycle(1);
        open.setRecruitDeadline(futureRecruitDeadline());
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(open);
        // 只有 2 家接受,cap 默认 5 ⇒ 未满
        java.util.List<CoopInvite> two = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            CoopInvite a = inv(100L + i, 1);
            a.setToType("merchant");
            two.add(a);
        }
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(two);

        svc.acceptInviteWithLock(1L, null);

        // 未满 → 正常接受并建单(★ 合作单只在这里产生 —— 结算只认它)
        verify(coopOrderMapper, times(1)).insertCoopOrder(any());
    }

    /**
     * P1-COOP-01:主题级(自由定向)已接受的分成型(share_mode=1)邀约,share_rate 总和
     * 在本次接受事务内不得超过 90%。cap(默认 5)本身拦不住这个 —— 5 个名额每个 20% 都在
     * cap 内,但 5×20%=100% 会把发布者保底吃穿。必须断言文案指明是分成上限闸,不能只 assertThrows
     * (紧挨着还有 slot cap / 商家封顶 5 两道同型异常的闸)。
     */
    @Test
    void accept_topicLevel_rejectsWhenShareRateSumExceeds90() {
        CoopInvite target = inv(1L, 0);
        target.setTopicId(7L);
        target.setToType("merchant");
        target.setShareMode(1);
        target.setShareRate(new BigDecimal("20"));
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        com.chengyinhub.business.domain.CmsTopic open = new com.chengyinhub.business.domain.CmsTopic();
        open.setId(7L);
        open.setDelFlag(0);
        open.setLifecycle(1);
        open.setRecruitDeadline(futureRecruitDeadline());
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(open);

        // 已接受两家分成型,合计 75%;本次再进 20% ⇒ 95% > 90%
        CoopInvite a1 = inv(101L, 1);
        a1.setToType("merchant"); a1.setShareMode(1); a1.setShareRate(new BigDecimal("40"));
        CoopInvite a2 = inv(102L, 1);
        a2.setToType("merchant"); a2.setShareMode(1); a2.setShareRate(new BigDecimal("35"));
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(Arrays.asList(a1, a2));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.acceptInviteWithLock(1L, null));
        assertTrue(ex.getMessage().contains("分成比例之和超过"),
                "该红在【分成比例总和上限】这道闸上,实际:" + ex.getMessage());
        verify(coopOrderMapper, times(0)).insertCoopOrder(any());
    }

    /** 负控的另一半:合计未超 90% 就必须放行(不分成的邀约不计入总和)。 */
    @Test
    void accept_topicLevel_allowsWhenShareRateSumUnder90() {
        CoopInvite target = inv(1L, 0);
        target.setTopicId(7L);
        target.setToType("merchant");
        target.setShareMode(1);
        target.setShareRate(new BigDecimal("20"));
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        com.chengyinhub.business.domain.CmsTopic open = new com.chengyinhub.business.domain.CmsTopic();
        open.setId(7L);
        open.setDelFlag(0);
        open.setLifecycle(1);
        open.setRecruitDeadline(futureRecruitDeadline());
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(open);

        // 已接受一家分成型 40% + 一家引流型(share_mode=0,无金额条款,不计入)
        CoopInvite a1 = inv(101L, 1);
        a1.setToType("merchant"); a1.setShareMode(1); a1.setShareRate(new BigDecimal("40"));
        CoopInvite a2 = inv(102L, 1);
        a2.setToType("merchant"); a2.setShareMode(0); a2.setShareRate(null);
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(Arrays.asList(a1, a2));

        svc.acceptInviteWithLock(1L, null);

        verify(coopOrderMapper, times(1)).insertCoopOrder(any());
    }

    /**
     * P1-COOP-R2 fail-closed 之一:本次要接受的邀约本身是分成型(share_mode=1)但 share_rate=null。
     * 旧实现的判断是 `share_mode==1 && share_rate!=null` 才进入求和/封顶逻辑,share_rate=null 会让
     * 整条闸直接跳过——分成型条款没有费率却被当"不参与分成"放行,90% 封顶对它形同虚设。
     */
    @Test
    void accept_topicLevel_rejectsCurrentInviteShareRateNull_failClosed() {
        CoopInvite target = inv(1L, 0);
        target.setTopicId(7L);
        target.setToType("merchant");
        target.setShareMode(1);
        target.setShareRate(null);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        com.chengyinhub.business.domain.CmsTopic open = new com.chengyinhub.business.domain.CmsTopic();
        open.setId(7L);
        open.setDelFlag(0);
        open.setLifecycle(1);
        open.setRecruitDeadline(futureRecruitDeadline());
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(open);
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(new ArrayList<CoopInvite>());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.acceptInviteWithLock(1L, null));
        assertTrue(ex.getMessage().contains("分成"),
                "分成型条款 share_rate=null 必须 fail-closed 拒绝,实际:" + ex.getMessage());
        verify(coopOrderMapper, times(0)).insertCoopOrder(any());
    }

    /** fail-closed 之二:负数费率同样是脏条款,不能被当合法值参与求和。 */
    @Test
    void accept_topicLevel_rejectsCurrentInviteShareRateNegative_failClosed() {
        CoopInvite target = inv(1L, 0);
        target.setTopicId(7L);
        target.setToType("merchant");
        target.setShareMode(1);
        target.setShareRate(new BigDecimal("-5"));
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        com.chengyinhub.business.domain.CmsTopic open = new com.chengyinhub.business.domain.CmsTopic();
        open.setId(7L);
        open.setDelFlag(0);
        open.setLifecycle(1);
        open.setRecruitDeadline(futureRecruitDeadline());
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(open);
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(new ArrayList<CoopInvite>());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.acceptInviteWithLock(1L, null));
        assertTrue(ex.getMessage().contains("分成"),
                "分成型条款 share_rate 为负数必须拒绝,实际:" + ex.getMessage());
        verify(coopOrderMapper, times(0)).insertCoopOrder(any());
    }

    /** fail-closed 之三:单条本身超过 90% 的分成条款直接拒绝,不必等求和。 */
    @Test
    void accept_topicLevel_rejectsCurrentInviteShareRateOver90_failClosed() {
        CoopInvite target = inv(1L, 0);
        target.setTopicId(7L);
        target.setToType("merchant");
        target.setShareMode(1);
        target.setShareRate(new BigDecimal("95"));
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        com.chengyinhub.business.domain.CmsTopic open = new com.chengyinhub.business.domain.CmsTopic();
        open.setId(7L);
        open.setDelFlag(0);
        open.setLifecycle(1);
        open.setRecruitDeadline(futureRecruitDeadline());
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(open);
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(new ArrayList<CoopInvite>());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.acceptInviteWithLock(1L, null));
        assertTrue(ex.getMessage().contains("分成"),
                "分成型条款单条超过 90% 必须拒绝,实际:" + ex.getMessage());
        verify(coopOrderMapper, times(0)).insertCoopOrder(any());
    }

    /** fail-closed:已声明非分成模式时，不能把分成比例静默冻结进合作单。 */
    @Test
    void accept_topicLevel_rejectsShareRateWhenModeIsNotShare_failClosed() {
        CoopInvite target = inv(1L, 0);
        target.setTopicId(7L);
        target.setToType("merchant");
        target.setShareMode(0);
        target.setShareRate(new BigDecimal("20"));
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        com.chengyinhub.business.domain.CmsTopic open = new com.chengyinhub.business.domain.CmsTopic();
        open.setId(7L);
        open.setDelFlag(0);
        open.setLifecycle(1);
        open.setRecruitDeadline(futureRecruitDeadline());
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(open);
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(new ArrayList<CoopInvite>());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.acceptInviteWithLock(1L, null));
        assertTrue(ex.getMessage().contains("报酬模式"),
                "非分成模式携带分成比例必须 fail-closed 拒绝,实际:" + ex.getMessage());
        verify(coopOrderMapper, never()).insertCoopOrder(any());
    }

    /** fail-closed:携带金额条款却不声明报酬模式时，不能把条款静默落成合作单。 */
    @Test
    void accept_topicLevel_rejectsMissingShareModeWithShareRate_failClosed() {
        CoopInvite target = inv(1L, 0);
        target.setTopicId(7L);
        target.setToType("merchant");
        target.setShareRate(new BigDecimal("20"));
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        CmsTopic open = new CmsTopic();
        open.setId(7L);
        open.setDelFlag(0);
        open.setLifecycle(1);
        open.setRecruitDeadline(futureRecruitDeadline());
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(open);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.acceptInviteWithLock(1L, null));
        assertTrue(ex.getMessage().contains("报酬模式"),
                "缺失报酬模式的金额条款必须 fail-closed 拒绝,实际:" + ex.getMessage());
        verify(coopOrderMapper, never()).insertCoopOrder(any());
    }

    /** 固定型(share_mode=2)但没填正数固定费:结算按固定档付款却无金额,必须拒绝。 */
    @Test
    void accept_topicLevel_rejectsFixedModeWithoutFixedFee_failClosed() {
        CoopInvite target = topicLevelInvite(2, null, null);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.acceptInviteWithLock(1L, null));
        assertTrue(ex.getMessage().contains("固定"),
                "固定型缺固定费必须 fail-closed 拒绝,实际:" + ex.getMessage());
        verify(coopOrderMapper, never()).insertCoopOrder(any());
    }

    /** 固定型却同时携带分成比例:两种付款口径并存,结算会按哪种解释成了运气,必须拒绝。 */
    @Test
    void accept_topicLevel_rejectsFixedModeWithShareRate_failClosed() {
        CoopInvite target = topicLevelInvite(2, new BigDecimal("10"), new BigDecimal("100"));
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.acceptInviteWithLock(1L, null));
        assertTrue(ex.getMessage().contains("分成比例"),
                "固定型携带分成比例必须 fail-closed 拒绝,实际:" + ex.getMessage());
        verify(coopOrderMapper, never()).insertCoopOrder(any());
    }

    /** 分成型却同时携带固定费:同上,反方向的条款污染。 */
    @Test
    void accept_topicLevel_rejectsShareModeWithFixedFee_failClosed() {
        CoopInvite target = topicLevelInvite(1, new BigDecimal("20"), new BigDecimal("100"));
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(new ArrayList<CoopInvite>());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.acceptInviteWithLock(1L, null));
        assertTrue(ex.getMessage().contains("固定费用"),
                "分成型携带固定费必须 fail-closed 拒绝,实际:" + ex.getMessage());
        verify(coopOrderMapper, never()).insertCoopOrder(any());
    }

    /** 负控的另一半:干净的固定型(正数固定费、无分成比例)必须放行,且不参与 90% 求和。 */
    @Test
    void accept_topicLevel_allowsCleanFixedMode() {
        CoopInvite target = topicLevelInvite(2, null, new BigDecimal("100"));
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        CoopInvite a1 = inv(101L, 1);
        a1.setToType("merchant"); a1.setShareMode(1); a1.setShareRate(new BigDecimal("85"));
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(Collections.singletonList(a1));

        svc.acceptInviteWithLock(1L, null);

        verify(coopOrderMapper, times(1)).insertCoopOrder(any());
    }

    /** 未锁价主题级邀约的公共 arrange:条款三字段外的部分各用例完全一致。 */
    private CoopInvite topicLevelInvite(Integer shareMode, BigDecimal shareRate, BigDecimal fixedFee) {
        CoopInvite target = inv(1L, 0);
        target.setTopicId(7L);
        target.setToType("merchant");
        target.setShareMode(shareMode);
        target.setShareRate(shareRate);
        target.setFixedFee(fixedFee);
        CmsTopic open = new CmsTopic();
        open.setId(7L);
        open.setDelFlag(0);
        open.setLifecycle(1);
        open.setRecruitDeadline(futureRecruitDeadline());
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(open);
        return target;
    }

    private Date futureRecruitDeadline() {
        return new Date(System.currentTimeMillis() + 60_000L);
    }

    /**
     * 历史 accepted 行的第二类低估:rate 有值但 mode 不是 1 ⇒ 该 rate 被静默排除在 90% 求和之外。
     * 本例里历史行 85% + 本次 20% = 105% 早已超编,不拦就会以「求和 20%」放行(纯资损)。
     */
    @Test
    void accept_topicLevel_rejectsAcceptedRateWithNonShareMode_failClosed() {
        CoopInvite target = topicLevelInvite(1, new BigDecimal("20"), null);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);

        CoopInvite dirty = inv(101L, 1);
        dirty.setToType("merchant");
        dirty.setShareMode(null);                  // 模式缺失
        dirty.setShareRate(new BigDecimal("85"));  // 却带着 85% 的分成比例
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(Collections.singletonList(dirty));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.acceptInviteWithLock(1L, null));
        assertTrue(ex.getMessage().contains("报酬模式不一致"),
                "历史行有 rate 但 mode!=1 必须拒绝,否则该 rate 被排除出求和,实际:" + ex.getMessage());
        verify(coopOrderMapper, never()).insertCoopOrder(any());
    }

    /**
     * 收窄的另一半(负控的反向证明):历史 accepted 行的**形状**问题不再锁死整个主题。
     * mode=2 却没填 fixed_fee 是脏数据,但它不参与分成求和,结算也只在 mode==2 时读 fixedFee ⇒
     * 拦它挡不住任何资损,只会让这条历史行把该主题所有后续 accept 永久堵死。必须放行。
     */
    @Test
    void accept_topicLevel_allowsWhenAcceptedRowHasOnlyShapeDirt() {
        CoopInvite target = topicLevelInvite(1, new BigDecimal("20"), null);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);

        CoopInvite shapeDirty = inv(101L, 1);
        shapeDirty.setToType("merchant");
        shapeDirty.setShareMode(2);   // 固定型
        shapeDirty.setFixedFee(null); // 却没填固定费:形状脏,但与分成求和无关
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(Collections.singletonList(shapeDirty));

        svc.acceptInviteWithLock(1L, null);

        verify(coopOrderMapper, times(1)).insertCoopOrder(any());
    }

    /**
     * fail-closed 之四:accepted 列表里的历史脏数据——share_mode=1 但 share_rate=null(建约早于本次
     * 校验上线,或写入路径本身有洞)。旧实现把它当 0 计入求和,会让"合计已超 90%"的真实情况被低估,
     * 新的邀约还能顺利叠加进去。这里必须 fail-closed 拒绝,而不是继续放行。
     */
    @Test
    void accept_topicLevel_rejectsWhenAcceptedListHasDirtyNullShareRate_failClosed() {
        CoopInvite target = inv(1L, 0);
        target.setTopicId(7L);
        target.setToType("merchant");
        target.setShareMode(1);
        target.setShareRate(new BigDecimal("20"));
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        com.chengyinhub.business.domain.CmsTopic open = new com.chengyinhub.business.domain.CmsTopic();
        open.setId(7L);
        open.setDelFlag(0);
        open.setLifecycle(1);
        open.setRecruitDeadline(futureRecruitDeadline());
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(open);

        CoopInvite dirty = inv(101L, 1);
        dirty.setToType("merchant");
        dirty.setShareMode(1);
        dirty.setShareRate(null); // 历史脏数据:分成型却无费率
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(Collections.singletonList(dirty));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.acceptInviteWithLock(1L, null));
        assertTrue(ex.getMessage().contains("分成"),
                "accepted 列表里分成型 share_rate=null 的脏数据必须 fail-closed 拒绝而非按0计入,实际:" + ex.getMessage());
        verify(coopOrderMapper, times(0)).insertCoopOrder(any());
    }

    /**
     * 并发合同钉子:topic 的 FOR UPDATE 行锁(selectCmsTopicByIdForUpdate)必须先于本方法内第一次
     * 「已接受」列表 SELECT(selectCoopInviteList)执行——顺序一旦被后续改动打乱,MySQL RR 下的并发
     * 接受会读到未被行锁串行化的旧快照,90% 分成闸在生产并发场景下形同虚设(单元测试打不出这个真实
     * 并发场景,只能钉住调用顺序这个必要条件)。
     * 这里记录真实调用序列而不用 Mockito 的 InOrder:InOrder 只要求"存在一次满足顺序的调用",
     * 行锁之前多插一次裸读它照样绿(实测过),那就是假绿——只有"第一次列表读必须晚于行锁"才是真断言。
     */
    @Test
    void accept_topicLevel_locksTopicBeforeReadingAcceptedList() {
        CoopInvite target = inv(1L, 0);
        target.setTopicId(7L);
        target.setToType("merchant");
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        final CmsTopic open = new CmsTopic();
        open.setId(7L);
        open.setDelFlag(0);
        open.setLifecycle(1);
        open.setRecruitDeadline(futureRecruitDeadline());
        final List<String> calls = new ArrayList<>();
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenAnswer(i -> {
            calls.add("lockTopic");
            return open;
        });
        when(coopInviteMapper.selectCoopInviteList(any())).thenAnswer(i -> {
            calls.add("readAccepted");
            return new ArrayList<CoopInvite>();
        });

        svc.acceptInviteWithLock(1L, null);

        assertEquals("lockTopic", calls.isEmpty() ? "(无调用)" : calls.get(0),
                "topic 行锁必须早于任何一次已接受列表读,不能在它之前插入不受行锁保护的裸读,实际调用序列:" + calls);
        assertTrue(calls.contains("readAccepted"),
                "本方法内应至少读一次已接受列表,实际调用序列:" + calls);
    }

    @Test
    void accept_gameLevel_rejectsWhenGameAlreadyHasAccepted() {
        CoopInvite target = inv(1L, 0);
        target.setGameId(5L);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        // 该游戏已有接受者
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(Collections.singletonList(inv(9L, 1)));
        assertThrows(IllegalStateException.class, () -> svc.acceptInviteWithLock(1L, null));
        verify(coopOrderMapper, times(0)).insertCoopOrder(any());
    }

    @Test
    void accept_topicLevel_rejectsWhenFull5() {
        CoopInvite target = inv(1L, 0);
        target.setTopicId(7L); // gameId null → 主题级
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(Arrays.asList(
                inv(2L, 1), inv(3L, 1), inv(4L, 1), inv(5L, 1), inv(6L, 1)));
        assertThrows(IllegalStateException.class, () -> svc.acceptInviteWithLock(1L, null));
    }

    @Test
    void accept_gameLevel_generatesOrderAndSupersedesPending() {
        CmsTopic originalTopic = new CmsTopic(); originalTopic.setId(100L); originalTopic.setDelFlag(0);
        originalTopic.setLifecycle(1); originalTopic.setRecruitDeadline(futureRecruitDeadline());
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(100L)).thenReturn(originalTopic);
        CoopInvite target = inv(1L, 0);
        target.setGameId(5L);
        target.setTopicId(100L);
        target.setFromId(10L);
        target.setToId(20L);
        target.setToType("merchant");
        target.setShareMode(1);
        target.setShareRate(new BigDecimal("30"));
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        when(coopInviteMapper.selectCoopInviteList(any())).thenAnswer(a -> {
            CoopInvite q = a.getArgument(0);
            if (q.getStatus() != null && q.getStatus() == 1) return new ArrayList<CoopInvite>(); // 容量:无接受者
            return Arrays.asList(inv(1L, 0), inv(2L, 0)); // 挂起:自己 + 另一家
        });

        svc.acceptInviteWithLock(1L, null);

        // 生成合作单,条款快照来自邀约
        ArgumentCaptor<CoopOrder> oc = ArgumentCaptor.forClass(CoopOrder.class);
        verify(coopOrderMapper).insertCoopOrder(oc.capture());
        assertEquals(Long.valueOf(1L), oc.getValue().getInviteId());
        assertEquals(Integer.valueOf(1), oc.getValue().getShareMode());
        assertEquals(0, new BigDecimal("30").compareTo(oc.getValue().getShareRate()));
        assertEquals("SUPPLY", oc.getValue().getRoleSnapshot(), "role 必须由服务端按本单受邀方重建");
        assertEquals("TOPIC", oc.getValue().getScopeTypeSnapshot(), "scope 类型必须冻结而非读取客户端字段");
        assertEquals("GUIDED_TICKET", oc.getValue().getEligiblePurchaseKindsSnapshot(),
                "经典定向合作单只能冻结 GUIDED_TICKET 选集");
        assertEquals("REGISTRATION_SCAN", oc.getValue().getFulfillmentEvidenceRuleSnapshot(),
                "商家合作单必须冻结逐报名证据规则");
        assertEquals("SALES_PERCENT", oc.getValue().getCompensationMode());
        assertEquals("NONE", oc.getValue().getBenefitMode(), "无权益快照不得被映射成 PERK");
        assertTrue(oc.getValue().getTermsSnapshot() != null && oc.getValue().getTermsSnapshot().contains("SALES_PERCENT"),
                "termsSnapshot 必须留下两轴审计证据");

        // updateCoopInvite:接受(id1 status1) + 顶替另一家(id2 status4),自己不被顶替
        ArgumentCaptor<CoopInvite> uc = ArgumentCaptor.forClass(CoopInvite.class);
        verify(coopInviteMapper, times(2)).updateCoopInvite(uc.capture());
        List<CoopInvite> ups = uc.getAllValues();
        CoopInvite accepted = ups.stream().filter(u -> u.getId().equals(1L)).findFirst().orElse(null);
        CoopInvite superseded = ups.stream().filter(u -> u.getId().equals(2L)).findFirst().orElse(null);
        assertNotNull(accepted);
        assertEquals(Integer.valueOf(1), accepted.getStatus());
        assertNull(accepted.getConfigDeadline(), "经典定向同意不写配置死线");
        assertNotNull(superseded);
        assertEquals(Integer.valueOf(4), superseded.getStatus());
    }

    @Test
    void accept_selfExploration_doesNotWriteConfigDeadline() {
        CmsTopic originalTopic = new CmsTopic(); originalTopic.setId(7L); originalTopic.setDelFlag(0);
        originalTopic.setLifecycle(1); originalTopic.setRecruitDeadline(futureRecruitDeadline());
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(originalTopic);
        // 阻断#8 / 台账 1-11:主题级邀约没有「配置游戏」步骤,接受时不得写 72h 死线
        CoopInvite target = inv(1L, 0);
        target.setTopicId(7L); // gameId null → 自由定向(主题级)
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(target);
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(new ArrayList<CoopInvite>()); // 未满员

        svc.acceptInviteWithLock(1L, null);

        ArgumentCaptor<CoopInvite> uc = ArgumentCaptor.forClass(CoopInvite.class);
        verify(coopInviteMapper).updateCoopInvite(uc.capture());
        assertEquals(Integer.valueOf(1), uc.getValue().getStatus());
        assertNull(uc.getValue().getConfigDeadline(), "主题级同意不得写 config_deadline");
        verify(coopOrderMapper).insertCoopOrder(any());
    }

    @Test
    void createCoopOrder_reusesExistingOrderForSameReg_noDoubleInsert() {
        // A-13 幂等:同一报名(reg_id)已建过合作单则复用旧单,不重复插入(库 reg_id 无 UNIQUE 前的兜底)
        CoopOrder existing = new CoopOrder();
        existing.setId(777L);
        existing.setRegId(55L);
        CoopInvite original = inv(88L, 1);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(88L)).thenReturn(original);
        existing.setInviteId(88L); existing.setFromId(100L); existing.setToId(200L); existing.setToType("merchant");
        when(coopInviteMapper.selectOriginalCoopOrdersForUpdate(88L, 55L))
                .thenReturn(java.util.Collections.singletonList(existing));

        CoopOrder order = new CoopOrder();
        order.setInviteId(88L); order.setFromId(100L); order.setToId(200L); order.setToType("merchant");
        order.setRegId(55L);
        Long id = svc.createCoopOrder(order);

        assertEquals(777L, id.longValue());
        verify(coopOrderMapper, times(0)).insertCoopOrder(any());
    }

    @Test
    void createCoopOrder_rebuildsServerOwnedContractFields() {
        CoopInvite invite = new CoopInvite();
        invite.setId(88L); invite.setStatus(1); invite.setDelFlag(0);
        invite.setInviteType(1);
        invite.setToType("club");
        invite.setTopicId(7L);
        invite.setDepositAmount(new BigDecimal("50"));
        invite.setShareMode(2);
        invite.setFixedFee(new BigDecimal("12"));
        when(coopInviteMapper.selectCoopInviteById(88L)).thenReturn(invite);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(88L)).thenReturn(invite);
        CmsTopic originalTopic = new CmsTopic(); originalTopic.setId(7L); originalTopic.setDelFlag(0);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(originalTopic);

        CoopOrder order = new CoopOrder();
        order.setInviteId(88L); order.setTopicId(7L); order.setToType("club");
        order.setRoleSnapshot("FORGED");
        order.setScopeTypeSnapshot("FORGED");
        order.setScopeIdSnapshot(999L);
        order.setEligiblePurchaseKindsSnapshot("FORGED");
        order.setFulfillmentEvidenceRuleSnapshot("FORGED");
        order.setDepositAmount(new BigDecimal("1"));
        order.setShareMode(1);
        order.setShareRate(new BigDecimal("99"));
        order.setTermsSnapshot("{\"forged\":true}");

        svc.createCoopOrder(order);

        assertEquals("ORGANIZER", order.getRoleSnapshot(), "role 必须由服务端按邀约重建");
        assertEquals("TOPIC", order.getScopeTypeSnapshot(), "scope 类型必须由服务端按邀约重建");
        assertEquals(Long.valueOf(7L), order.getScopeIdSnapshot(), "scope id 必须由服务端按邀约重建");
        assertEquals("GUIDED_TICKET", order.getEligiblePurchaseKindsSnapshot(), "purchase kind 必须由服务端按邀约重建");
        assertEquals("COOP_GROUP_VERIFY", order.getFulfillmentEvidenceRuleSnapshot(), "证据规则必须由服务端按邀约重建");
        assertEquals(new BigDecimal("50"), order.getDepositAmount(), "保证金快照必须来自邀约服务端值");
        assertEquals(Integer.valueOf(2), order.getShareMode(), "报酬模式必须来自服务端邀约快照");
        assertEquals(new BigDecimal("12"), order.getFixedFee(), "固定报酬必须来自服务端邀约快照");
        assertTrue(order.getTermsSnapshot().contains("FIXED_PER_FULFILLMENT"), "条款快照必须由服务端重建");
    }

    @Test
    void createCoopOrder_rejectsLegacyType2BeforeSnapshotOrInsert() {
        CoopInvite legacy = inv(88L, 1);
        legacy.setInviteType(CoopInvite.TYPE_LEGACY_MERCHANT_NODE);
        when(coopInviteMapper.selectCoopInviteById(88L)).thenReturn(legacy);
        CoopOrder order = new CoopOrder();
        order.setInviteId(88L);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.createCoopOrder(order));

        assertTrue(error.getMessage().contains("仅供查看"));
        verify(coopOrderMapper, never()).insertCoopOrder(any());
        assertNull(order.getTermsSnapshot());
    }

    // ---- cancelAcceptedInvite ----

    @Test
    void cancelAccepted_writesCreditLogAndCancels() {
        CoopInvite accepted = inv(1L, 1);
        accepted.setFromId(99L);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(accepted);
        when(coopInviteMapper.selectCoopInviteById(1L)).thenReturn(accepted);

        svc.cancelAcceptedInvite(1L, 99L, "行程冲突", 2);

        ArgumentCaptor<CoopInvite> uc = ArgumentCaptor.forClass(CoopInvite.class);
        verify(coopInviteMapper).updateCoopInvite(uc.capture());
        assertEquals(Integer.valueOf(3), uc.getValue().getStatus());

        ArgumentCaptor<CoopCreditLog> lc = ArgumentCaptor.forClass(CoopCreditLog.class);
        verify(coopCreditLogMapper).insertCoopCreditLog(lc.capture());
        assertEquals(Long.valueOf(99L), lc.getValue().getMemberId());
        assertEquals(Integer.valueOf(2), lc.getValue().getEventType());
        assertEquals("行程冲突", lc.getValue().getReason());
    }

    @Test
    void cancelAccepted_rejectsDisabledType0MerchantOperatorBeforeVoidingOrder() {
        CoopInvite accepted = inv(1L, 1);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(accepted);
        when(coopInviteMapper.selectCoopInviteById(1L)).thenReturn(accepted);
        when(mmsMerchantMapper.selectMmsMerchantByMemberIdForUpdate(200L)).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.cancelAcceptedInvite(1L, 200L, "停止合作", 2));

        assertTrue(error.getMessage().contains("审核通过且启用"));
        verify(coopInviteMapper, never()).updateCoopInvite(any());
        verify(coopCreditLogMapper, never()).insertCoopCreditLog(any());
        verify(coopOrderMapper, never()).voidOrderByInviteId(any());
    }

    @Test
    void cancelAccepted_rejectsDisabledType1MerchantOperatorBeforeVoidingOrder() {
        CoopInvite accepted = clubInvite(1L, 1, 8001L, 100L, 0);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(accepted);
        when(coopInviteMapper.selectCoopInviteById(1L)).thenReturn(accepted);
        when(mmsMerchantMapper.selectMmsMerchantByMemberIdForUpdate(300L)).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.cancelAcceptedInvite(1L, 300L, "停止合作", 2));

        assertTrue(error.getMessage().contains("审核通过且启用"));
        verify(coopInviteMapper, never()).updateCoopInvite(any());
        verify(coopCreditLogMapper, never()).insertCoopCreditLog(any());
        verify(coopOrderMapper, never()).voidOrderByInviteId(any());
    }

    @Test
    void cancelAccepted_allowsClubOwnerWhenInitiatingMerchantIsDisabled() {
        CoopInvite accepted = clubInvite(1L, 1, 8001L, 100L, 0);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(accepted);
        when(coopInviteMapper.selectCoopInviteById(1L)).thenReturn(accepted);
        when(mmsMerchantMapper.selectMmsMerchantByMemberIdForUpdate(300L)).thenReturn(null);

        svc.cancelAcceptedInvite(1L, 100L, "俱乐部退出", 2);

        verify(coopInviteMapper).updateCoopInvite(org.mockito.ArgumentMatchers.argThat(update ->
                Integer.valueOf(3).equals(update.getStatus())));
        verify(coopOrderMapper).voidOrderByInviteId(1L);
        verify(mmsMerchantMapper, never()).selectMmsMerchantByMemberIdForUpdate(any());
    }

    @Test
    void refundRetryAuthorizationRejectsDisabledMerchantBeforeRefundCanStart() {
        CoopInvite cancelled = inv(1L, 3);
        cancelled.setDepositStatus(2);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(cancelled);
        when(coopInviteMapper.selectCoopInviteById(1L)).thenReturn(cancelled);
        MmsMerchant disabled = new MmsMerchant();
        disabled.setMemberId(200L);
        disabled.setStatus(1L);
        disabled.setAccountStatus(2L);
        disabled.setDelFlag(0);
        when(mmsMerchantMapper.selectMmsMerchantByMemberIdForUpdate(200L)).thenReturn(disabled);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.authorizeDepositRefundRetry(1L, 200L));

        assertTrue(error.getMessage().contains("审核通过且启用"));
    }

    @Test
    void cancelAccepted_rejectsWhenNotAccepted() {
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(inv(1L, 0));
        assertThrows(IllegalStateException.class, () -> svc.cancelAcceptedInvite(1L, 99L, "x", 2));
    }

    @Test
    void cancelAccepted_legacyType2_isReadOnly() {
        CoopInvite legacy = inv(1L, 1);
        legacy.setInviteType(CoopInvite.TYPE_LEGACY_MERCHANT_NODE);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(legacy);
        when(coopInviteMapper.selectCoopInviteById(1L)).thenReturn(legacy);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.cancelAcceptedInvite(1L, 99L, "x", 2));

        assertTrue(error.getMessage().contains("仅供查看"));
        verify(coopInviteMapper, never()).updateCoopInvite(any());
        verify(coopCreditLogMapper, never()).insertCoopCreditLog(any());
        verify(coopOrderMapper, never()).voidOrderByInviteId(any());
    }

    @Test
    void cancelAccepted_rejectsAfterPriceLocked() {
        CoopInvite accepted = inv(1L, 1);
        accepted.setFromId(99L);
        accepted.setTopicId(7L);
        CmsTopic selling = new CmsTopic();
        selling.setId(7L); selling.setLifecycle(3);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(accepted);
        when(coopInviteMapper.selectCoopInviteById(1L)).thenReturn(accepted);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(selling);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.cancelAcceptedInvite(1L, 99L, "x", 2));

        assertTrue(ex.getMessage().contains("阵容已锁定"));
        verify(coopOrderMapper, times(0)).voidOrderByInviteId(any());
    }

    // ---- expireStaleInvites (双死线①) ----

    @Test
    void expireStale_onlyExpiresPendingInvites() {
        when(coopInviteMapper.expirePendingInvites(any())).thenReturn(2);
        assertEquals(2, svc.expireStaleInvites());
    }

    // ---- creditSummary (B4) ----

    @Test
    void creditSummary_computesFulfillmentRate() {
        CoopCreditLog fulfil = new CoopCreditLog(); fulfil.setEventType(0);
        CoopCreditLog cancel = new CoopCreditLog(); cancel.setEventType(2);
        when(coopCreditLogMapper.selectRecentByMember(any(), any())).thenReturn(Arrays.asList(fulfil, cancel));

        Map<String, Object> m = svc.creditSummary(10L);
        assertEquals(1, m.get("fulfil"));
        assertEquals(1, m.get("violationCount"));
        assertEquals(50, m.get("fulfillmentRate"));
    }

    @Test
    void creditSummary_noRecordsDefaults100() {
        when(coopCreditLogMapper.selectRecentByMember(any(), any())).thenReturn(Collections.emptyList());
        Map<String, Object> m = svc.creditSummary(10L);
        assertEquals(100, m.get("fulfillmentRate"));
        assertEquals(0, m.get("violationCount"));
    }

    // —— B4-3 信用→槽位配额动态化 ——
    private CoopCreditLog creditLog(int eventType) {
        CoopCreditLog l = new CoopCreditLog();
        l.setEventType(eventType);
        return l;
    }

    @Test
    void dynamicTopicSlotCap_sampleTooSmall_default5() {
        // 0 记录 → 样本不足 → 默认 5
        when(coopCreditLogMapper.selectRecentByMember(any(), any())).thenReturn(Collections.emptyList());
        assertEquals(5, svc.dynamicTopicSlotCap(1L));
        // 2 条也算样本不足
        when(coopCreditLogMapper.selectRecentByMember(any(), any()))
                .thenReturn(Arrays.asList(creditLog(0), creditLog(0)));
        assertEquals(5, svc.dynamicTopicSlotCap(1L));
    }

    @Test
    void dynamicTopicSlotCap_excellent_8() {
        // 5 履约 0 违约 → 履约率100 → 8
        when(coopCreditLogMapper.selectRecentByMember(any(), any()))
                .thenReturn(Arrays.asList(creditLog(0), creditLog(0), creditLog(0), creditLog(0), creditLog(0)));
        assertEquals(8, svc.dynamicTopicSlotCap(1L));
    }

    @Test
    void dynamicTopicSlotCap_poor_2() {
        // 3 履约 + 3 违约 → 履约率50 & 违约≥3 → 2
        when(coopCreditLogMapper.selectRecentByMember(any(), any()))
                .thenReturn(Arrays.asList(creditLog(0), creditLog(0), creditLog(0),
                        creditLog(2), creditLog(2), creditLog(1)));
        assertEquals(2, svc.dynamicTopicSlotCap(1L));
    }

    @Test
    void dynamicTopicSlotCap_average_5() {
        // 3 履约 + 1 违约 → 履约率75 & 违约<3 → 一般 → 5
        when(coopCreditLogMapper.selectRecentByMember(any(), any()))
                .thenReturn(Arrays.asList(creditLog(0), creditLog(0), creditLog(0), creditLog(2)));
        assertEquals(5, svc.dynamicTopicSlotCap(1L));
    }

    @Test
    void dynamicTopicSlotCap_nullDefaults5() {
        assertEquals(5, svc.dynamicTopicSlotCap(null));
    }

    // —— 保证金状态机 ——

    @Test
    void depositOwedParty_onlySupportsActiveInviteTypes() {
        CoopInvite type0 = inv(1L, 1);
        type0.setInviteType(0); type0.setToType("merchant"); type0.setToId(200L);
        assertEquals(Long.valueOf(200L), svc.depositOwedParty(type0));

        CoopInvite type1 = clubInvite(2L, 1, 8001L, 100L, 5);
        type1.setFromId(300L);
        assertEquals(Long.valueOf(100L), svc.depositOwedParty(type1));

        CoopInvite type1FromApply = clubInvite(3L, 1, 8002L, 101L, 5);
        type1FromApply.setFromId(301L); type1FromApply.setOriginApplyId(77L);
        assertEquals(Long.valueOf(301L), svc.depositOwedParty(type1FromApply));

        CoopInvite clubPublisherFromApply = clubInvite(5L, 1, 8003L, 102L, 5);
        clubPublisherFromApply.setFromType("club");
        clubPublisherFromApply.setFromId(7001L);
        clubPublisherFromApply.setOriginApplyId(78L);
        com.chengyinhub.business.domain.Club publisher = new com.chengyinhub.business.domain.Club();
        publisher.setId(7001L);
        publisher.setMemberId(9003L);
        when(clubMapper.selectClubById(7001L)).thenReturn(publisher);
        assertEquals(Long.valueOf(9003L), svc.depositOwedParty(clubPublisherFromApply),
                "俱乐部发布方应缴时必须返回主理人 memberId，不能把 club.id 当 memberId");

        CoopInvite type2 = inv(4L, 1);
        type2.setInviteType(CoopInvite.TYPE_LEGACY_MERCHANT_NODE); type2.setToType("merchant"); type2.setToId(400L);
        assertNull(svc.depositOwedParty(type2), "历史 type2 不得再产生保证金应缴方");
    }

    @Test
    void insertInvite_rejectsForgedOriginApplyBeforePersistence() {
        CoopInvite invite = clubInvite(1L, 0, 8001L, 100L, 0);
        invite.setTopicId(7L); invite.setOriginApplyId(77L);
        CoopClubApply mismatched = new CoopClubApply();
        mismatched.setId(77L); mismatched.setTopicId(7L); mismatched.setClubId(8002L); mismatched.setStatus(0);
        when(coopClubApplyService.selectById(77L)).thenReturn(mismatched);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> svc.insertCoopInvite(invite));

        assertTrue(ex.getMessage().contains("溯源不匹配"));
        verify(coopInviteMapper, times(0)).insertCoopInvite(any());
    }

    @Test
    void markTopicDepositsDue_marksUnpaidAndDoesNotBlockOnNotifyFailure() {
        CmsTopic topic = new CmsTopic();
        topic.setId(7L);
        topic.setDelFlag(0); topic.setName("测试主题");
        CoopInvite due = inv(1L, 1);
        due.setTopicId(7L); due.setInviteType(0); due.setToType("merchant"); due.setToId(200L); due.setDepositStatus(0);
        CoopInvite frozen = inv(2L, 1);
        frozen.setTopicId(7L); frozen.setInviteType(0); frozen.setToType("merchant"); frozen.setToId(201L); frozen.setDepositStatus(2);
        when(cmsTopicMapper.selectCmsTopicById(7L)).thenReturn(topic);
        when(coopInviteMapper.selectAcceptedByTopicForUpdate(7L)).thenReturn(Arrays.asList(due, frozen));
        org.mockito.Mockito.doThrow(new RuntimeException("模板未配置"))
                .when(notifyService).notifyCoopStatus(any(), any(), any(), any(), any());

        when(coopInviteMapper.updateDepositIfCurrent(eq(1L), eq(0), any(), eq(5), any())).thenReturn(1);
        svc.markTopicDepositsDue(7L);

        verify(coopInviteMapper).updateDepositIfCurrent(eq(1L), eq(0), any(), eq(5), eq(ICoopInviteService.DEPOSIT_AMOUNT));
        verify(coopInviteMapper, times(0)).updateDepositIfCurrent(eq(2L), any(), any(), any(), any());
    }

    @Test
    void markTopicDepositsDue_sendsNoticeOnlyAfterCommit() {
        CmsTopic topic = new CmsTopic();
        topic.setId(7L);
        topic.setDelFlag(0); topic.setName("测试主题");
        CoopInvite due = inv(1L, 1);
        due.setTopicId(7L); due.setInviteType(0); due.setToType("merchant"); due.setToId(200L); due.setDepositStatus(0);
        when(cmsTopicMapper.selectCmsTopicById(7L)).thenReturn(topic);
        when(coopInviteMapper.selectAcceptedByTopicForUpdate(7L)).thenReturn(Collections.singletonList(due));

        TransactionSynchronizationManager.initSynchronization();
        try {
            when(coopInviteMapper.updateDepositIfCurrent(eq(1L), eq(0), any(), eq(5), any())).thenReturn(1);
            svc.markTopicDepositsDue(7L);

            verify(coopInviteMapper).updateDepositIfCurrent(eq(1L), eq(0), any(), eq(5), eq(ICoopInviteService.DEPOSIT_AMOUNT));
            verify(notifyService, never()).notifyCoopStatus(any(), any(), any(), any(), any());
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            verify(notifyService).notifyCoopStatus(eq(200L), eq("测试主题"), eq("系统"),
                    eq("待缴保证金"), eq("主题已锁价开卖，请缴纳¥50保证金"));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void markTopicDepositsDue_skipsLegacyType2WithoutAnyDml() {
        CoopInvite legacy = inv(1L, 1);
        legacy.setInviteType(CoopInvite.TYPE_LEGACY_MERCHANT_NODE);
        legacy.setDepositStatus(0);
        when(coopInviteMapper.selectAcceptedByTopicForUpdate(7L)).thenReturn(Collections.singletonList(legacy));

        svc.markTopicDepositsDue(7L);

        verify(coopInviteMapper, never()).updateDepositIfCurrent(any(), any(), any(), any(), any());
        verify(notifyService, never()).notifyCoopStatus(any(), any(), any(), any(), any());
    }

    @Test
    void prepareDeposit_notInvitee_rejected() {
        CoopInvite c = clubInvite(1L, 1, 8001L, 100L, 5); // 主理人=100
        c.setTopicId(7L);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(c);
        assertThrows(IllegalStateException.class, () -> svc.prepareDeposit(1L, 999L)); // 非主理人
    }

    @Test
    void prepareDeposit_rejectsDisabledMerchantPayerBeforeCreatingPaymentOrder() {
        CoopInvite merchantInvite = inv(1L, 1);
        merchantInvite.setDepositStatus(5);
        merchantInvite.setTopicId(7L);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(merchantInvite);
        MmsMerchant disabled = new MmsMerchant();
        disabled.setMemberId(200L);
        disabled.setStatus(1L);
        disabled.setAccountStatus(2L);
        disabled.setDelFlag(0);
        when(mmsMerchantMapper.selectMmsMerchantByMemberIdForUpdate(200L)).thenReturn(disabled);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.prepareDeposit(1L, 200L));

        assertTrue(ex.getMessage().contains("审核通过且启用"));
        verify(coopInviteMapper, never()).updateDeposit(any(), any(), any(), any(), any());
    }

    @Test
    void prepareDeposit_rejectsMalformedAcceptedType0MatrixBeforePaymentMutation() {
        CoopInvite malformed = inv(1L, 1);
        malformed.setToType("club");
        malformed.setToId(8001L);
        malformed.setDepositStatus(5);
        malformed.setTopicId(7L);
        com.chengyinhub.business.domain.Club club = new com.chengyinhub.business.domain.Club();
        club.setId(8001L);
        club.setMemberId(100L);
        when(clubMapper.selectClubById(8001L)).thenReturn(club);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(malformed);
        CmsTopic selling = new CmsTopic();
        selling.setLifecycle(3);
        when(cmsTopicMapper.selectCmsTopicById(7L)).thenReturn(selling);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.prepareDeposit(1L, 100L));

        assertTrue(error.getMessage().contains("邀请类型"));
        verify(coopInviteMapper, never()).updateDeposit(any(), any(), any(), any(), any());
    }

    @Test
    void prepareDeposit_rejectsMalformedAcceptedType1MatrixBeforePaymentMutation() {
        CoopInvite malformed = clubInvite(1L, 1, 8001L, 100L, 5);
        malformed.setFromType("talent");
        malformed.setTopicId(7L);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(malformed);
        CmsTopic selling = new CmsTopic();
        selling.setLifecycle(3);
        when(cmsTopicMapper.selectCmsTopicById(7L)).thenReturn(selling);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.prepareDeposit(1L, 100L));

        assertTrue(error.getMessage().contains("邀请类型"));
        verify(coopInviteMapper, never()).updateDeposit(any(), any(), any(), any(), any());
    }

    @Test
    void prepareDeposit_legacyType2_isReadOnly() {
        CoopInvite legacy = inv(1L, 1);
        legacy.setInviteType(CoopInvite.TYPE_LEGACY_MERCHANT_NODE);
        legacy.setToType("merchant");
        legacy.setToId(100L);
        legacy.setDepositStatus(5);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(legacy);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.prepareDeposit(1L, 100L));

        assertTrue(error.getMessage().contains("仅供查看"));
        verify(coopInviteMapper, never()).updateDeposit(any(), any(), any(), any(), any());
    }

    @Test
    void prepareDeposit_happy_setsPendingAndReturnsDepOrderNo() {
        CoopInvite c = clubInvite(1L, 1, 8001L, 100L, 5);
        c.setTopicId(7L);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(c);
        com.chengyinhub.business.domain.CmsTopic selling = new com.chengyinhub.business.domain.CmsTopic();
        selling.setLifecycle(3);
        when(cmsTopicMapper.selectCmsTopicById(7L)).thenReturn(selling);
        when(coopInviteMapper.updateDeposit(eq(1L), eq(1), any(), any(), any())).thenReturn(1);
        Map<String, Object> r = svc.prepareDeposit(1L, 100L);
        assertNotNull(r.get("outTradeNo"));
        assertTrue(((String) r.get("outTradeNo")).startsWith("DEP"));
        assertEquals(0, ((java.math.BigDecimal) r.get("amount")).compareTo(new java.math.BigDecimal("50.00")));
        // 置待缴(status=1)
        verify(coopInviteMapper).updateDeposit(eq(1L), eq(1), any(), any(), any());
    }

    @Test
    void prepareDeposit_beforePriceLock_rejected() {
        CoopInvite c = clubInvite(1L, 1, 8001L, 100L, 5);
        c.setTopicId(7L);
        CmsTopic pricing = new CmsTopic();
        pricing.setLifecycle(2);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(c);
        when(cmsTopicMapper.selectCmsTopicById(7L)).thenReturn(pricing);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.prepareDeposit(1L, 100L));

        assertTrue(ex.getMessage().contains("锁价开卖后"));
        verify(coopInviteMapper, times(0)).updateDeposit(any(), any(), any(), any(), any());
    }

    @Test
    void prepareDeposit_pendingReviewAndUnknownOrdinalAreRejected() {
        CoopInvite c = clubInvite(1L, 1, 8001L, 100L, 5);
        c.setTopicId(7L);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(c);
        for (Integer lifecycle : java.util.Arrays.asList(TopicLifecycleState.PENDING_REVIEW.getCode(), 99)) {
            CmsTopic topic = new CmsTopic();
            topic.setLifecycle(lifecycle);
            when(cmsTopicMapper.selectCmsTopicById(7L)).thenReturn(topic);
            assertThrows(IllegalStateException.class, () -> svc.prepareDeposit(1L, 100L));
        }
        verify(coopInviteMapper, never()).updateDeposit(any(), any(), any(), any(), any());
    }

    @Test
    void prepareDeposit_reusesPendingOrderAfterUserCancelsPaymentSheet() {
        CoopInvite c = clubInvite(1L, 1, 8001L, 100L, 1);
        c.setTopicId(7L); c.setDepositOutTradeNo("DEP1T100");
        CmsTopic selling = new CmsTopic();
        selling.setLifecycle(3);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(c);
        when(cmsTopicMapper.selectCmsTopicById(7L)).thenReturn(selling);

        Map<String, Object> r = svc.prepareDeposit(1L, 100L);

        assertEquals("DEP1T100", r.get("outTradeNo"));
        verify(coopInviteMapper, times(0)).updateDeposit(any(), any(), any(), any(), any());
    }

    @Test
    void prepareDeposit_retryWithoutConfirmedCloseLeavesOriginalIdentityAndDoesNotCas() {
        CoopInvite invite = originalDepositRetry(false);
        IllegalStateException error = assertThrows(IllegalStateException.class, this::prepareOriginalDepositRetry);
        assertTrue(error.getMessage().contains("原保证金支付身份"));
        assertOriginalDepositRetryUnchanged(invite);
        verify(verifiedPaymentFacts).hasConfirmedExternalSuccess("DEP1T100");
        verify(paymentIntentJdbc).queryForList(
                org.mockito.ArgumentMatchers.startsWith("SELECT * FROM wx_pay_order_intent"), eq("DEP"), eq(1L));
        verify(coopInviteMapper, never()).updateDeposit(any(), any(), any(), any(), any());
    }

    @Test
    void prepareDeposit_confirmedSuccessEvenWithClosedAttemptCannotCreateNewPendingCas() {
        CoopInvite invite = originalDepositRetry(true);
        when(verifiedPaymentFacts.hasConfirmedExternalSuccess("DEP1T100")).thenReturn(true);
        IllegalStateException error = assertThrows(IllegalStateException.class, this::prepareOriginalDepositRetry);
        assertTrue(error.getMessage().contains("原保证金支付身份"));
        assertOriginalDepositRetryUnchanged(invite);
        verify(paymentIntentJdbc, never()).queryForList(
                org.mockito.ArgumentMatchers.startsWith("SELECT * FROM wx_pay_order_intent"), eq("DEP"), eq(1L));
        verify(coopInviteMapper, never()).updateDeposit(any(), any(), any(), any(), any());
    }

    @Test
    void prepareDeposit_confirmedClosedRetryReusesOriginalBusinessNoAfterLockedProof() {
        originalDepositRetry(true);
        when(coopInviteMapper.updateDeposit(eq(1L), eq(1), eq("DEP1T100"),
                org.mockito.ArgumentMatchers.isNull(), eq(new BigDecimal("50.00")))).thenReturn(1);
        Map<String, Object> prepared = prepareOriginalDepositRetry();
        assertEquals("DEP1T100", prepared.get("outTradeNo"));
        assertEquals(new BigDecimal("50.00"), prepared.get("amount"));
        assertEquals(100L, prepared.get("payerMemberId"));
        var order = org.mockito.Mockito.inOrder(paymentIntentJdbc, verifiedPaymentFacts, coopInviteMapper);
        order.verify(paymentIntentJdbc).queryForList(
                org.mockito.ArgumentMatchers.startsWith("SELECT id,deposit_amount"), eq("DEP1T100"));
        order.verify(verifiedPaymentFacts).hasConfirmedExternalSuccess("DEP1T100");
        order.verify(paymentIntentJdbc).queryForList(
                org.mockito.ArgumentMatchers.startsWith("SELECT * FROM wx_pay_order_intent"), eq("DEP"), eq(1L));
        order.verify(coopInviteMapper).updateDeposit(1L, 1, "DEP1T100", null, new BigDecimal("50.00"));
        verify(coopInviteMapper, times(1)).updateDeposit(any(), any(), any(), any(), any());
    }

    private CoopInvite originalDepositRetry(boolean closed) {
        CoopInvite invite = clubInvite(1L, 1, 8001L, 100L, 5);
        invite.setTopicId(7L); invite.setDepositAmount(new BigDecimal("50.00"));
        invite.setDepositOutTradeNo("DEP1T100");
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(invite);
        CmsTopic topic = new CmsTopic(); topic.setId(7L);
        topic.setDelFlag(0); topic.setLifecycle(3);
        when(cmsTopicMapper.selectCmsTopicById(7L)).thenReturn(topic);
        when(paymentIntentJdbc.queryForList(org.mockito.ArgumentMatchers.startsWith("SELECT id,deposit_amount"), eq("DEP1T100")))
                .thenReturn(List.of(Map.of("id", 1L, "amount", new BigDecimal("50.00"), "payable", 0)));
        Map<String, Object> attempt = new java.util.HashMap<>();
        attempt.put("business_out_trade_no", "DEP1T100"); attempt.put("biz_type", "DEP"); attempt.put("biz_id", 1L);
        attempt.put("mchid", "merchant"); attempt.put("out_trade_no", "WXP_ORIGINAL_ALIAS");
        attempt.put("total_fen", 5000L); attempt.put("currency", "CNY"); attempt.put("trade_type", "JSAPI");
        attempt.put("appid", "wx-original"); attempt.put("request_hash", "a".repeat(64));
        attempt.put("closed_confirmed_at", closed ? new java.sql.Timestamp(1700000000000L) : null);
        when(paymentIntentJdbc.queryForList(org.mockito.ArgumentMatchers.startsWith("SELECT * FROM wx_pay_order_intent"), eq("DEP"), eq(1L)))
                .thenReturn(List.of(attempt));
        return invite;
    }

    /** Unit transaction flag only; this does not execute/prove a database transaction or row lock. */
    private Map<String, Object> prepareOriginalDepositRetry() {
        boolean originalTransaction = TransactionSynchronizationManager.isActualTransactionActive();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { return svc.prepareDeposit(1L, 100L); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(originalTransaction); }
    }

    private void assertOriginalDepositRetryUnchanged(CoopInvite invite) {
        assertEquals(5, invite.getDepositStatus());
        assertEquals("DEP1T100", invite.getDepositOutTradeNo());
        assertEquals(new BigDecimal("50.00"), invite.getDepositAmount());
        assertNull(invite.getDepositPaidTransactionId());
    }

    @Test
    void historicalQueryDoesNotDisguiseDatabaseErrorsAsIdentityUpdates() {
        CoopInvite invite=inv(1L,3);invite.setTopicId(7L);invite.setDepositStatus(4);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(invite);
        CmsTopic topic=new CmsTopic();topic.setId(7L);
        topic.setDelFlag(0);topic.setClubId(73L);
        when(cmsTopicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(topic);
        org.springframework.jdbc.UncategorizedSQLException failure=new org.springframework.jdbc.UncategorizedSQLException(
                "test","select",new java.sql.SQLException("broken SQL","42000",1064));
        when(coopInviteMapper.selectClubOwnerForDepositQueryNowait(73L)).thenThrow(failure);
        assertThrows(org.springframework.dao.DataAccessException.class,()->svc.authorizeDepositRefundRetry(1L,100L));
    }

    @Test
    void markDepositPaid_setsFrozen() {
        CoopInvite c = clubInvite(1L, 0, 8001L, 100L, 1);
        c.setDepositAmount(new BigDecimal("50.00"));
        when(coopInviteMapper.selectByDepositOutTradeNoForUpdate("DEP1")).thenReturn(c);
        c.setDepositOutTradeNo("DEP1");
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(c);
        when(coopInviteMapper.markDepositPaidConfirmed(eq(1L), eq("DEP1"), any(), eq("wx1"))).thenAnswer(call -> {
            c.setDepositStatus(2);c.setDepositPaidTransactionId("wx1");return 1;
        });
        var reference = originalDepositProof("DEP1",5000L,"wx1");
        assertTrue(consumeDeposit("DEP1",5000L,"wx1",reference));
        verify(coopInviteMapper).markDepositPaidConfirmed(eq(1L), eq("DEP1"), any(), eq("wx1")); // 2已缴冻结
    }

    @Test
    void markDepositPaid_unknownOrder_false() {
        when(coopInviteMapper.selectByDepositOutTradeNoForUpdate("DEPX")).thenReturn(null);
        assertEquals(false, consumeDeposit("DEPX",5000L,"wx1",originalDepositProof("DEPX",5000L,"wx1")));
    }

    @Test
    void depositStateChanges_ignoreLegacyType2() {
        CoopInvite legacy = inv(1L, 1);
        legacy.setInviteType(CoopInvite.TYPE_LEGACY_MERCHANT_NODE);
        legacy.setDepositStatus(2);
        when(coopInviteMapper.selectByDepositOutTradeNoForUpdate("DEP2")).thenReturn(legacy);
        when(coopInviteMapper.selectCoopInviteById(1L)).thenReturn(legacy);

        assertEquals(false, consumeDeposit("DEP2",5000L,"wx1",originalDepositProof("DEP2",5000L,"wx1")));
        assertEquals(0, svc.deductDeposit(1L));
        assertEquals(0, svc.settleDepositRefunded(1L, "REF2"));

        verify(coopInviteMapper, never()).updateDeposit(any(), any(), any(), any(), any());
    }

    @Test
    void unverifiedThreeArgumentWriteIsNotAPublicInterfaceOrServiceEntry() throws Exception {
        assertThrows(NoSuchMethodException.class,()->ICoopInviteService.class.getMethod("markDepositPaid",String.class,Long.class,String.class));
        assertThrows(NoSuchMethodException.class,()->CoopInviteServiceImpl.class.getMethod("markDepositPaid",String.class,Long.class,String.class));
        var apply=CoopInviteServiceImpl.class.getDeclaredMethod("applyConfirmedDepositPaid",String.class,Long.class,String.class);
        assertTrue(java.lang.reflect.Modifier.isPrivate(apply.getModifiers()));
    }

    @Test
    void missingOrMismatchedOriginalReferenceCannotReachThePrivatePaidCas() {
        originalDepositProof("DEP1",5000L,"wx1");
        var wrong=new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(999L,"b".repeat(64));
        when(verifiedPaymentFacts.requireConsumablePayment(null)).thenThrow(new IllegalArgumentException("Original reference required"));
        when(verifiedPaymentFacts.requireConsumablePayment(wrong)).thenThrow(new com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService.ConflictException());
        assertThrows(IllegalArgumentException.class,()->consumeDeposit("DEP1",5000L,"wx1",null));
        assertThrows(IllegalStateException.class,()->consumeDeposit("DEP1",5000L,"wx1",wrong));
        verify(coopInviteMapper,never()).selectByDepositOutTradeNoForUpdate(any());
        verify(coopInviteMapper,never()).markDepositPaidConfirmed(any(),any(),any(),any());
    }

    private com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference originalDepositProof(String no,long amountFen,String tx) {
        var payment = new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact(
                com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Kind.PAYMENT,"merchant","wx-original",
                no,no,no,tx,tx,"JSAPI","CNY",amountFen,amountFen,java.time.Instant.ofEpochSecond(1700000000),null,"SUCCESS");
        var reference = new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(7L,payment.factHash());
        when(verifiedPaymentFacts.requireVerified(reference)).thenReturn(
                new com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService.Stored(reference,payment));
        when(verifiedPaymentFacts.findPaymentRecovery(reference)).thenReturn(java.util.Optional.empty());
        when(verifiedPaymentFacts.requireConsumablePayment(reference)).thenReturn(
                new com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService.Stored(reference,payment));
        ReflectionTestUtils.setField(svc,"depositVerifiedFacts",verifiedPaymentFacts);return reference;
    }

    /** Unit lifecycle flag only, not a database transaction or rollback proof. */
    private boolean consumeDeposit(String no,long amountFen,String tx,
            com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference reference) {
        boolean old=TransactionSynchronizationManager.isActualTransactionActive();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { return svc.markDepositPaid(no,amountFen,tx,reference); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(old); }
    }

    @Test
    void accept_dueDepositStillAllowsCooperation() {
        CoopInvite c = clubInvite(1L, 0, 8001L, 100L, 5); // 锁价后的应缴未缴不挡合作
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(c);
        svc.acceptInviteWithLock(1L, null);
        verify(coopOrderMapper).insertCoopOrder(any());
    }

    @Test
    void deductDeposit_onlyWhenFrozen() {
        CoopInvite frozen = clubInvite(1L, 1, 8001L, 100L, 2);
        when(coopInviteMapper.selectCoopInviteById(1L)).thenReturn(frozen);
        when(coopInviteMapper.deductHeldDeposit(1L)).thenReturn(1);
        assertEquals(1, svc.deductDeposit(1L));
        CoopInvite notFrozen = clubInvite(2L, 1, 8001L, 100L, 0);
        when(coopInviteMapper.selectCoopInviteById(2L)).thenReturn(notFrozen);
        assertEquals(0, svc.deductDeposit(2L)); // 未冻结不扣
    }

    // —— attachPerks:申报层字段搬运 ——
    //
    // 负控清单(实测):
    //   删掉 CoopInviteServiceImpl.attachPerks 的 `p.setRetailValue(t.getRetailValue());`
    //     ⇒ attachPerks_copiesRetailValueFromTemplate 红(retailValue 为 null)
    //
    // 为什么值得一条用例:attachPerks 是逐字段手抄(不是 copyProperties),
    // 漏一个 setter 不报错、不炸,只是那列静默恒 NULL —— 与「章节入驻门槛比 retail_value」
    // 叠加后会让所有 perk 被拒。字段是手抄的,就必须有测试钉住。

    private CoopPerkTemplate template(Long id, Long merchantId, BigDecimal retailValue) {
        CoopPerkTemplate t = new CoopPerkTemplate();
        t.setId(id);
        t.setMerchantId(merchantId);
        t.setPerkType(0);
        t.setName("双人下午茶");
        t.setUnitCost(new BigDecimal("20.00"));
        t.setRetailValue(retailValue);
        t.setQuota(50);
        t.setRedeemRule("到店出示核销码");
        return t;
    }

    @Test
    void attachPerks_copiesRetailValueFromTemplate() {
        CoopInvite c = inv(1L, 1);
        c.setToType("merchant"); // 非 club ⇒ resolveInviteeOwner 直接返 toId
        c.setToId(200L);
        when(coopInviteMapper.selectCoopInviteById(1L)).thenReturn(c);
        when(coopPerkTemplateMapper.selectById(9L)).thenReturn(template(9L, 200L, new BigDecimal("88.00")));

        assertEquals(1, svc.attachPerks(1L, 200L, Collections.singletonList(9L)));

        ArgumentCaptor<CoopPerk> pc = ArgumentCaptor.forClass(CoopPerk.class);
        verify(coopPerkMapper).insertCoopPerk(pc.capture());
        CoopPerk saved = pc.getValue();
        assertNotNull(saved.getRetailValue(), "retail_value 必须从模板带过来,恒 NULL 会让入驻门槛拒掉所有 perk");
        assertEquals(0, new BigDecimal("88.00").compareTo(saved.getRetailValue()));
        // 同批搬运的其余字段不该被这次修复带歪
        assertEquals(0, new BigDecimal("20.00").compareTo(saved.getUnitCost()));
        assertEquals(Integer.valueOf(50), saved.getQuota());
    }

    @Test
    void attachPerks_skipsTemplateOfOtherMerchant() {
        CoopInvite c = inv(1L, 1);
        c.setToType("merchant");
        c.setToId(200L);
        when(coopInviteMapper.selectCoopInviteById(1L)).thenReturn(c);
        // 模板归属另一个商家 ⇒ 只用自己的模板
        when(coopPerkTemplateMapper.selectById(9L)).thenReturn(template(9L, 999L, new BigDecimal("88.00")));

        assertEquals(0, svc.attachPerks(1L, 200L, Collections.singletonList(9L)));
        verify(coopPerkMapper, times(0)).insertCoopPerk(any());
    }

    @Test
    void attachPerks_legacyType2_isReadOnly() {
        CoopInvite legacy = inv(1L, 1);
        legacy.setInviteType(CoopInvite.TYPE_LEGACY_MERCHANT_NODE);
        legacy.setToType("merchant");
        legacy.setToId(200L);
        when(coopInviteMapper.selectCoopInviteById(1L)).thenReturn(legacy);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.attachPerks(1L, 200L, Collections.singletonList(9L)));

        assertTrue(error.getMessage().contains("仅供查看"));
        verify(coopPerkMapper, never()).insertCoopPerk(any());
    }

    /** 开放招商的模板必须复制为独立快照；模板 ID 不能直接混进 merchant_chapter_offer.perk_id。 */
    @Test
    void materializeStandalonePerk_copiesOwnTemplateWithDirectMerchantOwner() {
        when(coopPerkTemplateMapper.selectById(9L)).thenReturn(template(9L, 200L, new BigDecimal("88.00")));
        when(coopPerkMapper.insertCoopPerk(any(CoopPerk.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, CoopPerk.class).setId(91L);
            return 1;
        });

        assertEquals(Long.valueOf(91L), svc.materializeStandalonePerkFromTemplate(9L, 200L, 12));

        ArgumentCaptor<CoopPerk> pc = ArgumentCaptor.forClass(CoopPerk.class);
        verify(coopPerkMapper).insertCoopPerk(pc.capture());
        CoopPerk saved = pc.getValue();
        assertNull(saved.getInviteId(), "开放招商供给不应伪造 coop_invite");
        assertEquals(Long.valueOf(200L), saved.getMerchantId(), "独立快照必须直接记录商家归属");
        assertEquals(Integer.valueOf(12), saved.getQuota(), "实际供给配额由本章提交值冻结");
        assertEquals(0, new BigDecimal("88.00").compareTo(saved.getRetailValue()));
    }

    /** 负控：不能用别人的模板生成自己的独立权益快照。 */
    @Test
    void materializeStandalonePerk_rejectsOtherMerchantsTemplate() {
        when(coopPerkTemplateMapper.selectById(9L)).thenReturn(template(9L, 999L, new BigDecimal("88.00")));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.materializeStandalonePerkFromTemplate(9L, 200L, 12));

        assertTrue(ex.getMessage().contains("自己的"));
        verify(coopPerkMapper, never()).insertCoopPerk(any());
    }

    /** 已到期的模板不能生成一个从一开始就不生效的章节供给快照。 */
    @Test
    void materializeStandalonePerk_rejectsExpiredTemplate() {
        CoopPerkTemplate expired = template(9L, 200L, new BigDecimal("88.00"));
        expired.setValidEnd(new Date(System.currentTimeMillis() - 60_000L));
        when(coopPerkTemplateMapper.selectById(9L)).thenReturn(expired);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.materializeStandalonePerkFromTemplate(9L, 200L, 12));

        assertTrue(ex.getMessage().contains("已过期"));
        verify(coopPerkMapper, never()).insertCoopPerk(any());
    }

    /** 独立快照的 owner 必须直接从 merchant_id 取，不能再错误地反查不存在的邀约。 */
    @Test
    void resolvePerkOwner_returnsDirectMerchantForStandalonePerk() {
        CoopPerk perk = new CoopPerk();
        perk.setMerchantId(200L);
        when(coopPerkMapper.selectById(91L)).thenReturn(perk);

        assertEquals(Long.valueOf(200L), svc.resolvePerkOwner(91L));
        verify(coopInviteMapper, never()).selectCoopInviteById(any());
    }

    @Test
    void depositRefundRetryRejectsMalformedInviteMatrixBeforeAnyRefundCanStart() {
        CoopInvite malformed = inv(1L, 3);
        malformed.setInviteType(1);
        malformed.setFromType("talent");
        malformed.setToType("merchant");
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(malformed);
        when(coopInviteMapper.selectCoopInviteById(1L)).thenReturn(malformed);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.authorizeDepositRefundRetry(1L, 200L));

        assertTrue(ex.getMessage().contains("类型"));
        verify(mmsMerchantMapper, never()).selectMmsMerchantByMemberIdForUpdate(any());
    }

    @Test
    void depositRefundRetryRejectsDisabledMerchantActingWithOldSession() {
        CoopInvite cancelled = inv(1L, 3);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(cancelled);
        when(coopInviteMapper.selectCoopInviteById(1L)).thenReturn(cancelled);
        MmsMerchant disabled = new MmsMerchant();
        disabled.setMemberId(200L);
        disabled.setStatus(1L);
        disabled.setAccountStatus(2L);
        disabled.setDelFlag(0);
        when(mmsMerchantMapper.selectMmsMerchantByMemberIdForUpdate(200L)).thenReturn(disabled);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.authorizeDepositRefundRetry(1L, 200L));

        assertTrue(ex.getMessage().contains("启用"));
    }

    @Test
    void depositRefundRetryKeepsClubSidePositiveControlWhenOppositeMerchantIsDisabled() {
        CoopInvite cancelled = clubInvite(1L, 3, 77L, 7001L, 2);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(cancelled);
        when(coopInviteMapper.selectCoopInviteById(1L)).thenReturn(cancelled);
        MmsMerchant disabled = new MmsMerchant();
        disabled.setMemberId(300L);
        disabled.setStatus(1L);
        disabled.setAccountStatus(2L);
        disabled.setDelFlag(0);
        when(mmsMerchantMapper.selectMmsMerchantByMemberIdForUpdate(300L)).thenReturn(disabled);

        assertEquals(cancelled, svc.authorizeDepositRefundRetry(1L, 7001L));

        verify(mmsMerchantMapper, never()).selectMmsMerchantByMemberIdForUpdate(300L);
    }

    @Test
    void depositRefundRetryRejectsStrangerAndNonTerminalInvite() {
        CoopInvite pending = inv(1L, 1);
        when(coopInviteMapper.selectCoopInviteByIdForUpdate(1L)).thenReturn(pending);

        assertThrows(IllegalStateException.class,
                () -> svc.authorizeDepositRefundRetry(1L, 999L));
        assertThrows(IllegalStateException.class,
                () -> svc.authorizeDepositRefundRetry(1L, 100L));
    }

    // 协作邀请页对俱乐部主理人也会拉官方邀约:「不是商家」必须是可稳定识别的专用异常,不是普通 ISE
    @Test
    void listOfficialMerchantInvites_nonMerchant_throwsNotActiveMerchantException() {
        when(mmsMerchantMapper.selectMmsMerchantByMemberId(424242L)).thenReturn(null);
        assertThrows(com.chengyinhub.business.service.NotActiveMerchantException.class,
                () -> svc.listOfficialMerchantInvites(424242L));
    }
}
