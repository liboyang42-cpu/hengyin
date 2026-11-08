package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantOperationScope;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MemberCapability;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

import com.chengyinhub.common.utils.OwnershipUtils;
/**
 * 主题模板(= 已发布主题另存为模板,复制即用)。
 *
 * 实现口径(补充说明 §0.1):不新建 topic-template 表,复用 CmsTopic —— is_template=1 即模板;
 * /use = copyCmsTopic 全量复制并把归属改为当前会员(生成该会员草稿)。依赖 migration_topic_template_20260707.sql。
 */
@RestController
@Tag(name = "主题模板")
@RequestMapping("/api/template/topic-template")
public class ApiTopicTemplateController extends BaseController {

    @Autowired
    private ICmsTopicService cmsTopicService;

    @Autowired
    private ICapabilityService capabilityService;

    @Autowired
    private IClubService clubService;

    @Autowired
    private INotifyService notifyService;

    @Autowired
    private IMmsMerchantService mmsMerchantService;

    @Autowired
    private IMerchantAccessService merchantAccessService;

    @Autowired
    private com.chengyinhub.business.service.support.TopicLocationBindingService locationBindingService;

    @Operation(summary = "主题模板库列表（含章节数/玩法数）")
    @PostMapping("/list")
    public AjaxResult list(String recommendedOnly) {
        // recommendedOnly=1 → 只回 template_status=VERIFIED 的推荐范本;不传 = 既有货架行为不变
        return AjaxResult.success(cmsTopicService.buildTopicTemplateListVO("1".equals(recommendedOnly)));
    }

    @Operation(summary = "主题模板详情（聚合：章节→节点→玩法 + 路线剪影）")
    @PostMapping("/info")
    public AjaxResult info(String id) {
        Long tid = Convert.toLong(id, 0L);
        if (tid < 1) {
            return error("缺少模板ID");
        }
        // 章节招募聚合对登录商家及本主题原发布者可见；商家身份判据走能力真源 member_capability
        // (不读 ums_member.role / 不直查 mms_merchant——那两处是历史遗留判据)
        Long memberId = null;
        try {
            memberId = getAppUserId();
        } catch (Exception ignore) {
            // 未登录:匿名可看故事面,recruitStatus 整段不下发
        }
        boolean viewerIsMerchant = memberId != null
                && capabilityService.hasCapability(memberId, MemberCapability.CAP_MERCHANT);
        Integer merchantCategoryId = null;
        if (viewerIsMerchant) {
            MmsMerchant merchant = mmsMerchantService.selectMmsMerchantByMemberId(memberId);
            merchantCategoryId = merchant != null && merchant.getCategoryId() != null
                    ? merchant.getCategoryId() : -1;
        }
        com.chengyinhub.business.domain.vo.TopicTemplateDetailVO detail =
                cmsTopicService.buildPublicTopicTemplateDetail(
                        tid, memberId, viewerIsMerchant, merchantCategoryId);
        if (detail == null) {
            return error("模板不存在");
        }
        return AjaxResult.success(detail);
    }

    /**
     * 俱乐部对模板主题表达承接兴趣 —— 轻量口径(2026-07-21 拍板):
     * 只给创建者发一条订阅消息通知,不落任何"申请中/待审核"状态、不建状态机、不加并发锁。
     * 创建者若有意,走既有完整流程(发邀约 → 俱乐部接受+保证金 → transfer-to-club)。
     * 订阅消息是 best-effort:创建者未授权模板时静默不发,前端文案不得写"对方已收到"。
     */
    @Operation(summary = "俱乐部承接意向（仅通知创建者，不落状态）")
    @PostMapping("/club-interest")
    public AjaxResult clubInterest(String id) {
        Long tid = Convert.toLong(id, 0L);
        if (tid < 1) {
            return error("缺少模板ID");
        }
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        Club club = clubService.selectClubByMemberId(memberId);
        if (club == null) {
            return error("仅俱乐部主理人可申请承接");
        }
        Integer isTemplate = cmsTopicService.selectTopicIsTemplate(tid);
        if (isTemplate == null || isTemplate != 1) {
            return error("模板不存在");
        }
        CmsTopic topic = cmsTopicService.selectCmsTopicById(tid);
        if (topic == null) {
            return error("模板不存在");
        }
        if (topic.getClubId() != null) {
            return error("该主题已被俱乐部承接");
        }
        if (topic.getMemberId() == null) {
            return error("该主题暂无创建者");
        }
        if (OwnershipUtils.isOwner(topic.getMemberId(), memberId)) {
            return error("这是你自己的主题");
        }
        notifyService.notifyClubInterested(topic.getMemberId(), topic.getName(), club.getName());
        return AjaxResult.success();
    }

