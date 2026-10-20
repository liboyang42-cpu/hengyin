package com.chengyinhub.business.service.ai;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.dto.ai.AiTemplateFillReq;
import com.chengyinhub.business.domain.dto.ai.MerchantAuthoringFactsReference;
import com.chengyinhub.business.domain.vo.ai.AiTemplateFillResp;
import com.chengyinhub.business.service.IAiCreatorService;
import com.chengyinhub.common.exception.ServiceException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Reuses the existing creator/gateway/quota path; creates only a human-reviewable draft. */
@org.springframework.context.annotation.Profile("w05-merchant-authoring")
@Service
public class MerchantBoundTemplateDraftService {
    private final MerchantAuthoringFactsService facts;
    private final IAiCreatorService creator;
    private final MerchantAuthoringRecordService records;
    @Autowired
    public MerchantBoundTemplateDraftService(MerchantAuthoringFactsService facts, IAiCreatorService creator, MerchantAuthoringRecordService records) {
        this.facts = facts; this.creator = creator; this.records = records;
    }
    public Map<String, Object> generate(AiTemplateFillReq request, Long principal) {
        if (principal == null || principal <= 0) throw new ServiceException("请先登录", 401);
        if (request == null || request.getMerchantFacts() == null || request.getMerchantConstraints() == null) {
            throw new ServiceException("请先选择本店商品并确认店内规则", 400);
        }
        String role = creator.resolveRoleFor(principal);
        if (!"merchant".equals(role) && !"club".equals(role)) throw new ServiceException("当前身份暂不支持AI创作", 403);
        MerchantAuthoringFactsReference input = request.getMerchantFacts();
        MerchantAuthoringFactsReference captured = new MerchantAuthoringFactsReference();
        captured.setProductId(input.getProductId()); captured.setSkuId(input.getSkuId()); captured.setFactsHash(input.getFactsHash());
        Map<String, Object> verified = facts.requireCurrent(principal, captured);
        Map<String, Object> constraints = request.getMerchantConstraints().validatedSnapshot();
        String goal = request.getExtraNote();
        if (goal == null || goal.trim().isEmpty() || goal.length() > 2000) throw new ServiceException("请填写本次草稿目标", 400);
        Integer method = request.getValidationMethod();
        if (method == null || method < 0 || method > 5) throw new ServiceException("请选择已支持的验证方式", 400);
        if (method == 2 && !Boolean.TRUE.equals(constraints.get("filmingAllowed"))) {
            throw new ServiceException("店铺未允许拍摄，请选择其他验证方式", 400);
        }
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("verifiedStoreFacts", verified); prompt.put("humanConfirmedConstraints", constraints);
        prompt.put("humanGoal", goal.trim());
        prompt.put("authorityBoundary", "仅生成待人工确认的描述和店内游戏草稿；价格、库存、优惠、奖励及发布均未授权，不可承诺或执行；旧积分价格不可当现金报价。");
        // No request-supplied shop name/category/reward replaces server facts, and
        // no selected SKU object reaches a product/stock mutation service.
        AiTemplateFillReq clean = new AiTemplateFillReq();
        clean.setShopName((String) verified.get("shopName")); clean.setCategory(""); clean.setReward("");
        clean.setPlayStyle(""); clean.setValidationMethod(method); clean.setExtraNote(JSON.toJSONString(prompt));
        AiTemplateFillResp generated = creator.templateFill(clean, principal);
        Map<String, Object> current = facts.requireCurrent(principal, captured);
        if (!role.equals(creator.resolveRoleFor(principal))) throw new ServiceException("当前身份已变化，请重新生成草稿", 409);
        if (generated == null || generated.getTemplate() == null || generated.getTraceId() == null || generated.getTraceId().trim().isEmpty()) {
            throw new ServiceException("AI草稿响应不完整", 502);
        }
        if (!method.equals(generated.getTemplate().getValidationMethod())) {
            throw new ServiceException("AI草稿与人工选择的验证方式不一致，请重新生成", 502);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", generated.getTraceId()); result.put("template", generated.getTemplate());
        // These provenance fields are server-created, never copied from model JSON.
        result.put("merchantFacts", current); result.put("merchantConstraints", constraints);
        result.put("candidateId", records.record(principal, current, constraints, generated).getId());
        result.put("draftOnly", true); result.put("offerConfigured", false);
        return result;
    }
}
