package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.vo.NodeMerchantOverride;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 承接方现场层:规则说明 / 反馈话术 / 到店券归商家,玩法归主办方。
 *
 * 2026-08-06 用户拍板。为什么这样切:节点是主办方叙事的一环(前站产出咬合后站、
 * 还有线性解锁闸),商家改玩法就断链;但现场怎么讲、答错说什么、给什么券,
 * 是他的店、他的人力、他的成本 —— 原来这三样无论谁承接都发主办方模板上那份,
 * **商家出了场地却没有自己的权益出口**。
 *
 * ⚠️ 负控已实测:把 forNode 改成恒返 empty ⇒ 下面 4 条覆写用例转红。
 */
class NodeMerchantOverrideServiceImplTest {

    private static final Long NODE = 500L;

    private ICmsRegistrationMerchantService regSvc;
    private NodeMerchantOverrideServiceImpl service;

    @BeforeEach
    void setUp() {
        regSvc = mock(ICmsRegistrationMerchantService.class);
        service = new NodeMerchantOverrideServiceImpl(regSvc);
    }

    private CmsRegistrationMerchant won(String rule, String feedback, Long couponId) {
        CmsRegistrationMerchant r = new CmsRegistrationMerchant();
        r.setMemberId(88L);
        r.setNodeId(NODE);
        r.setAuditStatus(1L);
        r.setRuleInstructions(rule);
        r.setFeedbackText(feedback);
        r.setCouponId(couponId);
        return r;
    }

    private void given(CmsRegistrationMerchant... rows) {
        when(regSvc.selectCmsRegistrationMerchantList(any())).thenReturn(Arrays.asList(rows));
    }

    @Test
    @Timeout(5)
    void merchantValues_win() {
        given(won("到店先跟店员说暗号", "再想想,提示在吧台", 77L));
        NodeMerchantOverride o = service.forNode(NODE);

        assertEquals("到店先跟店员说暗号", o.ruleInstructionsOr("主办方规则"));
        assertEquals("再想想,提示在吧台", o.feedbackTextOr("主办方反馈"));
        assertEquals(Long.valueOf(77L), o.couponIdOr(11L), "券该是商家自己的 —— 成本他出");
        assertEquals(Long.valueOf(88L), o.getMerchantMemberId());
    }

    /** 商家没配就回落主办方,别把页面弄空。 */
    @Test
    @Timeout(5)
    void blankMerchantValues_fallBackToTemplate() {
        given(won("  ", null, null));
        NodeMerchantOverride o = service.forNode(NODE);

        assertEquals("主办方规则", o.ruleInstructionsOr("主办方规则"), "空白串也算没配");
        assertEquals("主办方反馈", o.feedbackTextOr("主办方反馈"));
        assertEquals(Long.valueOf(11L), o.couponIdOr(11L));
    }

    /** ★存量脏数据:couponId=0 当空值使,直接透传会让下游拿 0 去查券。 */
    @Test
    @Timeout(5)
    void zeroCouponId_treatedAsUnset() {
        given(won(null, null, 0L));
        assertEquals(Long.valueOf(11L), service.forNode(NODE).couponIdOr(11L));
    }

    /** 没有中标商家 = 空对象,全部回落。这是绝大多数节点的常态,不能抛。 */
    @Test
    @Timeout(5)
    void noWinner_returnsEmpty() {
        when(regSvc.selectCmsRegistrationMerchantList(any())).thenReturn(Collections.emptyList());
        NodeMerchantOverride o = service.forNode(NODE);

        assertEquals("主办方规则", o.ruleInstructionsOr("主办方规则"));
        assertEquals(Long.valueOf(11L), o.couponIdOr(11L));
        assertNull(o.getMerchantMemberId());
    }

    /**
     * ★闸:只认中标(audit_status=1)且未删的报名。
     * 这条要是松了,任何人报个名就能把自己的券挂到别人的节点上发出去。
     */
    @Test
    @Timeout(5)
    void queryOnlyAsksForWinningUndeletedRegistration() {
        given(won("r", "f", 7L));
        service.forNode(NODE);

        ArgumentCaptor<CmsRegistrationMerchant> cap =
                ArgumentCaptor.forClass(CmsRegistrationMerchant.class);
        verify(regSvc).selectCmsRegistrationMerchantList(cap.capture());
        CmsRegistrationMerchant probe = cap.getValue();
        assertEquals(NODE, probe.getNodeId());
        assertEquals(Long.valueOf(1L), probe.getAuditStatus(), "必须只查已中标的");
        assertEquals(Integer.valueOf(0), probe.getDelFlag(), "已删的报名不能参与覆写");
    }

    /** 参数不合法直接空对象,别去打库。 */
    @Test
    @Timeout(5)
    void nullOrNonPositiveNodeId_shortCircuits() {
        for (Long bad : new Long[] { null, 0L, -1L }) {
            assertNull(service.forNode(bad).getMerchantMemberId());
        }
        verify(regSvc, org.mockito.Mockito.never()).selectCmsRegistrationMerchantList(any());
    }

    /**
     * 竞争位应保证一节点至多一个中标商家(win_key 唯一)。真出现多行说明约束被绕过 ——
     * 取第一条而不是抛错:玩家正站在店里,这里炸掉等于把数据问题变成玩家侧故障。
     */
    @Test
    @Timeout(5)
    void multipleWinners_takesFirstInsteadOfThrowing() {
        List<CmsRegistrationMerchant> two = Arrays.asList(won("第一家", null, 1L), won("第二家", null, 2L));
        when(regSvc.selectCmsRegistrationMerchantList(any())).thenReturn(two);
        assertEquals("第一家", service.forNode(NODE).ruleInstructionsOr("fallback"));
    }
}
