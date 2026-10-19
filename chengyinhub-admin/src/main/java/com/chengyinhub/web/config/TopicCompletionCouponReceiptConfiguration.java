package com.chengyinhub.web.config;
import com.chengyinhub.business.mapper.TopicCompletionCouponReceiptMapper;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.coupon.TopicCompletionCouponReceiptService;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;

/** Dormant source composition. Enabling requires separate schema and deployment review. */
@Configuration
@Profile("w17-topic-completion-receipts")
public class TopicCompletionCouponReceiptConfiguration {
    @Bean public TopicCompletionCouponReceiptService topicCompletionCouponReceiptService(
            TopicCompletionCouponReceiptMapper mapper, ISmsCouponHistoryService originalGrant, DataSource dataSource) {
        return new TopicCompletionCouponReceiptService(mapper,originalGrant,dataSource);
    }
}
