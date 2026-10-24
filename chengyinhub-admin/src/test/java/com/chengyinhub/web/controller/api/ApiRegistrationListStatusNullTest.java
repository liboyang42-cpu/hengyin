package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * /api/registration/list: owner_type=3 且 status 未传时不得 NPE。
 * 复现路径:小程序「投诉与建议」只传 owner_type=3,不传 status →
 * Cannot invoke "String.equals(Object)" because "status" is null。
 */
class ApiRegistrationListStatusNullTest {

    private ApiRegistrationController wiredController(ICmsRegistrationService registrationService) {
        ApiRegistrationController controller = spy(new ApiRegistrationController());
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "registrationService", registrationService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "topicService", mock(ICmsTopicService.class));
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "activityService", mock(ICmsActivityService.class));
        // #164 给 myRegistrationList 接入退款状态:latestRegistrationRefunds() 会调 refundService,
        // 未 mock 则空指针(生产由 Spring 注入,此处补 mock 返空即可)。
        IRefundService refundService = mock(IRefundService.class);
        when(refundService.selectRefundList(any())).thenReturn(new ArrayList<>());
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "refundService", refundService);
        doReturn(100L).when(controller).getAppUserId();
        return controller;
    }

    @Test
    void list_ownerType3_nullStatus_returns200_notNpe() {
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(new ArrayList<>(Collections.emptyList()));
        ApiRegistrationController controller = wiredController(registrationService);

        AjaxResult res = assertDoesNotThrow(
                () -> RegistrationQueryTestFixtures.wire(controller).myRegistrationList("3", null, null),
                "status=null 时不得 NPE(投诉页复现)");
        assertEquals(200, res.get(AjaxResult.CODE_TAG), "应正常返回 200");
        TableDataInfo data = (TableDataInfo) res.get(AjaxResult.DATA_TAG);
        assertEquals(0, data.getRows().size());
    }

    @Test
    void list_ownerType3_emptyStatus_returns200() {
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(new ArrayList<>(Collections.emptyList()));
        ApiRegistrationController controller = wiredController(registrationService);

        AjaxResult res = RegistrationQueryTestFixtures.wire(controller).myRegistrationList("3", "", null);
        assertEquals(200, res.get(AjaxResult.CODE_TAG), "空串等同未传/全部");
    }
}
