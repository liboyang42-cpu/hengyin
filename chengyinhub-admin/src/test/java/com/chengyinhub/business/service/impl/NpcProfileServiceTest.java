package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.vo.ai.NpcProfileVO;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 节点 NPC 的出场解析(CR-927):节点级优先、回落门店分身,两条都只取已过审版本。
 *
 * <p>读闸在 mapper 的 selectEnabledByScope(audit_status=1 AND enabled=1 AND del_flag='0'),
 * 有真库 IT 锁着;这里锁的是「选哪个 scope」这条业务规则 —— 写反了存量点位上的店分身会凭空消失,
 * 或节点 NPC 配了也不出场。
 */
class NpcProfileServiceTest {

    private static final Long NODE = 31L;
    private static final Long MERCHANT = 501L;
    private static final Long MEMBER = 50L;

    private NpcProfileMapper mapper;
    private NpcProfileService service;

    @BeforeEach
    void setUp() {
        mapper = mock(NpcProfileMapper.class);
        service = new NpcProfileService();
        com.chengyinhub.business.mapper.MmsMerchantMapper merchants = mock(com.chengyinhub.business.mapper.MmsMerchantMapper.class);
        com.chengyinhub.business.domain.MmsMerchant merchant = new com.chengyinhub.business.domain.MmsMerchant();
        merchant.setId(MERCHANT); merchant.setMemberId(MEMBER); merchant.setStatus(1L);
        merchant.setAccountStatus(1L); merchant.setDelFlag(0);
        when(merchants.selectMmsMerchantList(any())).thenReturn(Collections.singletonList(merchant));
        ReflectionTestUtils.setField(service, "merchantNpcIdentityDirectory",
                new com.chengyinhub.business.service.support.MerchantNpcIdentityDirectory(merchants));
        ReflectionTestUtils.setField(service, "npcProfileMapper", mapper);
    }

    private static NpcProfile row(long id, int scope, long scopeId, String name) {
        NpcProfile p = new NpcProfile();
        p.setId(id);
        p.setScopeType(scope);
        p.setScopeId(scopeId);
        p.setName(name);
        p.setGreeting("你好");
        return p;
    }

    @Test
    void resolveForNode_readsScopeFourAndReturnsNullWhenAbsent() {
        when(mapper.selectEnabledByScope(4, NODE)).thenReturn(Collections.singletonList(
                row(77L, 4, NODE, "阿福")));

        NpcProfileVO vo = service.resolveForNode(NODE);

        assertEquals("阿福", vo.getName());
        assertEquals(77L, vo.getProfileId());
        assertEquals(Integer.valueOf(4), vo.getScopeType());

        assertNull(service.resolveForNode(null), "没传 nodeId 不该去查库");
        when(mapper.selectEnabledByScope(4, 99L)).thenReturn(Collections.emptyList());
        assertNull(service.resolveForNode(99L), "没配过 = 无出场 NPC");
    }

    @Test
    void resolveForNodeOrMerchant_prefersNodeScope() {
        when(mapper.selectEnabledByScope(4, NODE)).thenReturn(Collections.singletonList(
                row(77L, 4, NODE, "阿福")));

        NpcProfileVO vo = service.resolveForNodeOrMerchant(NODE, MEMBER);

        assertEquals("阿福", vo.getName());
        verify(mapper, never()).selectEnabledByScope(eq(2), anyLong());
    }

    @Test
    void resolveForNodeOrMerchant_fallsBackToStoreNpc() {
        when(mapper.selectEnabledByScope(4, NODE)).thenReturn(Collections.emptyList());
        when(mapper.selectEnabledByScope(2, MERCHANT)).thenReturn(List.of(
                row(88L, 2, MERCHANT, "店小二")));

        NpcProfileVO vo = service.resolveForNodeOrMerchant(NODE, MEMBER);

        assertEquals("店小二", vo.getName(), "存量点位没有节点 NPC 时,店分身照旧出场");
        verify(mapper).selectEnabledByScope(4, NODE);
        verify(mapper).selectEnabledByScope(2, MERCHANT);
        verify(mapper, never()).selectEnabledByScope(2, MEMBER);
    }

    @Test
    void resolveForNodeOrMerchant_returnsNullWhenNeitherExists() {
        when(mapper.selectEnabledByScope(any(Integer.class), anyLong())).thenReturn(Collections.emptyList());
        assertNull(service.resolveForNodeOrMerchant(NODE, MEMBER));
        assertNull(service.resolveForNodeOrMerchant(NODE, null), "没挂商家的节点也不该 NPE");
    }

    /** M-28:节点上记的是商家 member_id,门店分身按 mms_merchant.id 存;剧情 NPC 列表必须先换算再查。 */
    @Test
    void merchantMemberListResolvesTheMerchantRowIdBeforeReadingScopeTwo() {
        when(mapper.selectEnabledByScope(2, MERCHANT)).thenReturn(Collections.singletonList(
                row(88L, 2, MERCHANT, "店长")));

        List<NpcProfileVO> list = service.resolveEnabledListForMerchantMember(MEMBER);

        assertEquals(1, list.size());
        assertEquals("店长", list.get(0).getName());
        verify(mapper, never()).selectEnabledByScope(2, MEMBER);
    }
}
