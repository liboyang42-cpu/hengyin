package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.business.domain.dto.PublishSafetyRequest.ReviewMode;
import com.chengyinhub.system.service.ISysConfigService;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 活动发布的审核模式(2026-10-20 分级审核,推翻 M0-6「活动一律先审后发」):
 * 活动按内容处理,默认先发后审;以下任一成立改为先审后发(PRE_REVIEW):
 *  ① 类目落在 sys_config['audit.activity.high_risk_categories'](逗号分隔的类目 id:未成年/医疗/金融/危险运动/涉水涉火);
 *  ② 发布主体是未通过入驻审核的商家;
 *  ③ 总闸 sys_config['audit.activity.pre_review_all']=true(灰度回滚阀,一键退回全量先审)。
 * 配置读取异常一律按 PRE_REVIEW,不 fail-open。
 */
@Component
public class ActivityReviewPolicy {
    public static final String CONFIG_HIGH_RISK_CATEGORIES = "audit.activity.high_risk_categories";
    public static final String CONFIG_PRE_REVIEW_ALL = "audit.activity.pre_review_all";

    /** 发布主体的审核相关视图(控制器从商家/俱乐部身份解析结果带入) */
    public record Publisher(boolean merchant, boolean merchantApproved) {}

    private static final Logger logger = LoggerFactory.getLogger(ActivityReviewPolicy.class);

    @Autowired
    private ISysConfigService configService;

    public ReviewMode modeOf(CmsActivityPublishRequest request, Publisher publisher) {
        if (request == null || publisher == null) {
            return ReviewMode.PRE_REVIEW;
        }
        if (publisher.merchant() && !publisher.merchantApproved()) {
            return ReviewMode.PRE_REVIEW;
        }
        try {
            String all = configService.selectConfigByKey(CONFIG_PRE_REVIEW_ALL);
            if (all != null && "true".equalsIgnoreCase(all.trim())) {
                return ReviewMode.PRE_REVIEW;
            }
            Set<String> highRisk = ids(configService.selectConfigByKey(CONFIG_HIGH_RISK_CATEGORIES));
            for (String id : ids(request.getCategoryIds())) {
                if (highRisk.contains(id)) {
                    return ReviewMode.PRE_REVIEW;
                }
            }
        } catch (Exception e) {
            logger.error("活动审核模式配置读取失败,按 PRE_REVIEW 处理", e);
            return ReviewMode.PRE_REVIEW;
        }
        return ReviewMode.POST_REVIEW;
    }

    /** 是否收费:任一票价 > 0 */
    public boolean hasCharge(CmsActivityPublishRequest request) {
        if (request == null || request.getTickets() == null) {
            return false;
        }
        for (ActivityTicketRequest ticket : request.getTickets()) {
            if (ticket != null && ticket.getPrice() != null && ticket.getPrice().signum() > 0) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> ids(String csv) {
        Set<String> ids = new HashSet<>();
        if (csv == null) {
            return ids;
        }
        for (String part : csv.split(",")) {
            String id = part.trim();
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }
}
