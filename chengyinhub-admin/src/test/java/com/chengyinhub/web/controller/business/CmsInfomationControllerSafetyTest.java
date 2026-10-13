package com.chengyinhub.web.controller.business;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.CmsInfomation;
import com.chengyinhub.business.domain.dto.CmsInfomationWriteDTO;
import com.chengyinhub.business.service.ICmsInfomationService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 玩法说明仍被小程序公开展示链路读取：后台迁移不能让伪造操作者或物理删除穿透。
 */
class CmsInfomationControllerSafetyTest {

    private ICmsInfomationService service;
    private CmsInfomationController controller;

    @BeforeEach
    void setUp() {
        service = mock(ICmsInfomationService.class);
        controller = new CmsInfomationController();
        ReflectionTestUtils.setField(controller, "cmsInfomationService", service);
        authenticateAs("guide-admin");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void writeEndpointsAcceptOnlyWhitelistedFieldsAndUseAuthenticatedAdmin() {
        when(service.insertCmsInfomation(any(CmsInfomation.class))).thenReturn(1);
        when(service.updateCmsInfomation(any(CmsInfomation.class))).thenReturn(1);

        CmsInfomationWriteDTO created = request("新增说明", "新增原因");
        created.setId(88L);
        controller.add(created);

        CmsInfomationWriteDTO edited = request("编辑说明", "编辑原因");
        edited.setId(9L);
        controller.edit(edited);

        ArgumentCaptor<CmsInfomation> createdCaptor = ArgumentCaptor.forClass(CmsInfomation.class);
        ArgumentCaptor<CmsInfomation> editedCaptor = ArgumentCaptor.forClass(CmsInfomation.class);
        verify(service).insertCmsInfomation(createdCaptor.capture());
        verify(service).updateCmsInfomation(editedCaptor.capture());
        assertEquals(null, createdCaptor.getValue().getId());
        assertEquals("guide-admin", createdCaptor.getValue().getCreateBy());
        assertEquals(null, createdCaptor.getValue().getUpdateBy());
        assertEquals(null, editedCaptor.getValue().getCreateBy());
        assertEquals("guide-admin", editedCaptor.getValue().getUpdateBy());
    }

    @Test
    void removeRejectsDeletingAStillPublishedGuide() {
        ServiceException exception = assertThrows(ServiceException.class, () -> controller.remove(new Long[] { 8L }));

        assertEquals(HttpStatus.FORBIDDEN, exception.getCode());
        assertTrue(exception.getMessage().contains("小程序展示链路"));
        verifyNoInteractions(service);
    }

    @Test
    void auditReasonIsAcceptedForOperationLogButNeverExposedToMiniProgramReads() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        CmsInfomationWriteDTO submitted = objectMapper.readValue("{\"title\":\"玩法\",\"auditReason\":\"更新文案\"}", CmsInfomationWriteDTO.class);

        assertEquals("更新文案", submitted.getAuditReason());
        assertTrue(JSON.toJSONString(submitted).contains("auditReason"));
        assertTrue(!objectMapper.writeValueAsString(new CmsInfomation()).contains("auditReason"));
    }

    @Test
    void writeRejectsMissingOrOverlongAuditReasonBeforeServiceCall() {
        ServiceException missing = assertThrows(ServiceException.class, () -> controller.add(request("玩法", " ")));
        ServiceException overlong = assertThrows(ServiceException.class, () -> controller.edit(request("玩法", repeat('a', 501))));

        assertEquals(HttpStatus.BAD_REQUEST, missing.getCode());
        assertTrue(missing.getMessage().contains("变更说明"));
        assertEquals(HttpStatus.BAD_REQUEST, overlong.getCode());
        assertTrue(overlong.getMessage().contains("500"));
        verifyNoInteractions(service);
    }

    private void authenticateAs(String username) {
        SysUser user = new SysUser();
        user.setUserName(username);
        LoginUser loginUser = new LoginUser(1L, 1L, user, Collections.<String>emptySet());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    private CmsInfomationWriteDTO request(String title, String auditReason) {
        CmsInfomationWriteDTO request = new CmsInfomationWriteDTO();
        request.setTitle(title);
        request.setSubtitle("副标题");
        request.setSortId(1L);
        request.setContents("<p>正文</p>");
        request.setAuditReason(auditReason);
        return request;
    }

    private String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int index = 0; index < count; index++)
        {
            builder.append(value);
        }
        return builder.toString();
    }
}
