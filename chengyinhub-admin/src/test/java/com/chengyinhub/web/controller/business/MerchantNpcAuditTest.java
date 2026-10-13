package com.chengyinhub.web.controller.business;

import java.util.HashMap;
import java.util.Map;

import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 门店形象的审核通道。
 *
 * <p><b>★★ 这组测试存在的理由:2026-09-05 之前这条通道根本不存在。</b>
 * 商家在 App/小程序自助提交一律落 {@code audit_status=0};而后台的 add 建的是
 * <b>已过审</b>(后台是可信来源)、edit 不碰该字段 —— 也就是<b>商家配完永远不会上线</b>,
 * 而且零报错零告警:玩家侧表现是「所有店铺都没有形象」,与「运营还没配」长得一模一样。
 *
 * <p>四条要害:
 * <ul>
 *   <li>只认 1/2 —— 审核状态是可见性开关,认不出的值不能靠猜</li>
 *   <li><b>驳回必须有原因</b> —— 不说为什么,商家只会把同一份原样再交一遍</li>
 *   <li><b>通过时清空原因</b> —— 留着上次的驳回理由,商家会看到「已上线」旁挂着「头像不是本人」</li>
 *   <li>mapper 的 WHERE 钉 {@code audit_status=0} —— 一次误点不能把已上线的打回</li>
 * </ul>
 */
class MerchantNpcAuditTest {

    private CmsMerchantNpcController controller;
    private NpcProfileMapper mapper;

    @BeforeEach
    void setUp() {
        controller = new CmsMerchantNpcController();
        mapper = mock(NpcProfileMapper.class);
        ReflectionTestUtils.setField(controller, "npcProfileMapper", mapper);

        NpcProfile existing = new NpcProfile();
        existing.setId(9L);
        existing.setAuditStatus(0);
        when(mapper.selectMerchantNpcById(9L)).thenReturn(existing);
        when(mapper.auditMerchantNpc(any())).thenReturn(1);
    }

    private Map<String, Object> body(Object id, Object status, Object reason) {
        Map<String, Object> m = new HashMap<>();
        if (id != null) m.put("id", id);
        if (status != null) m.put("auditStatus", status);
        if (reason != null) m.put("auditReason", reason);
        return m;
    }

    private static int code(AjaxResult r) {
        return ((Number) r.get(AjaxResult.CODE_TAG)).intValue();
    }

    @Test
    void approvesPendingProfile() {
        AjaxResult r = controller.audit(body(9L, 1, null));

        assertThat(code(r)).isEqualTo(200);
        verify(mapper).auditMerchantNpc(argThat(p ->
                Integer.valueOf(1).equals(p.getAuditStatus()) && p.getAuditReason() == null));
    }

    /** ★★ 驳回必须写原因。没有原因 = 商家把同一份原样再交一遍,一个死循环。 */
    @Test
    void rejectionRequiresReason() {
        assertThat(code(controller.audit(body(9L, 2, null)))).isNotEqualTo(200);
        assertThat(code(controller.audit(body(9L, 2, "   ")))).isNotEqualTo(200);
        verify(mapper, never()).auditMerchantNpc(any());

        AjaxResult ok = controller.audit(body(9L, 2, "头像不是本人"));
        assertThat(code(ok)).isEqualTo(200);
        verify(mapper).auditMerchantNpc(argThat(p -> "头像不是本人".equals(p.getAuditReason())));
    }

    /** ★ 通过时清空原因 —— 否则「已上线」旁边还挂着上一次的驳回理由。 */
    @Test
    void approvalClearsPreviousReason() {
        controller.audit(body(9L, 1, "上次驳回的理由"));

        verify(mapper).auditMerchantNpc(argThat(p -> p.getAuditReason() == null));
    }

    /** ★ 只认 1/2:审核状态是可见性开关,认不出的值不能靠猜。 */
    @Test
    void onlyAcceptsApproveOrReject() {
        for (final Object bad : new Object[] {0, 3, -1, "yes", null}) {
            assertThat(code(controller.audit(body(9L, bad, "r"))))
                    .as("auditStatus=%s 必须被拒", bad)
                    .isNotEqualTo(200);
        }
        verify(mapper, never()).auditMerchantNpc(any());
    }

    @Test
    void rejectsMissingOrUnknownProfile() {
        assertThat(code(controller.audit(body(null, 1, null)))).isNotEqualTo(200);

        when(mapper.selectMerchantNpcById(77L)).thenReturn(null);
        assertThat(code(controller.audit(body(77L, 1, null)))).isNotEqualTo(200);
        verify(mapper, never()).auditMerchantNpc(any());
    }

    /**
     * ★ mapper 返回 0 行 = 它已经不是待审状态(别人审过了 / 商家又改了)。
     * 必须如实说,不能报成功 —— 报成功会让审核员以为处理完了。
     */
    @Test
    void reportsWhenNoLongerPending() {
        when(mapper.auditMerchantNpc(any())).thenReturn(0);

        AjaxResult r = controller.audit(body(9L, 1, null));

        assertThat(code(r)).isNotEqualTo(200);
        assertThat(String.valueOf(r.get(AjaxResult.MSG_TAG))).contains("待审");
    }
}
