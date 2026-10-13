package com.chengyinhub.web.controller.business;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.service.IMerchantOfferService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;

/**
 * 商家三档承接审核台(主题运营控制台方案 §4.2)—— merchant_chapter_offer 的后台读写面。
 *
 * <p><b>为什么必须有</b>:offer 自 enroll 落库即生效(status=1),此前后台零可见零写入 ——
 * 商家报什么档、提什么条件、quota 用没用完,运营全靠 SQL;定档定参(BD 谈判结果)更无处落。
 *
 * <p><b>队列分组是主题 → 章节</b>,不是模板(7-04 A-09 点名的错误粒度):章节是三档口径真源,
 * 混档章节(章节改档后的存量快照)只有按章节聚合才看得出来。
 *
 * <p>写入面全部走 {@link IMerchantOfferService} 的闸(费率上限/权益门槛/二清边界/锁价冻结),
 * 本层不复制校验 —— 闸只放一层,两处维护必然漂移。
 */
@RestController
@RequestMapping("/business/chapterOffer")
public class MerchantChapterOfferController extends BaseController
{
    @Autowired
    private IMerchantOfferService merchantOfferService;

    @Autowired
    private ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;

    /**
     * 审核台队列:主题 → 章节 → 商家 offer。章节节点带口径三件套(档/费率上限/权益门槛),
     * 行内 offer 的 termsMode 是快照,与章节口径不一致的行由前端标出。
     */
    @PreAuthorize("@ss.hasPermi('business:chapterOffer:list')")
    @GetMapping("/queue")
    public AjaxResult queue(Long topicId, Integer status)
    {
        boolean allowed = topicId == null ? topicOperationsScopeAuthorizer.hasGlobalTopicScope()
                : topicOperationsScopeAuthorizer.canViewTopic(topicId);
        if (!allowed) {
            return error("当前账号无主题供给查看范围");
        }
        List<MerchantChapterOffer> offers = merchantOfferService.adminQueue(topicId, status);

        Map<Long, Map<String, Object>> topicMap = new LinkedHashMap<>();
        for (MerchantChapterOffer o : offers) {
            Map<String, Object> topicNode = topicMap.get(o.getTopicId());
            if (topicNode == null) {
                topicNode = new LinkedHashMap<>();
                topicNode.put("topicId", o.getTopicId());
                topicNode.put("topicName", o.getTopicName());
                topicNode.put("topicLifecycle", o.getTopicLifecycle());
                topicNode.put("chapters", new LinkedHashMap<Long, Map<String, Object>>());
                topicMap.put(o.getTopicId(), topicNode);
            }
            @SuppressWarnings("unchecked")
            Map<Long, Map<String, Object>> chapterMap = (Map<Long, Map<String, Object>>) topicNode.get("chapters");
            Map<String, Object> chapterNode = chapterMap.get(o.getChapterId());
            if (chapterNode == null) {
                chapterNode = new LinkedHashMap<>();
                chapterNode.put("chapterId", o.getChapterId());
                chapterNode.put("chapterName", o.getChapterName());
                chapterNode.put("termsMode", o.getChapterTermsMode());
                chapterNode.put("maxPerHeadFee", o.getChapterMaxPerHeadFee());
                chapterNode.put("perkMinValue", o.getChapterPerkMinValue());
                chapterNode.put("maxMerchant", o.getChapterMaxMerchant());
                chapterNode.put("offers", new ArrayList<MerchantChapterOffer>());
                chapterMap.put(o.getChapterId(), chapterNode);
            }
            @SuppressWarnings("unchecked")
            List<MerchantChapterOffer> rows = (List<MerchantChapterOffer>) chapterNode.get("offers");
            rows.add(o);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> topicNode : topicMap.values()) {
            @SuppressWarnings("unchecked")
            Map<Long, Map<String, Object>> chapterMap = (Map<Long, Map<String, Object>>) topicNode.get("chapters");
            topicNode.put("chapters", new ArrayList<>(chapterMap.values()));
            result.add(topicNode);
        }
        return AjaxResult.success(result);
    }

    /**
     * 定档定参(本台的灵魂):对一行 offer 调整条款档与参数,闸全在 service 层。
     */
    @PreAuthorize("@ss.hasPermi('business:chapterOffer:edit')")
    @Log(title = "商家三档定档定参", businessType = BusinessType.UPDATE)
    @PutMapping("/adjust")
    public AjaxResult adjust(@RequestBody MerchantChapterOffer body)
    {
        if (body == null) return error("参数为空");
        // 服务端接管的字段一律清掉,防前端越权塞值(同 offerEnroll 的纪律);
        // topic/chapter/merchant 由 id 反查既有行,不信前端传的
        body.setTopicId(null);
        body.setChapterId(null);
        body.setMerchantId(null);
        body.setQuotaUsed(null);
        body.setStatus(null);
        try {
            merchantOfferService.adjustTerms(body, getUsername());
            return AjaxResult.success("条款已更新");
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 停用一行供给(下架杆)。不删行:核销流水引用 offer_id。
     */
    @PreAuthorize("@ss.hasPermi('business:chapterOffer:edit')")
    @Log(title = "商家供给停用", businessType = BusinessType.UPDATE)
    @PutMapping("/deactivate/{id}")
    public AjaxResult deactivate(@PathVariable("id") Long id)
    {
        try {
            merchantOfferService.deactivate(id);
            return AjaxResult.success("已停用");
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }
}
