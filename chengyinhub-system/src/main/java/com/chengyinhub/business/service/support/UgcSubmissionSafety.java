package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Round5B-6:零散 UGC 写入口的统一前置检查,只组合既有能力 —— 文本走 {@link IWxContentSecurityService#checkText}
 * (msgSecCheck v2,fail-close),图片走 {@link AppMediaReferenceGuard}(只收本站登记、本人上传或已审核通过的媒体)。
 * 不新建检测或审核系统。
 */
@Component
public class UgcSubmissionSafety {
    @Autowired private IWxContentSecurityService security;
    @Autowired private UmsMemberMapper members;
    @Autowired private AppMediaReferenceGuard media;

    /**
     * 文本机审:违规 / 审不了一律拒绝(抛 ServiceException,文案即检测服务给出的提示)。
     * @param humanReviewFollows 提交后还要过平台人工审核时传 true:机审只是「要人工」不拒绝,交给后续人审。
     */
    public void requireText(Long memberId, boolean humanReviewFollows, String... parts) {
        String text = IWxContentSecurityService.joinText(parts);
        if (text == null || text.trim().isEmpty()) return;
        String verdict = security.checkText(text, openid(memberId));
        if (verdict == null) return;
        if (humanReviewFollows && IWxContentSecurityService.REVIEW_REQUIRED.equals(verdict)) return;
        throw new ServiceException(verdict);
    }

    /** 文本机审结论,不抛:null=通过;非 null=没通过(含审不了)。给「不通过就换成默认值」的入口用。 */
    public String textVerdict(Long memberId, String text) {
        if (text == null || text.trim().isEmpty()) return null;
        return security.checkText(text, openid(memberId));
    }

    /** 图片引用校验(可多张,分号/逗号或 JSON 数组);外链、他人未审核媒体抛 ServiceException。原值不改写。 */
    public void requireOwnImages(Long memberId, String references) {
        if (references == null || references.trim().isEmpty()) return;
        media.requireAttachments(memberId, references, false);
    }

    private String openid(Long memberId) {
        if (memberId == null || memberId <= 0) return null;
        UmsMember member = members.selectUmsMemberById(memberId);
        return member == null ? null : member.getWxOpenId();
    }
}
