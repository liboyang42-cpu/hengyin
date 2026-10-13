package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * A3-2(P0) + B4:票务写端点封口。
 *
 * <p>钉住:edit 把客户端声明的归属原样交给 Service —— 先由 Service 校验 ticket 与声明一致
 * (跨活动/主题的 ticket id 一律 403,B4),再在写库前剥离归属(ownerId/ownerType/memberId——
 * 防手填 ownerId 把票挪到别的主题绕发布审核)与运行时库存计数器(remainingInventory——
 * 订单流原子扣减维护,后台手改=超卖/清库存);remove 一律 FORBIDDEN(票被订单引用,物理删除=孤儿订单)。
 *
 * <p>剥离落点从 controller 移到 Service 是 B4 的必要条件:controller 提前置 null 会让 Service
 * 看不到声明,归属绑定闸就永远不成立。剥离本身没有取消,只是推到了绑定校验之后。
 */
class OmsTicketControllerSafetyTest {

    private OmsTicketController controllerWith(IOmsTicketService service) {
        OmsTicketController controller = new OmsTicketController();
        ReflectionTestUtils.setField(controller, "omsTicketService", service);
        return controller;
    }

    @Test
    void editPassesClaimedOwnershipToServiceForBindingThenStripping() {
        IOmsTicketService service = mock(IOmsTicketService.class);
        OmsTicket submitted = new OmsTicket();
        submitted.setId(5L);
        submitted.setPrice(new BigDecimal("88"));   // 合法可管理字段:必须照常提交
        submitted.setOwnerId(999L);
        submitted.setOwnerType(1L);
        submitted.setMemberId(7L);
        submitted.setRemainingInventory(100000L);

        controllerWith(service).edit(submitted);

        // 归属声明必须原样透传给 Service:置 null 会让「票是不是属于本活动」这唯一判据消失。
        verify(service).updateOmsTicket(argThat(t ->
                Long.valueOf(999L).equals(t.getOwnerId())
                        && Long.valueOf(1L).equals(t.getOwnerType())
                        && Long.valueOf(7L).equals(t.getMemberId())
                        && Long.valueOf(100000L).equals(t.getRemainingInventory())
                        && new BigDecimal("88").equals(t.getPrice())
                        && Long.valueOf(5L).equals(t.getId())));
    }

    @Test
    void removeRejectsPhysicalTicketDeletion() {
        IOmsTicketService service = mock(IOmsTicketService.class);
        AjaxResult result = controllerWith(service).remove(new Long[] { 1L });

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("不支持物理删除"),
                "应被票务禁删封口拦下,实际文案:" + result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(service);
    }
}
