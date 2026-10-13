package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.dto.DispatchResult;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTopicService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 被邀商家接受 type2 邀约后的自动中标。
 *
 * 病灶(2026-08-07 修):`confirmNodeMerchant` 只在**已存在**待调配报名里挑,
 * 而被邀商家此刻**本来就没有报名行** —— 他是被主办方邀来的,没走过报名。
 * ⇒ 恒 `NO_PENDING_CANDIDATE`,调用方只打一条 error 日志(**零用户可见告警**),
 * 商家看到「已接受」以为拿到了节点,实际没占上,要人工去后台点一次 select 才闭环。
 *
 * 修法:邀约本身就是主办方的决定 = **预先批准**,按邀约补一条已中标的报名行
 * (与自由探索「邀请 = 预先批准的申请」同一范式)。
 * ⚠️ 补录不等于插队:竞争位仍由 slotHasOtherWinner 读守卫 + DB win_key 唯一键把关。
 */
class DispatchInvitedMerchantTest {

    private static final Long TOPIC = 300L;
    private static final Long NODE = 500L;
    private static final Long MERCHANT = 88L;

    private ICmsRegistrationMerchantService regSvc;
    private ICmsTopicService topicSvc;
    private CmsTopicMapper topicMapper;
    private ClubMapper clubMapper;
    private MmsMerchantMapper merchantMapper;
    private DispatchServiceImpl service;

