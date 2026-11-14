package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.AuditRecord;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicContentRevision;
import com.chengyinhub.business.exception.BountyTipException;
import com.chengyinhub.business.mapper.CmsTopicContentRevisionMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IHostTipService;
import com.chengyinhub.business.service.IVideoBountyService;
import com.chengyinhub.business.service.support.JourneyReviewTextService;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RM-02 backoffice verification:
 * - OWN-E: VideoBountyAdminController + HostTipAdminController RBAC & read-only guarantees.
 * - OWN-U03-04: AuditWorkbenchController.detail() unifiedStatus + published vs reviewed diff.
 * - OWN-UG-12: CmsMemberTemplate Excel secret answer isolation.
 */
class Rm02BackofficeAdminTest {

    @Test
    void ownE_videoBountyAndHostTipPermissionsAndReadonlyContracts() throws Exception {
        Method listCampaigns = VideoBountyAdminController.class.getMethod("listCampaigns", Integer.class);
        assertEquals("@ss.hasPermi('business:videobounty:config')",
                listCampaigns.getAnnotation(PreAuthorize.class).value());
        Method reviewSub = VideoBountyAdminController.class.getMethod(
                "review", Long.class, com.chengyinhub.business.domain.dto.VideoBountyReviewRequest.class);
        assertEquals("@ss.hasPermi('business:videobounty:review')",
                reviewSub.getAnnotation(PreAuthorize.class).value());

        for (Method m : HostTipAdminController.class.getDeclaredMethods()) {
            assertNull(m.getAnnotation(PostMapping.class), "OWN-E HostTip admin must not expose POST payout/mark-paid");
            assertNull(m.getAnnotation(PutMapping.class), "OWN-E HostTip admin must not expose PUT mutation");
        }
        Method listByHost = HostTipAdminController.class.getMethod("listByHost", Long.class);
        assertNotNull(listByHost.getAnnotation(GetMapping.class));
        assertEquals("@ss.hasPermi('business:hosttip:audit')",
                listByHost.getAnnotation(PreAuthorize.class).value());

        IVideoBountyService bountyService = mock(IVideoBountyService.class);
        VideoBountyAdminController bountyCtrl = new VideoBountyAdminController(bountyService);
        when(bountyService.listCampaigns(1)).thenReturn(Collections.emptyList());
        assertEquals(200, bountyCtrl.listCampaigns(1).get("code"));

        IHostTipService hostTipService = mock(IHostTipService.class);
        HostTipAdminController hostCtrl = new HostTipAdminController(hostTipService);
        AjaxResult locked = hostCtrl.handleRejection(BountyTipException.conflict("GROUP_VERIFY_VERSION_CONFLICT", "冲突"));
        assertEquals("GROUP_VERIFY_VERSION_CONFLICT", locked.get("reasonCode"));
    }

    @Test
    void ownU0304_auditDetailIncludesPublishedVsReviewedDiffAndUnifiedStatus() {
        AuditWorkbenchController controller = new AuditWorkbenchController();
        IAuditTaskService auditTaskService = mock(IAuditTaskService.class);
        ICmsTopicService cmsTopicService = mock(ICmsTopicService.class);
        ICmsMemberTemplateService templateService = mock(ICmsMemberTemplateService.class);
        JourneyReviewTextService reviewTextService = mock(JourneyReviewTextService.class);
        CmsTopicContentRevisionMapper revisionMapper = mock(CmsTopicContentRevisionMapper.class);

        ReflectionTestUtils.setField(controller, "auditTaskService", auditTaskService);
        ReflectionTestUtils.setField(controller, "cmsTopicService", cmsTopicService);
        ReflectionTestUtils.setField(controller, "cmsMemberTemplateService", templateService);
        ReflectionTestUtils.setField(controller, "journeyReviewTextService", reviewTextService);
        ReflectionTestUtils.setField(controller, "topicContentRevisionMapper", revisionMapper);

        AuditTask task = new AuditTask();
        task.setId(501L);
        task.setBizType(AuditTask.BIZ_TOPIC);
        task.setBizId(88L);
        task.setStatus(0);
        task.setVersion(2);
        task.setSnapshotJson("{\"name\":\"新标题\",\"checkStatus\":0,\"lifecycle\":3}");

        CmsTopic topic = new CmsTopic();
        topic.setId(88L);
        topic.setName("当前公开标题");
        topic.setStatus(1);
        topic.setCheckStatus(1);
        topic.setUserStatus(1);
        topic.setLifecycle(2);
        topic.setConfigVersion(5L);
        topic.setPublishedConfigVersion(4L);

        CmsTopicContentRevision rev = new CmsTopicContentRevision();
        rev.setId(19L);
        rev.setTopicId(88L);
        rev.setRevisionNo(4L);
        rev.setContentSha256("abc123");

        when(auditTaskService.selectAuditTaskById(501L)).thenReturn(task);
        when(auditTaskService.listAuditRecords(501L)).thenReturn(List.of(new AuditRecord()));
        when(cmsTopicService.selectCmsTopicById(88L)).thenReturn(topic);
        when(revisionMapper.selectLatestByTopicId(88L)).thenReturn(rev);

        SysUser adminUser = new SysUser();
        adminUser.setUserId(1L);
        LoginUser principal = new LoginUser();
        principal.setUserId(1L);
        principal.setUser(adminUser);
        principal.setPermissions(Collections.singleton("*:*:*"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, Collections.emptyList()));
        AjaxResult result;
        try {
            result = controller.detail(501L, null);
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertEquals(200, result.get("code"));

        @SuppressWarnings("unchecked")
        Map<String, Object> unified = (Map<String, Object>) result.get("unifiedStatus");
        assertEquals(1, unified.get("checkStatus"));
        assertEquals(1, unified.get("userStatus"));
        assertEquals(2, unified.get("lifecycle"));

        @SuppressWarnings("unchecked")
        Map<String, Object> published = (Map<String, Object>) result.get("publishedSnapshot");
        assertEquals(4L, published.get("revisionNo"));
        assertEquals("当前公开标题", published.get("name"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diff = (List<Map<String, Object>>) result.get("revisionDiff");
        assertFalse(diff.isEmpty());
        assertTrue(diff.stream().anyMatch(d -> "name".equals(d.get("field"))
                && "当前公开标题".equals(d.get("publishedValue"))
                && "新标题".equals(d.get("reviewedValue"))));
    }

    @Test
    void ownUg12_memberTemplateSecretAnswersExcludedFromExcelExport() throws Exception {
        for (String fieldName : List.of("questionAnswer", "correctAnswer", "answerReveal")) {
            Field f = CmsMemberTemplate.class.getDeclaredField(fieldName);
            assertNull(f.getAnnotation(Excel.class),
                    "UG-12: secret answer field " + fieldName + " must NOT carry @Excel export annotation");
        }
    }
}
