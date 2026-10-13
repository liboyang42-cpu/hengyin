package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布广场聚合。
 *
 * 供发布广场顶部"发布能力卡":当前身份 + 可发布能力 + 上架额度 + 我的项目摘要。
 * 权限布尔在响应层从 user_permission 现有字段派生(canSimplePublish=canCreateTheme 等),
 * 不在领域层新造第二套权限词汇(见补充说明 §0.2)。
 */
@RestController
@Tag(name = "发布广场")
@RequestMapping("/api/publish")
public class ApiPublishController extends BaseController {

    private static final int MAX_CLUBS_PER_LEADER = 2;

    @Autowired
    private IRoleService roleService;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private ICmsTopicService cmsTopicService;

    @Autowired
    private ICmsActivityService cmsActivityService;

    @Autowired
    private IClubService clubService;

    @Autowired
    private IMmsMerchantService mmsMerchantService;

    @Operation(summary = "发布广场首页聚合(能力+额度+项目摘要)")
    @PostMapping("/home")
    public AjaxResult home() {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        UmsMember member = umsMemberService.selectUmsMemberById(memberId);
        if (member == null) {
            return error("用户不存在");
        }
        String role = roleService.resolveRole(member);
        UserPermission perm = roleService.getPermission(member);
        if (perm == null) {
            return error("当前身份权限配置缺失，请联系管理员");
        }

        boolean canCreateTheme = !Boolean.FALSE.equals(perm.getCanCreateTheme());
        boolean isMerchant = isMerchant(memberId);
        int ownedClubCount = clubCount(memberId);

        // 发布能力(响应层派生,映射现有 permission)
        Map<String, Object> permission = new HashMap<>();
        permission.put("canSimplePublish", canCreateTheme);
        permission.put("canProPublish", canCreateTheme);
        permission.put("canSelfPlay", canCreateTheme);
        permission.put("canPublishActivity", !Boolean.FALSE.equals(perm.getCanCreateEvent()));
        permission.put("canConfigureCoupon", !Boolean.FALSE.equals(perm.getCanPublishCoupon()));
        permission.put("canCreateClub", !isMerchant && ownedClubCount < MAX_CLUBS_PER_LEADER);

        // 上架额度:在架公开主题数 / 上限 / 剩余
        int themesOnline = cmsTopicService.selectOnlineThemeNumByMemberId(memberId, null);
        Map<String, Object> quota = new HashMap<>();
        quota.put("maxThemes", perm.getMaxThemes());
        quota.put("themesOnline", themesOnline);
        quota.put("themesRemaining", perm.getMaxThemes() == null ? null : Math.max(0, perm.getMaxThemes() - themesOnline));
        quota.put("maxNodesPerTheme", perm.getMaxNodesPerTheme());

        // 我的项目摘要:主题(审核态×上架态)+活动 归一计数
        Map<String, Object> projectSummary = summarize(memberId);

        Map<String, Object> data = new HashMap<>();
        data.put("role", role);
        data.put("isMerchant", isMerchant);
        data.put("permission", permission);
        data.put("quota", quota);
        data.put("projectSummary", projectSummary);
        return AjaxResult.success(data);
    }

    private Map<String, Object> summarize(Long memberId) {
        int pending = 0, online = 0, offline = 0, rejected = 0;
        CmsTopic tq = new CmsTopic();
        tq.setMemberId(memberId);
        tq.setDelFlag(0);
        tq.setActiveClubOnly(true);
        List<CmsTopic> topics = cmsTopicService.selectCmsTopicList(tq);
        if (topics != null) {
            for (CmsTopic t : topics) {
                int status = t.getStatus() == null ? 0 : t.getStatus();
                int userStatus = t.getUserStatus() == null ? 0 : t.getUserStatus();
                if (status == 2) rejected++;
                else if (status == 0) pending++;
                else if (userStatus == 1) online++;
                else offline++;
            }
        }
        CmsActivity aq = new CmsActivity();
        aq.setMemberId(memberId);
        aq.setActiveClubOnly(true);
        List<CmsActivity> acts = cmsActivityService.selectCmsActivityList(aq);
        if (acts != null) {
            for (CmsActivity a : acts) {
                int status = a.getStatus() == null ? 0 : a.getStatus().intValue();
                int publishStatus = a.getPublishStatus() == null ? 0 : a.getPublishStatus().intValue();
                if (status == 2) rejected++;
                else if (status == 0) pending++;
                else if (publishStatus == 1) online++;
                else offline++;
            }
        }
        Map<String, Object> s = new HashMap<>();
        s.put("pending", pending);
        s.put("online", online);
        s.put("offline", offline);
        s.put("rejected", rejected);
        s.put("total", pending + online + offline + rejected);
        return s;
    }

    private boolean isMerchant(Long memberId) {
        MmsMerchant m = mmsMerchantService.selectMmsMerchantByMemberId(memberId);
        return m != null && m.getStatus() != null && m.getStatus() == 1L;
    }

    private int clubCount(Long memberId) {
        List<com.chengyinhub.business.domain.Club> owned = clubService.selectClubsByMemberId(memberId);
        return owned == null ? 0 : owned.size();
    }
}