    @BeforeEach
    void setUp() {
        regSvc = mock(ICmsRegistrationMerchantService.class);
        topicSvc = mock(ICmsTopicService.class);
        topicMapper = mock(CmsTopicMapper.class);
        clubMapper = mock(ClubMapper.class);
        merchantMapper = mock(MmsMerchantMapper.class);
        service = new DispatchServiceImpl(regSvc, topicSvc, topicMapper, clubMapper, merchantMapper);
        MmsMerchant merchant = new MmsMerchant();
        merchant.setMemberId(MERCHANT);
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        when(merchantMapper.selectMmsMerchantByMemberIdForUpdate(MERCHANT)).thenReturn(merchant);

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setDelFlag(0);
        when(topicSvc.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
        // 默认:该竞争位没有别的中标商家
        when(regSvc.selectCmsRegistrationMerchantList(any())).thenReturn(Collections.emptyList());
        when(regSvc.confirmWinnerIfPending(any())).thenReturn(1);
    }

    @Test
    @Timeout(5)
    void inactiveInvitedMerchantIsNotSeededOrConfirmed() {
        MmsMerchant disabled = new MmsMerchant();
        disabled.setMemberId(MERCHANT);
        disabled.setStatus(1L);
        disabled.setAccountStatus(2L);
        disabled.setDelFlag(0);
        when(merchantMapper.selectMmsMerchantByMemberIdForUpdate(MERCHANT)).thenReturn(disabled);

        DispatchResult result = service.confirmNodeMerchant(TOPIC, NODE, MERCHANT);

        assertEquals(DispatchResult.Code.MERCHANT_INACTIVE, result.getCode());
        verify(regSvc, never()).insertCmsRegistrationMerchant(any());
        verify(regSvc, never()).confirmWinnerIfPending(any());
    }

    /** 被邀商家没有报名行时,补一条并中标 —— 而不是返回「没有待调配报名」。 */
    @Test
    @Timeout(5)
    void invitedMerchantWithoutRegistration_getsSeededAndConfirmed() {
        // insert 时把 id 回填,模拟 useGeneratedKeys
        when(regSvc.insertCmsRegistrationMerchant(any())).thenAnswer(inv -> {
            ((CmsRegistrationMerchant) inv.getArgument(0)).setId(901L);
            return 1;
        });
        when(regSvc.selectCmsRegistrationMerchantById(901L)).thenAnswer(inv -> {
            CmsRegistrationMerchant r = new CmsRegistrationMerchant();
            r.setId(901L);
            r.setTopicId(TOPIC);
            r.setNodeId(NODE);
            r.setMemberId(MERCHANT);
            r.setAuditStatus(0L);
            r.setDelFlag(0);
            return r;
        });

        DispatchResult result = service.confirmNodeMerchant(TOPIC, NODE, MERCHANT);

        assertTrue(result.isSelected(), "被邀商家必须能自动中标,否则商家以为已合作实际没占上位子");

        ArgumentCaptor<CmsRegistrationMerchant> cap = ArgumentCaptor.forClass(CmsRegistrationMerchant.class);
        verify(regSvc).insertCmsRegistrationMerchant(cap.capture());
        CmsRegistrationMerchant seeded = cap.getValue();
        assertEquals(TOPIC, seeded.getTopicId());
        assertEquals(NODE, seeded.getNodeId(), "补录必须绑定邀约里那个节点");
        assertEquals(MERCHANT, seeded.getMemberId());
        assertEquals(Long.valueOf(0L), seeded.getAuditStatus(),
                "先落待调配,再走与报名同一条 CAS 中标路径 —— 不绕过竞争位");
    }

    /**
     * ★负控:补录不等于插队。该竞争位已有别人中标时必须拒,且**不插入任何行**。
     * 这条要是松了,邀约就成了顶掉别人已占位子的后门。
     */
    @Test
    @Timeout(5)
    void slotAlreadyWon_refusesAndInsertsNothing() {
        CmsRegistrationMerchant otherWinner = new CmsRegistrationMerchant();
        otherWinner.setId(700L);
        otherWinner.setTopicId(TOPIC);
        otherWinner.setNodeId(NODE);
        otherWinner.setMemberId(99L);
        otherWinner.setAuditStatus(1L);
        // 第一次查(待调配)空 ⇒ 走补录;补录前的竞争位查返回别人已中标
        when(regSvc.selectCmsRegistrationMerchantList(any())).thenAnswer(inv -> {
            CmsRegistrationMerchant probe = inv.getArgument(0);
            if (Long.valueOf(0L).equals(probe.getAuditStatus())) return new ArrayList<CmsRegistrationMerchant>();
            return Collections.singletonList(otherWinner);
        });

        DispatchResult result = service.confirmNodeMerchant(TOPIC, NODE, MERCHANT);

        assertFalse(result.isSelected());
        verify(regSvc, never()).insertCmsRegistrationMerchant(any());
    }

    /** 已有待调配报名时走原路径,不补录 —— 别把正常报名也变成补录。 */
    @Test
    @Timeout(5)
    void existingPendingRegistration_takesOriginalPath() {
        CmsRegistrationMerchant pending = new CmsRegistrationMerchant();
        pending.setId(800L);
        pending.setTopicId(TOPIC);
        pending.setNodeId(NODE);
        pending.setMemberId(MERCHANT);
        pending.setAuditStatus(0L);
        pending.setDelFlag(0);
        when(regSvc.selectCmsRegistrationMerchantList(any())).thenAnswer(inv -> {
            CmsRegistrationMerchant probe = inv.getArgument(0);
            return Long.valueOf(0L).equals(probe.getAuditStatus())
                    ? Collections.singletonList(pending) : new ArrayList<CmsRegistrationMerchant>();
        });
        when(regSvc.selectCmsRegistrationMerchantById(800L)).thenReturn(pending);

        DispatchResult result = service.confirmNodeMerchant(TOPIC, NODE, MERCHANT);

        assertTrue(result.isSelected());
        verify(regSvc, never()).insertCmsRegistrationMerchant(any());
    }

    /** 缺参数照旧拒,别让补录路径把参数校验也吞了。 */
    @Test
    @Timeout(5)
    void missingParams_stillRejected() {
        assertFalse(service.confirmNodeMerchant(null, NODE, MERCHANT).isSelected());
        assertFalse(service.confirmNodeMerchant(TOPIC, NODE, null).isSelected());
    }

    /** 主题不存在时不该补录出一条挂空的报名。 */
    @Test
    @Timeout(5)
    void missingTopic_refuses() {
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(null);
        List<CmsRegistrationMerchant> none = Collections.emptyList();
        when(regSvc.selectCmsRegistrationMerchantList(any())).thenReturn(none);
        when(regSvc.insertCmsRegistrationMerchant(any())).thenAnswer(inv -> {
            ((CmsRegistrationMerchant) inv.getArgument(0)).setId(902L);
            return 1;
        });
        CmsRegistrationMerchant seeded = new CmsRegistrationMerchant();
        seeded.setId(902L);
        seeded.setTopicId(TOPIC);
        seeded.setAuditStatus(0L);
        seeded.setDelFlag(0);
        when(regSvc.selectCmsRegistrationMerchantById(902L)).thenReturn(seeded);

        assertFalse(service.confirmNodeMerchant(TOPIC, NODE, MERCHANT).isSelected(),
                "主题不存在时 confirmRegistration 那道闸要接住");
        verify(regSvc, never()).insertCmsRegistrationMerchant(any());
    }
}