    @Operation(summary = "使用主题模板(复制生成我的草稿)")
    @PostMapping("/use")
    public AjaxResult use(String id, String scope, String bindingIntent, String targetCityCode) {
        Long principalMemberId;
        try {
            principalMemberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (principalMemberId == null || principalMemberId <= 0) {
            return error("请先登录");
        }
        Long tid = Convert.toLong(id, 0L);
        if (tid < 1) {
            return error("缺少模板ID");
        }
        Integer isTemplate = cmsTopicService.selectTopicIsTemplate(tid);
        if (isTemplate == null || isTemplate != 1) {
            return error("该主题不是可用模板");
        }
        Long memberId = principalMemberId;
        try {
            if (MerchantOperationScope.isMerchant(scope)) {
                memberId = merchantAccessService.require(
                        principalMemberId, MerchantPermission.PROJECT_MANAGE).getOwnerMemberId();
            }
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        return useTemplateForMember(tid, memberId, bindingIntent, targetCityCode);
    }

    /** 旧调用方/单测入口：不传采用方式 = LEGACY，复制结果与旧版一致。 */
    public AjaxResult use(String id, String scope) {
        return use(id, scope, null, null);
    }

    private AjaxResult useTemplateForMember(Long tid, Long memberId, String bindingIntent,
                                            String targetCityCode) {
        // 全量复制(章节/节点/协作/票种)+ 归属改为明确作用域会员 → 生成草稿
        Map<String, Object> params = new HashMap<>();
        params.put("copyType", 3);
        params.put("memberId", memberId);
        if (bindingIntent != null && !bindingIntent.trim().isEmpty()) params.put("bindingIntent", bindingIntent);
        if (targetCityCode != null && !targetCityCode.trim().isEmpty()) params.put("targetCityCode", targetCityCode);
        Long newTopicId;
        try {
            newTopicId = cmsTopicService.copyCmsTopic(tid, params);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
        Map<String, Object> data = new HashMap<>();
        data.put("topicId", newTopicId);
        // 增量读回（保持 data.topicId 原键）：来源血缘、采用方式与待重绑清单，供采用流程直接展示。
        CmsTopic created = cmsTopicService.selectCmsTopicById(newTopicId);
        String contractJson = created == null ? null : created.getContentContractJson();
        Map<String, Object> summary = com.chengyinhub.business.service.support.TopicLocationContract.summary(contractJson);
        data.put("sourceTemplateTopicId", summary.get("sourceTemplateTopicId"));
        data.put("sourceConfigVersion", summary.get("sourceConfigVersion"));
        data.put("cityCode", created == null ? null : created.getCityCode());
        data.put("bindingIntent", summary.get("bindingIntent"));
        data.put("targetCityCode", summary.get("targetCityCode"));
        data.put("pendingRebindSlotKeys", summary.get("pendingRebindSlotKeys"));
        data.put("bindingIssues", com.chengyinhub.business.service.support.TopicLocationContract
                .bindingIssues(contractJson, created == null ? null : created.getCityCode()));
        return AjaxResult.success(data);
    }

    @Operation(summary = "作者绑定/解绑地点槽（目标城市选点）")
    @PostMapping("/binding")
    public AjaxResult bindLocation(String topicId, String scope, String slotKey, String nodeId,
                                   String approvedFallbackNodeIds, String openingHours,
                                   String observationNote, String referenceMediaIds,
                                   String expectedRevision) {
        Long principalMemberId;
        try {
            principalMemberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (principalMemberId == null || principalMemberId <= 0) {
            return error("请先登录");
        }
        Long ownerMemberId;
        try {
            ownerMemberId = resolveOwnerMemberId(principalMemberId, scope);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        com.chengyinhub.business.domain.dto.TopicLocationBindingCommand command =
                new com.chengyinhub.business.domain.dto.TopicLocationBindingCommand();
        command.setTopicId(Convert.toLong(topicId, 0L));
        command.setSlotKey(slotKey);
        Long parsedNodeId = Convert.toLong(nodeId, 0L);
        command.setNodeId(parsedNodeId != null && parsedNodeId > 0L ? parsedNodeId : null);
        command.setApprovedFallbackNodeIds(longList(approvedFallbackNodeIds));
        command.setReferenceMediaIds(stringList(referenceMediaIds));
        command.setOpeningHours(openingHours);
        command.setObservationNote(observationNote);
        command.setExpectedRevision(isBlank(expectedRevision) ? null : Convert.toInt(expectedRevision, null));
        try {
            return AjaxResult.success(locationBindingService.bind(command, ownerMemberId, principalMemberId));
        } catch (ServiceException e) {
            return e.getCode() == null ? error(e.getMessage()) : new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "地点合同与绑定状态（作者只读）")
    @PostMapping("/binding-status")
    public AjaxResult bindingStatus(String topicId, String scope) {
        Long principalMemberId;
        try {
            principalMemberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (principalMemberId == null || principalMemberId <= 0) {
            return error("请先登录");
        }
        Long ownerMemberId;
        try {
            ownerMemberId = resolveOwnerMemberId(principalMemberId, scope);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        try {
            return AjaxResult.success(locationBindingService.status(Convert.toLong(topicId, 0L), ownerMemberId));
        } catch (ServiceException e) {
            return e.getCode() == null ? error(e.getMessage()) : new AjaxResult(e.getCode(), e.getMessage());
        }
    }

    private Long resolveOwnerMemberId(Long principalMemberId, String scope) {
        if (MerchantOperationScope.isMerchant(scope)) {
            return merchantAccessService.require(
                    principalMemberId, MerchantPermission.PROJECT_MANAGE).getOwnerMemberId();
        }
        return principalMemberId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static java.util.List<Long> longList(String csv) {
        java.util.List<Long> ids = new java.util.ArrayList<Long>();
        if (isBlank(csv)) return ids;
        for (String part : csv.split(",")) {
            Long id = Convert.toLong(part.trim(), 0L);
            if (id != null && id > 0L) ids.add(id);
        }
        return ids;
    }

    private static java.util.List<String> stringList(String csv) {
        java.util.List<String> values = new java.util.ArrayList<String>();
        if (isBlank(csv)) return values;
        for (String part : csv.split(",")) {
            if (!part.trim().isEmpty()) values.add(part.trim());
        }
        return values;
    }

    @Operation(summary = "把我的已发布主题另存为模板")
    @PostMapping("/save-as")
    public AjaxResult saveAs(String id, String scope) {
        Long principalMemberId;
        try {
            principalMemberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (principalMemberId == null || principalMemberId <= 0) {
            return error("请先登录");
        }
        Long memberId = principalMemberId;
        try {
            if (MerchantOperationScope.isMerchant(scope)) {
                memberId = merchantAccessService.require(
                        principalMemberId, MerchantPermission.PROJECT_MANAGE).getOwnerMemberId();
            }
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        Long tid = Convert.toLong(id, 0L);
        if (tid < 1) {
            return error("缺少主题ID");
        }
        CmsTopic topic = cmsTopicService.selectCmsTopicById(tid);
        if (topic == null) {
            return error("主题不存在");
        }
        if (OwnershipUtils.notOwner(topic.getMemberId(), memberId)) {
            return error("只能把自己的主题另存为模板");
        }
        if (topic.getStatus() == null || topic.getStatus() != 1) {
            return error("仅审核通过的主题可另存为模板");
        }
        int rows = cmsTopicService.saveAsTemplate(tid, memberId, 1);
        return rows > 0 ? success("已另存为主题模板") : error("操作失败");
    }
}
