package com.chengyinhub.web.config;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.mapper.TopicCouponRewardSupplyMapper;
import com.chengyinhub.business.service.coupon.TopicCouponRewardSupplyService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
/** Explicit, default-absent read profile. Wires the existing authority and real MyBatis mapper. */
@Configuration
@Profile("w17-topic-coupon-supply-read")
public class TopicCouponRewardSupplyReadConfiguration {
    @Bean public TopicCouponRewardSupplyService topicCouponRewardSupplyService(IMerchantAccessService authority,TopicCouponRewardSupplyMapper mapper) {
        return new TopicCouponRewardSupplyService(authority,mapper,Clock.systemUTC());
    }
}
