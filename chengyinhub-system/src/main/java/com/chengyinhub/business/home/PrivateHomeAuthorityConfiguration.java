package com.chengyinhub.business.home;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Absent unless explicitly enabled. Enabling it still fails closed until versioned values are present. */
@Configuration
public class PrivateHomeAuthorityConfiguration {
    @Bean
    @ConditionalOnProperty(name = "app.private-home.authority-enabled", havingValue = "true")
    public PrivateHomeTrustedEvidence privateHomeTrustedEvidence() {
        return new PrivateHomeFailClosedEvidence();
    }

    @Bean
    @ConditionalOnProperty(name = "app.private-home.authority-enabled", havingValue = "true")
    public PrivateHomeAuthority privateHomeAuthority(PrivateHomeLocationPolicy policy, PrivateHomeTrustedEvidence evidence) {
        return new PrivateHomeConfiguredAuthority(policy, evidence);
    }

    @Bean
    @ConditionalOnProperty(name = "app.private-home.authority-enabled", havingValue = "true")
    public PrivateHomeResources privateHomeResources(DataSource dataSource) {
        return new PrivateHomeResourceAccountAdapter(dataSource);
    }
}
