package com.chengyinhub.web.controller.business;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

/**
 * 带队场次(主题运营控制台方案 §4.6)—— 「一场带队」的跨主题只读调证台。
 *
 * <p><b>模型说明(2026-07-21 复核)</b>:§4.6 成文时判「缺模型」,现码已有完整载体 ——
 * {@code cms_activity}(集合时间/地点/clubId)+ {@code club_lead_state}(责任领队/当前阶段/
 * 状态{1集合中 2行进 3已结算})+ {@code club_lead_checkin}(到场名册),xcx 领队侧在写。
 * 本台不建新表,只做「摊开来看」:场次列表 + 名册(no-show 对比)+ 流程经过的商家。
 *
 * <p><b>纯只读</b>(2026-07-21 拍板):领队指派/更换维持 xcx 侧自理,后台不抢写入权。
 * #152 的 topic/{id}/lead-fulfillment 是主题详情内的 per-topic 视图,本台是跨主题总控,互补。
 */
@RestController
@RequestMapping("/business/clubRun")
public class ClubRunController extends BaseController
{
    @Autowired
    private ClubLeadMapper clubLeadMapper;

    @Autowired
    private ICmsActivityService cmsActivityService;

    @Autowired
    private ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;

    /** 场次列表:state × activity × topic × club × 领队 + 报名/到场计数。筛 topicId/clubId/status,全可空。 */
    @PreAuthorize("@ss.hasPermi('business:clubRun:list')")
    @GetMapping("/list")
    public AjaxResult list(Long topicId, Long clubId, Integer status)
    {
        if (!canViewTopicOrGlobal(topicId)) {
            return error("当前账号无主题履约查看范围");
        }
        return AjaxResult.success(clubLeadMapper.selectRunList(topicId, clubId, status));
    }

    /**
     * 场次名册 + 流程经过的商家。
     *
     * <p>名册行 = 该场报名(owner_type=2 配对 owner_id,共享ID空间必须带 type)× 到场打卡;
     * no-show 判定(已付∧未核销∧未到场)在展示层,后端只给事实。
     * 流程商家 = 该主题各章节**生效**供给(生效两轴口径),按章节串起来 —— 「这团会经过哪几家、各自什么档」。
     */
    @PreAuthorize("@ss.hasPermi('business:clubRun:list')")
    @GetMapping("/manifest/{activityId}")
    public AjaxResult manifest(@PathVariable("activityId") Long activityId)
    {
        CmsActivity activity = cmsActivityService.selectCmsActivityById(activityId);
        if (activity == null || activity.getTopicId() == null
                || !topicOperationsScopeAuthorizer.canViewTopic(activity.getTopicId())) {
            return error("场次不存在或当前账号无主题查看范围");
        }
        List<Map<String, Object>> roster = clubLeadMapper.selectManifest(activityId);
        List<Map<String, Object>> merchants = clubLeadMapper.selectTopicMerchants(activity.getTopicId());

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("activityId", activityId);
        d.put("topicId", activity.getTopicId());
        d.put("roster", roster);
        d.put("merchants", merchants);
        return AjaxResult.success(d);
    }


    private boolean canViewTopicOrGlobal(Long topicId) {
        return topicId == null ? topicOperationsScopeAuthorizer.hasGlobalTopicScope()
                : topicOperationsScopeAuthorizer.canViewTopic(topicId);
    }
}
