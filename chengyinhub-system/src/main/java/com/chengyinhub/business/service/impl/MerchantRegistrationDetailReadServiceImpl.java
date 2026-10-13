package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsRegistrationScan;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.ViewRegistrationMerchant;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsRegistrationScanService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IMerchantRegistrationDetailReadService;
import com.chengyinhub.business.service.IViewRegistrationMerchantService;
import com.chengyinhub.business.util.TemplateSecrets;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class MerchantRegistrationDetailReadServiceImpl implements IMerchantRegistrationDetailReadService {
    private final IViewRegistrationMerchantService viewRegistrationMerchantService;
    private final ICmsRegistrationMerchantService registrationMerchantService;
    private final ICmsTopicNodeService topicNodeService;
    private final ICmsMemberTemplateService memberTemplateService;
    private final ICmsTopicChapterService topicChapterService;
    private final ICmsRegistrationService registrationService;
    private final ICmsRegistrationScanService registrationScanService;

    public MerchantRegistrationDetailReadServiceImpl(IViewRegistrationMerchantService viewRegistrationMerchantService,
                                                      ICmsRegistrationMerchantService registrationMerchantService,
                                                      ICmsTopicNodeService topicNodeService,
                                                      ICmsMemberTemplateService memberTemplateService,
                                                      ICmsTopicChapterService topicChapterService,
                                                      ICmsRegistrationService registrationService,
                                                      ICmsRegistrationScanService registrationScanService) {
        this.viewRegistrationMerchantService = viewRegistrationMerchantService;
        this.registrationMerchantService = registrationMerchantService;
        this.topicNodeService = topicNodeService;
        this.memberTemplateService = memberTemplateService;
        this.topicChapterService = topicChapterService;
        this.registrationService = registrationService;
        this.registrationScanService = registrationScanService;
    }

    @Override
    public ViewRegistrationMerchant getRegistrationDetail(Long memberId, Long registrationId) {
        ViewRegistrationMerchant registration = viewRegistrationMerchantService
                .selectViewRegistrationMerchantById(registrationId);
        if (registration == null) {
            throw new RuntimeException("报名记录不存在");
        }
        if (!memberId.equals(registration.getMemberId())) {
            throw new RuntimeException("无权查看该报名记录");
        }
        CmsRegistrationMerchant raw = registrationMerchantService.selectCmsRegistrationMerchantById(registrationId);
        Long auditStatus = raw == null ? null : raw.getAuditStatus();
        registration.setAuditStatus(auditStatus);
        boolean isAssignedMerchant = Long.valueOf(1L).equals(auditStatus);

        Long nodeId = registration.getNodeId();
        CmsTopicNode node = nodeId != null && nodeId != 0L
                ? topicNodeService.selectCmsTopicNodeById(nodeId) : null;
        if (node != null) {
            registration.setTemplateId(node.getTemplateId());
            registration.setNodeName(node.getName());
            registration.setNodeImgUrl(node.getImgUrl());
            CmsMemberTemplate template = node.getTemplateId() == null ? null
                    : memberTemplateService.selectCmsMemberTemplateById(node.getTemplateId());
            if (template != null) {
                registration.setTemplateName(template.getTitle());
                registration.setTemplateDetail(isAssignedMerchant
                        ? TemplateSecrets.merchantView(template)
                        : TemplateSecrets.strip(template));
            }
            if (node.getChapterId() != null && node.getChapterId() != 0L) {
                CmsTopicChapter chapter = topicChapterService.selectCmsTopicChapterById(node.getChapterId());
                if (chapter != null) {
                    registration.setChapterId(chapter.getId());
                    registration.setChapterName(chapter.getName());
                }
            }
        }

        CmsRegistration registrationProbe = new CmsRegistration();
        registrationProbe.setPaymentStatus(2);
        registrationProbe.setOwnerType(1L);
        registrationProbe.setOwnerId(registration.getTopicId());
        List<CmsRegistration> paidRegistrations = registrationService.selectCmsRegistrationList(registrationProbe);
        registration.setTotalOrderNum(paidRegistrations.size());
        registration.setVerifiedNum(countVerifiedByMerchant(memberId, paidRegistrations));
        return registration;
    }

    private int countVerifiedByMerchant(Long memberId, List<CmsRegistration> paidRegistrations) {
        if (memberId == null || paidRegistrations == null || paidRegistrations.isEmpty()) {
            return 0;
        }
        Set<Long> paidIds = new HashSet<>();
        for (CmsRegistration registration : paidRegistrations) {
            if (registration.getId() != null) {
                paidIds.add(registration.getId());
            }
        }
        CmsRegistrationScan probe = new CmsRegistrationScan();
        probe.setMemberId(memberId);
        probe.setRegistrationType(1);
        int verified = 0;
        for (CmsRegistrationScan scan : registrationScanService.selectCmsRegistrationScanList(probe)) {
            if (scan.getRegistrationId() != null && paidIds.contains(scan.getRegistrationId())) {
                verified++;
            }
        }
        return verified;
    }
}
