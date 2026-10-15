package com.chengyinhub.web.controller.business;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.domain.CmsRegistrationScan;
import com.chengyinhub.business.mapper.CoopGroupVerifyMapper;
import com.chengyinhub.business.service.ICmsRegistrationScanService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

/**
 * 核销证据台(主题运营控制台方案 §4.5)—— 「谁核销了、哪些去了」的只读穿透视图。
 *
 * <p><b>全只读</b>:不提供新增/删除/改核销结果的任何端点(§4.5 明文)。数据源两条:
 * 扫码流水 {@code cms_registration_scan}(谁扫的、扫了谁、什么方式、什么结果 —— 数人头的唯一真源,
 * ⚠️ 别拿 {@code cms_registration.verification_status} 数人头,那是布尔)+ 团核销
 * {@code coop_group_verify}(商家×领队×主题)。漫游据点奖励调证在 topic/roam-rewards(#152),不重建。
 *
 * <p><b>穿透</b> = 前端从主题工作台/商家/俱乐部页带 query 跳入本页预填筛选,四个入口同一份证据。
 */
@RestController
@RequestMapping("/business/verifyEvidence")
public class VerifyEvidenceController extends BaseController
{
    @Autowired
    private ICmsRegistrationScanService cmsRegistrationScanService;

    @Autowired
    private CoopGroupVerifyMapper coopGroupVerifyMapper;

    @Autowired
    private ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;

    /**
     * 扫码核销流水(原子记录)。筛 memberId/registrationId/registrationType,全可空。
     *
     * <p>⚠️ 行语义按 {@code registration_type} 分:1=核销台账行({@code member_id}=**核销商家**,
     * writeScanLedger 唯一收口,①②整单/③按章/动态码路径都汇到这)/ 0=报名主行(member_id=玩家)。
     * {@code status} 两条历史写入路径的常量不一致(主路径 0/旧路径 1),**不当「结果」筛**,故不收此参。
     */
    @PreAuthorize("@ss.hasPermi('business:verifyEvidence:list')")
    @GetMapping("/scans")
    public AjaxResult scans(Long memberId, Long registrationId, Integer registrationType)
    {
        if (!topicOperationsScopeAuthorizer.hasGlobalTopicScope()) {
            return error("当前账号无核销证据查看范围");
        }
        CmsRegistrationScan probe = new CmsRegistrationScan();
        probe.setMemberId(memberId);
        probe.setRegistrationId(registrationId);
        probe.setRegistrationType(registrationType);
        // 只读证据台要看得到已撤销的整单核销台账行(status=2),计数口径照旧排除。
        probe.getParams().put("includeReversed", Boolean.TRUE);
        return AjaxResult.success(cmsRegistrationScanService.selectCmsRegistrationScanList(probe));
    }

    /** 团核销台账(商家接待了哪个领队的团)。筛 topicId/merchantMemberId/leaderMemberId,全可空。 */
    @PreAuthorize("@ss.hasPermi('business:verifyEvidence:list')")
    @GetMapping("/groupVerifies")
    public AjaxResult groupVerifies(Long topicId, Long merchantMemberId, Long leaderMemberId)
    {
        boolean allowed = topicId == null ? topicOperationsScopeAuthorizer.hasGlobalTopicScope()
                : topicOperationsScopeAuthorizer.canViewTopic(topicId);
        if (!allowed) {
            return error("当前账号无主题核销证据查看范围");
        }
        return AjaxResult.success(coopGroupVerifyMapper.selectAdminList(topicId, merchantMemberId, leaderMemberId));
    }
}
