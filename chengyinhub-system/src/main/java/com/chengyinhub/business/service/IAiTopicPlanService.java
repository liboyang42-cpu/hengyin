package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.ai.AiMerchantPlanReq;
import com.chengyinhub.business.domain.vo.ai.AiMerchantPlanResp;

/**
 * AI 主题方案生成模块:筛选真实商家候选并生成可编辑方案。
 */
public interface IAiTopicPlanService
{
    AiMerchantPlanResp generate(AiMerchantPlanReq req, Long memberId);
}
