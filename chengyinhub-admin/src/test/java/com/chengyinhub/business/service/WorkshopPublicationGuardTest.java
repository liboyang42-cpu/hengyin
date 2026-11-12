package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import com.chengyinhub.business.service.impl.TemplatePublishServiceImpl;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkshopPublicationGuardTest {
    private Map<String,Object> paid() {
        return new HashMap<>(Map.of("state","PAID","license_status","ACTIVE","paid_at",new Date(),
                "payment_fact_id",3L,"buyer_id",7L,"installation_owner",7L,"draft_owner",7L,"provider","WECHAT_APP"));
    }

    @Test void revokedLineageBlocksEveryExistingTemplatePublicationServiceEntry() {
        var mapper=mock(WorkshopMapper.class);var row=paid();row.put("license_status","REVOKED");
        when(mapper.lockLicenseForDraft(21L)).thenReturn(row);
        var publisher=new TemplatePublishServiceImpl();ReflectionTestUtils.setField(publisher,"workshopLicenses",new WorkshopLicenseGuard(mapper));
        var request=new CmsTemplatePublishRequest();request.setId(21L);
        var template=new CmsMemberTemplate();template.setId(21L);
        assertThrows(ServiceException.class,()->publisher.publishTemplateWithTransaction(request,7L,"buyer"));
        assertThrows(ServiceException.class,()->publisher.submitTemplateForReview(21L));
        assertThrows(ServiceException.class,()->publisher.submitTemplateToMarket(21L,7L,"buyer"));
        assertThrows(ServiceException.class,()->publisher.updateAdminTemplate(template,"admin"));
        assertThrows(ServiceException.class,()->publisher.saveMerchantNodeTemplate(template,7L,"buyer"));
        assertThrows(ServiceException.class,()->publisher.updateTemplateLibraryStatus(21L,1,7L,null));
        var library=mock(ICmsTemplateLibraryService.class);ReflectionTestUtils.setField(publisher,"templateLibraryService",library);
        CmsTemplateLibrary projection=new CmsTemplateLibrary();projection.setId(30L);projection.setMemberTemplateId(21L);projection.setDelFlag(0);
        when(library.selectCmsTemplateLibraryById(30L)).thenReturn(projection);
        assertThrows(ServiceException.class,()->publisher.updateTemplateFromLibraryProjection(projection,"admin"));
    }

    @Test void activePublicationStillSubmitsOriginalAuditButLaterRevocationStopsActualReviewCaller() {
        var workshop=mock(WorkshopMapper.class);var license=paid();when(workshop.lockLicenseForDraft(21L)).thenReturn(license);
        var guard=new WorkshopLicenseGuard(workshop);var publisher=new TemplatePublishServiceImpl();
        ReflectionTestUtils.setField(publisher,"workshopLicenses",guard);
        var members=mock(ICmsMemberTemplateService.class);var templates=mock(CmsMemberTemplateMapper.class);
        var library=mock(CmsTemplateLibraryMapper.class);var audit=mock(IAuditTaskService.class);var tasks=mock(AuditTaskMapper.class);
        ReflectionTestUtils.setField(publisher,"memberTemplateService",members);ReflectionTestUtils.setField(publisher,"memberTemplateMapper",templates);
        ReflectionTestUtils.setField(publisher,"libraryMapper",library);ReflectionTestUtils.setField(publisher,"auditTaskMapper",tasks);
        ReflectionTestUtils.setField(publisher,"auditTaskService",audit);ReflectionTestUtils.setField(publisher,"capabilityGuard",mock(ICapabilityGuard.class));
        ReflectionTestUtils.setField(publisher,"mediaPublicationGuard",mock(AppMediaPublicationGuard.class));
        var existing=new CmsMemberTemplate();existing.setId(21L);existing.setMemberId(7L);existing.setOriginalTemplateId(8L);existing.setDelFlag(0);
        when(templates.selectCmsMemberTemplateByIdsForUpdate(List.of(21L))).thenReturn(List.of(existing));
        when(members.updateCmsMemberTemplate(any())).thenReturn(1);
        when(audit.submitOrRefreshTemplate(eq(21L),eq(7L),anyString())).thenReturn(501L);
        var request=new CmsTemplatePublishRequest();request.setId(21L);request.setTitle("purchased");request.setValidationMethod(1);
        request.setQuestionName("question");request.setQuestionAnswer("purchased answer");
        assertEquals(21L,publisher.publishTemplateWithTransaction(request,7L,"buyer"));
        verify(audit).submitOrRefreshTemplate(eq(21L),eq(7L),anyString());
        license.put("license_status","REVOKED");
        var review=new AuditTaskServiceImpl();ReflectionTestUtils.setField(review,"workshopLicenses",guard);ReflectionTestUtils.setField(review,"auditTaskMapper",tasks);
        var task=new AuditTask();task.setId(501L);task.setBizId(21L);task.setBizType(AuditTask.BIZ_TEMPLATE);task.setSubmitterId(7L);task.setSourceType("auto_gate");task.setVersion(1);
        when(tasks.selectAuditTaskById(501L)).thenReturn(task);
        var decision=new AuditReviewDTO();decision.setStatus(AuditTask.STATUS_APPROVED);decision.setExpectedVersion(1);
        assertEquals("WORKSHOP_LICENSE_DISABLED",assertThrows(ServiceException.class,()->review.review(501L,99L,decision)).getMessage());
        verify(tasks,never()).selectAuditTaskByIdForUpdate(501L);
        assertEquals("purchased answer",request.getQuestionAnswer(),"Revocation does not erase the author's saved content");
    }

    @Test void suspendedLicenseBlocksLibraryApprovalAndPaidMarketRedistributionEvenWhileActive() {
        var workshop=mock(WorkshopMapper.class);var license=paid();when(workshop.lockLicenseForDraft(21L)).thenReturn(license);
        var guard=new WorkshopLicenseGuard(workshop);
        assertThrows(ServiceException.class,()->guard.requirePublication(21L,true));
        license.put("license_status","SUSPENDED");
        var review=new AuditTaskServiceImpl();var tasks=mock(AuditTaskMapper.class);var libraries=mock(CmsTemplateLibraryMapper.class);
        ReflectionTestUtils.setField(review,"workshopLicenses",guard);ReflectionTestUtils.setField(review,"auditTaskMapper",tasks);ReflectionTestUtils.setField(review,"libraryMapper",libraries);
        var task=new AuditTask();task.setId(501L);task.setBizType(AuditTask.BIZ_TEMPLATE_LIBRARY);task.setBizId(30L);task.setSourceType("auto_gate");
        var projection=new CmsTemplateLibrary();projection.setId(30L);projection.setMemberTemplateId(21L);
        when(tasks.selectAuditTaskById(501L)).thenReturn(task);when(libraries.selectCmsTemplateLibraryById(30L)).thenReturn(projection);
        var decision=new AuditReviewDTO();decision.setStatus(AuditTask.STATUS_APPROVED);
        assertEquals("WORKSHOP_LICENSE_DISABLED",assertThrows(ServiceException.class,()->review.review(501L,99L,decision)).getMessage());
        verify(tasks,never()).selectAuditTaskByIdForUpdate(anyLong());
    }

    @Test void originalFreeTemplateHasNoPaidRestrictionAndMismatchedInstallationOwnerCannotPublish() {
        var mapper=mock(WorkshopMapper.class);var guard=new WorkshopLicenseGuard(mapper);
        when(mapper.lockLicenseForDraft(21L)).thenReturn(null);
        assertDoesNotThrow(()->guard.requirePublication(21L,true));
        var row=paid();row.put("draft_owner",8L);when(mapper.lockLicenseForDraft(21L)).thenReturn(row);
        assertThrows(ServiceException.class,()->guard.requirePublication(21L,false));
    }

    @Test void topicBundleApprovalChecksOriginalLicenseBeforeLockingOrApprovingTemplates() {
        var topics=mock(CmsTopicMapper.class);var chapters=mock(CmsTopicChapterMapper.class);var nodes=mock(CmsTopicNodeMapper.class);var templates=mock(CmsMemberTemplateMapper.class);
        var bundles=new TopicBundleReviewService(topics,chapters,nodes,templates);
        var keys=mock(ThemeStateKeyMapper.class);ReflectionTestUtils.setField(bundles,"themeStateKeyMapper",keys);
        var topic=new CmsTopic();topic.setId(10L);topic.setMemberId(7L);topic.setProductType(1);topic.setPublishMode("pro");topic.setConfigVersion(3L);topic.setRouteMode("LINEAR");topic.setName("story");topic.setDelFlag(0);
        when(topics.selectCmsTopicById(10L)).thenReturn(topic);
        var chapter=new CmsTopicChapter();chapter.setId(20L);chapter.setTopicId(10L);chapter.setName("chapter");chapter.setSortId(1L);chapter.setBlocksJson("[{\"type\":\"node\",\"nodeId\":30}]");
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(List.of(chapter));
        var node=new CmsTopicNode();node.setId(30L);node.setTopicId(10L);node.setChapterId(20L);node.setName("place");node.setAddress("road");node.setLatitude("31.240100");node.setLongitude("121.490100");node.setTemplateId(21L);node.setStatus(1L);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(List.of(node));
        var template=new CmsMemberTemplate();template.setId(21L);template.setMemberId(7L);template.setTitle("paid draft");template.setQuestionName("question");template.setQuestionAnswer("frozen answer");template.setDraftStatus(0);template.setStatus(0L);template.setCheckStatus(0);template.setPublishStatus(0L);template.setDelFlag(0);
        when(templates.selectCmsMemberTemplateById(21L)).thenReturn(template);
        String snapshot=bundles.freezeForSubmission(10L,7L).getSnapshotJson();
        var mapper=mock(WorkshopMapper.class);var row=paid();row.put("license_status","REVOKED");when(mapper.lockLicenseForDraft(21L)).thenReturn(row);
        ReflectionTestUtils.setField(bundles,"workshopLicenses",new WorkshopLicenseGuard(mapper));
        assertThrows(ServiceException.class,()->bundles.applyDecision(10L,7L,snapshot,true));
        verify(topics,never()).selectCmsTopicByIdForUpdate(10L);verify(templates,never()).approveBundledDraft(any(),any());
    }
}
