package com.chengyinhub.web.controller.business;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IRoamPoiApplicationService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;

/**
 * 漫游 POI 治理(主题运营控制台方案 §4.4)—— roam_poi 的后台入口。
 *
 * <p><b>为什么必须有</b>:商家可自助写入 POI 且 status=1 即对玩家可见({@code selectPoisNear}
 * 只筛 status),此前后台零入口 ⇒ 劣质/违规据点上线只能写 SQL(母档失明域点名)。
 *
 * <p>治理面 = 全量可见 + 上/下架;内容编辑仍在商家侧,这里刻意不做编辑表单
 * (后台代编商家资产会产生「谁写的内容谁负责」的归属混乱)。
 * 分层随 roam 域既有惯例(ApiRoamController 直用 RoamMapper,无 service 层)。
 */
@RestController
@RequestMapping("/business/roamPoi")
public class RoamPoiAdminController extends BaseController
{
    @Autowired
    private RoamMapper roamMapper;
    @Autowired
    private IRoamPoiApplicationService roamPoiApplicationService;
    @Autowired
    private ICmsMemberTemplateService memberTemplateService;
    @Autowired
    private IMmsMerchantService mmsMerchantService;

    /** 治理列表:全量含下架(status 可筛);商家 POI 连昵称。 */
    @PreAuthorize("@ss.hasPermi('business:roamPoi:list')")
    @GetMapping("/list")
    public AjaxResult list(Integer status, String cityCode, Long merchantId)
    {
        List<RoamPoi> pois = roamMapper.selectAdminPois(status, cityCode, merchantId);
        return AjaxResult.success(pois);
    }

    /** 上架(status=1,玩家侧立即可见)。 */
    @PreAuthorize("@ss.hasPermi('business:roamPoi:edit')")
    @Log(title = "漫游POI上架", businessType = BusinessType.UPDATE)
    @PutMapping("/online/{id}")
    public AjaxResult online(@PathVariable("id") Long id)
    {
        return toAjax(roamMapper.updatePoiStatus(id, 1));
    }

    /** 下架(status=0,玩家侧立即不可见;不删行,发现流水引用 poi_id)。 */
    @PreAuthorize("@ss.hasPermi('business:roamPoi:edit')")
    @Log(title = "漫游POI下架", businessType = BusinessType.UPDATE)
    @PutMapping("/offline/{id}")
    public AjaxResult offline(@PathVariable("id") Long id)
    {
        return toAjax(roamMapper.updatePoiStatus(id, 0));
    }

    /** 商家投放/认领申请：直接读取 roam_poi 审核态，仅在本治理页处置。 */
    @PreAuthorize("@ss.hasPermi('business:roamPoi:list')")
    @GetMapping("/applications")
    public AjaxResult applications(Integer auditStatus)
    {
        List<RoamPoi> applications = roamPoiApplicationService.listForReview(auditStatus);
        // 逐条装配审核材料。审核队列本来就短(只有待审那几条),这里刻意不做连表 ——
        // ponytail: 逐条查,队列长到几百条再改成连表。
        // 更要紧的是别把 correct_answer 这类字段塞进 RoamMapper.xml:那个文件里的查询
        // 同时服务玩家侧,选出答案就是泄题(已有契约钉死整份文件不得出现 correct_answer)。
        for (RoamPoi application : applications) {
            application.setReviewMaterial(buildReviewMaterial(application));
        }
        return AjaxResult.success(applications);
    }

    /**
     * 审核材料 = 商家是谁 + 他配了什么玩法。
     *
     * 审核要回答的是「这个玩法跑不跑得起来、题目合不合规」,只给一个模板 id 是答不了的。
     * 这里是后台面(@PreAuthorize business:roamPoi:list),给正确答案是必要的 ——
     * 审核员要能看出题目有没有唯一解;玩家侧投影(CityNodeVO)仍然一个字都不带。
     */
    private Map<String, Object> buildReviewMaterial(RoamPoi application)
    {
        Map<String, Object> material = new LinkedHashMap<>();
        if (application == null) return material;

        if (application.getApplicantMerchantId() != null) {
            MmsMerchant merchant = mmsMerchantService.selectMmsMerchantById(application.getApplicantMerchantId());
            if (merchant != null) {
                Map<String, Object> shop = new LinkedHashMap<>();
                shop.put("name", merchant.getName());
                shop.put("phone", merchant.getPhone());
                shop.put("address", merchant.getAddress());
                shop.put("categoryId", merchant.getCategoryId());
                shop.put("businessTime", merchant.getBusinessTime());
                // 入驻档位:审核员要知道这家是不是真的过审了(闸在提交侧,这里是复核)
                shop.put("status", merchant.getStatus());
                shop.put("accountStatus", merchant.getAccountStatus());
                material.put("merchant", shop);
            }
        }

        if (application.getTemplateId() != null && application.getTemplateId() > 0) {
            CmsMemberTemplate template = memberTemplateService
                    .selectCmsMemberTemplateById(application.getTemplateId());
            if (template != null) {
                Map<String, Object> play = new LinkedHashMap<>();
                play.put("title", template.getTitle());
                play.put("description", template.getDescription());
                play.put("interactionType", template.getInteractionType());
                play.put("validationMethod", template.getValidationMethod());
                play.put("questionName", template.getQuestionName());
                play.put("questionAnswer", template.getQuestionAnswer());
                play.put("questionA", template.getQuestionA());
                play.put("questionB", template.getQuestionB());
                play.put("questionC", template.getQuestionC());
                play.put("questionD", template.getQuestionD());
                play.put("correctAnswer", template.getCorrectAnswer());
                play.put("photoRequireDesc", template.getPhotoRequireDesc());
                play.put("hint1", template.getHint1());
                play.put("hint2", template.getHint2());
                play.put("couponId", template.getCouponId());
                play.put("feedbackText", template.getFeedbackText());
                play.put("storyJson", template.getStoryJson());
                play.put("audioUrl", template.getAudioUrl());
                material.put("template", play);
            }
        }
        return material;
    }

    @PreAuthorize("@ss.hasPermi('business:roamPoi:edit')")
    @Log(title = "漫游据点申请通过", businessType = BusinessType.UPDATE)
    @PutMapping("/applications/approve/{id}")
    public AjaxResult approve(@PathVariable("id") Long id)
    {
        try {
            return AjaxResult.success(roamPoiApplicationService.approve(id));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:roamPoi:edit')")
    @Log(title = "漫游据点申请驳回", businessType = BusinessType.UPDATE)
    @PutMapping("/applications/reject/{id}")
    public AjaxResult reject(@PathVariable("id") Long id, String reason)
    {
        try {
            return roamPoiApplicationService.reject(id, reason)
                    ? success("已驳回") : error("申请已被处置，请刷新后重试");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }
}
