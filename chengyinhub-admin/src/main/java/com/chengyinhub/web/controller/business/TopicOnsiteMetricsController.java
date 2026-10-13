package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ITopicOnsiteMetricsService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/business/topicFunnel")
public class TopicOnsiteMetricsController extends BaseController {
    @Autowired
    private ITopicOnsiteMetricsService topicOnsiteMetricsService;
    @Autowired
    private ICmsTopicService topicService;
    @Autowired
    private ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;

    @PreAuthorize("@ss.hasPermi('business:topic:query')")
    @GetMapping("/{topicId}")
    public AjaxResult get(@PathVariable Long topicId) {
        if (!topicOperationsScopeAuthorizer.canViewTopic(topicId)) {
            return error("主题不存在或当前账号无操作范围");
        }
        CmsTopic topic = topicId == null ? null : topicService.selectCmsTopicById(topicId);
        if (topic == null) {
            return error("主题不存在");
        }
        if (!Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())) {
            return error("该主题不是探店日活动");
        }
        return success(topicOnsiteMetricsService.get(topicId));
    }
}
