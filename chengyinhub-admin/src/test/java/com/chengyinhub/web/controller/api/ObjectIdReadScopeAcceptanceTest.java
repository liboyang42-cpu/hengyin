package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.ViewRegistrationMerchant;
import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsRegistrationScanService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IViewRegistrationMerchantService;
import com.chengyinhub.business.service.impl.AppMediaAssetService;
import com.chengyinhub.business.service.impl.MerchantRegistrationDetailReadServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验收 CX-41 中尚无测试的两类按 ID 读取端点:换 ID 读别人的对象被拒,拒绝不泄露字段、不产生写。
 * 其余端点(报名详情/地址/用户资料/订单/导出/审核单等)由既有测试覆盖,见 PR 证据表。
 */
class ObjectIdReadScopeAcceptanceTest {

    private static final Long PRINCIPAL = 9001L;
    private static final Long OTHER_OWNER = 9100L;

    @Test
    void merchantCannotReadAnotherShopsRegistrationDetailByChangingTheId() {
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, 11L, PRINCIPAL, MerchantRoleCode.MERCHANT_OWNER));
        IViewRegistrationMerchantService views = mock(IViewRegistrationMerchantService.class);
        ViewRegistrationMerchant foreign = new ViewRegistrationMerchant();
        foreign.setId(777L);
        foreign.setMemberId(OTHER_OWNER);
        foreign.setTopicId(70L);
        when(views.selectViewRegistrationMerchantById(777L)).thenReturn(foreign);
        ICmsRegistrationMerchantService rawRows = mock(ICmsRegistrationMerchantService.class);
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        ICmsMemberTemplateService templates = mock(ICmsMemberTemplateService.class);
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        ICmsRegistrationScanService scans = mock(ICmsRegistrationScanService.class);
        MerchantRegistrationDetailReadServiceImpl reader = new MerchantRegistrationDetailReadServiceImpl(
                views, rawRows, nodes, templates, mock(ICmsTopicChapterService.class), registrations, scans);
        ApiRegistrationMerchantController controller = new ApiRegistrationMerchantController() {
            @Override
            public Long getAppUserId() {
                return PRINCIPAL;
            }
        };
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "merchantRegistrationDetailReadService", reader);

        AjaxResult result = controller.getRegistrationDetail("777");

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("无权查看该报名记录", result.get(AjaxResult.MSG_TAG));
        assertFalse(result.containsKey(AjaxResult.DATA_TAG));
        verifyNoInteractions(rawRows, nodes, templates, registrations, scans);
    }

    @Test
    @SuppressWarnings("unchecked")
    void memberCannotReadPreviewOrResubmitAnotherMembersMediaByChangingTheId() {
        AppMediaAssetMapper mapper = mock(AppMediaAssetMapper.class);
        IAuditTaskService audits = mock(IAuditTaskService.class);
        ObjectProvider<IAuditTaskService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(audits);
        Map<String, Object> foreign = new LinkedHashMap<>();
        foreign.put("id", 12L);
        foreign.put("memberId", OTHER_OWNER);
        foreign.put("ownerType", "APP");
        foreign.put("reviewState", "APPROVED");
        foreign.put("objectKey", "upload/0123456789abcdef0123456789abcdef.mp3");
        foreign.put("version", 0);
        when(mapper.select(12L)).thenReturn(foreign);
        when(mapper.selectForUpdate(12L)).thenReturn(foreign);
        ApiMediaAssetController controller = new ApiMediaAssetController(new AppMediaAssetService(mapper, provider)) {
            @Override
            public Long getAppUserId() {
                return PRINCIPAL;
            }
        };

        assertEquals("无权读取该媒体", assertThrows(ServiceException.class, () -> controller.status(12L)).getMessage());
        assertEquals("无权读取该媒体", assertThrows(ServiceException.class, () -> controller.preview(12L)).getMessage());
        assertEquals("无权读取该媒体", assertThrows(ServiceException.class, () -> controller.retry(12L)).getMessage());

        verify(mapper, never()).retry(anyLong(), anyLong(), anyInt());
        verify(mapper, never()).bindAuditTask(anyLong(), anyInt(), anyLong());
        verifyNoInteractions(audits);
        verify(mapper, never()).decide(anyLong(), anyInt(), anyLong(), any(), any());
    }
}
