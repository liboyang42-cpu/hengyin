package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.ai.AiMerchantPlanReq;
import com.chengyinhub.business.domain.dto.ai.AiNodeGenReq;
import com.chengyinhub.business.domain.dto.ai.AiTemplateFillReq;
import com.chengyinhub.business.domain.dto.ai.AiClubDesignReq;
import com.chengyinhub.business.domain.dto.ai.AiMerchantTaskSuggestReq;
import com.chengyinhub.business.domain.dto.ai.AiSafetyPrecheckReq;
import com.chengyinhub.business.domain.dto.ai.AiThemeDraftReq;
import com.chengyinhub.business.domain.dto.ai.AiChapterOverviewReq;
import com.chengyinhub.business.domain.vo.ai.AiClubDesignResp;
import com.chengyinhub.business.domain.vo.ai.AiChapterOverviewResp;
import com.chengyinhub.business.domain.vo.ai.AiMerchantPlanResp;
import com.chengyinhub.business.domain.vo.ai.AiMerchantTaskSuggestResp;
import com.chengyinhub.business.domain.vo.ai.AiNodeGenResp;
import com.chengyinhub.business.domain.vo.ai.AiTemplateFillResp;
import com.chengyinhub.business.domain.vo.ai.AiSafetyPrecheckResp;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftResp;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftQuotaResp;

/**
 * AI 创作服务:组题 / 主题草稿 / 节点助手 / 安全预检。
 * 所有方法通过 AiGatewayService 调模型(不直连),控制器只返回结果不直接落库发布。
 *
 * @author chengyinhub
 */
public interface IAiCreatorService
{
    AiMerchantPlanResp merchantPlan(AiMerchantPlanReq req, Long memberId);
    AiThemeDraftResp themeDraft(AiThemeDraftReq req, Long memberId);
    /** Pure planning only; durable prepare owns and persists this original identity. */
    com.chengyinhub.business.service.ai.AiThemeDraftPlan planThemeDraft(Long memberId, String nonce);
    /** Only the committed durable claim winner may consume the persisted original plan. */
    AiThemeDraftResp themeDraftPrepared(AiThemeDraftReq req, Long memberId,
            com.chengyinhub.business.service.ai.AiThemeDraftPlan plan);
    AiThemeDraftQuotaResp themeDraftQuota(Long memberId);
    AiNodeGenResp nodeGenerate(AiNodeGenReq req, Long memberId);

    /** 升级1·AI 自动填表:店名/品类/奖励 → 玩法标题/规则/问题/剧情/配图提示(模板全字段)。 */
    AiTemplateFillResp templateFill(AiTemplateFillReq req, Long memberId);
    AiSafetyPrecheckResp safetyPrecheck(AiSafetyPrecheckReq req, Long memberId);

    /** 章节概述:为单个章节生成一段整体语境概述(只写章节概述字段)。 */
    AiChapterOverviewResp chapterOverview(AiChapterOverviewReq req, Long memberId);

    /**
     * M3 商家任务建议: 告诉商户他的店适合参与哪些主题/能变成什么任务节点。
     */
    AiMerchantTaskSuggestResp merchantTaskSuggest(AiMerchantTaskSuggestReq req, Long memberId);

    /**
     * M3 俱乐部活动策划: 一句话→完整主题方案+商家推荐+宣传文案。
     */
    AiClubDesignResp clubDesign(AiClubDesignReq req, Long memberId);

    /**
     * 商家店铺参谋:聚合事实包(JSON 字符串,只含聚合统计不含会员明细)→ 经营解读/适合人群/活动建议。
     * 解析失败或模型未就绪(mock)抛 AiServiceException,由调用方降级为「仅数据事实」。
     */
    com.chengyinhub.business.domain.vo.ai.AiMerchantInsightResp merchantInsight(String factsJson, Long memberId);

    /** 给控制器做身份门禁:按 memberId 解析当前角色字符串(复用后端 RoleService)。 */
    String resolveRoleFor(Long memberId);
}
