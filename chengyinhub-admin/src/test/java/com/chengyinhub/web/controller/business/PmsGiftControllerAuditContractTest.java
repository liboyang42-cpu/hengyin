package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.PmsGift;
import com.chengyinhub.business.service.IPmsGiftService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.common.enums.BusinessType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调价不是普通资料编辑：操作日志必须保存本次请求参数（包含服务端补入的原积分和操作原因）。
 */
class PmsGiftControllerAuditContractTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void priceEditWritesDurableOperationAudit() throws Exception {
        Method edit = PmsGiftController.class.getMethod("edit", PmsGift.class);
        Log log = edit.getAnnotation(Log.class);

        assertNotNull(log);
        assertEquals("礼物目录定价", log.title());
        assertEquals(BusinessType.UPDATE, log.businessType());
        assertTrue(log.isSaveRequestData());
    }

    @Test
    void writeEndpointsReplaceForgedOperatorWithAuthenticatedAdmin() {
        PmsGiftController controller = new PmsGiftController();
        IPmsGiftService giftService = mock(IPmsGiftService.class);
        ReflectionTestUtils.setField(controller, "pmsGiftService", giftService);
        authenticateAs("catalog-admin");
        when(giftService.insertPmsGift(any(PmsGift.class))).thenReturn(1);
        when(giftService.updatePmsGift(any(PmsGift.class))).thenReturn(1);

        PmsGift created = new PmsGift();
        created.setCreateBy("forged-creator");
        controller.add(created);
        PmsGift edited = new PmsGift();
        edited.setUpdateBy("forged-updater");
        controller.edit(edited);

        ArgumentCaptor<PmsGift> createdCaptor = ArgumentCaptor.forClass(PmsGift.class);
        ArgumentCaptor<PmsGift> editedCaptor = ArgumentCaptor.forClass(PmsGift.class);
        verify(giftService).insertPmsGift(createdCaptor.capture());
        verify(giftService).updatePmsGift(editedCaptor.capture());
        assertEquals("catalog-admin", createdCaptor.getValue().getCreateBy());
        assertEquals("catalog-admin", editedCaptor.getValue().getUpdateBy());
    }

    private void authenticateAs(String username) {
        SysUser user = new SysUser();
        user.setUserName(username);
        LoginUser loginUser = new LoginUser(1L, 1L, user, Collections.<String>emptySet());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
    }
}
