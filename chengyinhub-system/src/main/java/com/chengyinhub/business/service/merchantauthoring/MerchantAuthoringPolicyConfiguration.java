package com.chengyinhub.business.service.merchantauthoring;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The approved authoring policy, from properties. Off by default ({@code chengyin.merchant.authoring.enabled=false}):
 * without this bean every action that needs a policy is refused with its CONFIG_* code. Only an environment that has
 * an owner-approved model, cost cap, template list, review switches and visibility turns it on (TEST does, with
 * fixture values). A blank property stays null, so that one action stays refused.
 */
@Configuration
@ConditionalOnProperty(name = "chengyin.merchant.authoring.enabled", havingValue = "true")
public class MerchantAuthoringPolicyConfiguration {
    @Bean
    public MerchantAuthoringPolicy merchantAuthoringPolicy(
            @Value("${chengyin.merchant.authoring.model-version:}") String modelVersion,
            @Value("${chengyin.merchant.authoring.prompt-version:}") String promptVersion,
            @Value("${chengyin.merchant.authoring.cost-limit:}") String costLimit,
            @Value("${chengyin.merchant.authoring.template-ids:}") String templateIds,
            @Value("${chengyin.merchant.authoring.in-store-template-ids:}") String inStoreTemplateIds,
            @Value("${chengyin.merchant.authoring.merchant-review:false}") boolean merchantReview,
            @Value("${chengyin.merchant.authoring.theme-review:false}") boolean themeReview,
            @Value("${chengyin.merchant.authoring.visibility:}") String visibility,
            @Value("${chengyin.merchant.authoring.timezone:}") String timezone,
            @Value("${chengyin.merchant.authoring.branches:}") String branches,
            @Value("${chengyin.merchant.authoring.environment-provider:}") String environmentProvider,
            @Value("${chengyin.merchant.authoring.weather-safe-branches:}") String weatherSafeBranches) {
        return new MerchantAuthoringPolicy(blank(modelVersion), blank(promptVersion),
                blank(costLimit) == null ? null : Integer.valueOf(costLimit.trim()),
                set(templateIds), set(inStoreTemplateIds), merchantReview, themeReview, blank(visibility), blank(timezone),
                set(branches), blank(environmentProvider), set(weatherSafeBranches));
    }

    static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static Set<String> set(String csv) {
        if (blank(csv) == null) return null;
        Set<String> out = new LinkedHashSet<>();
        Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(out::add);
        return out.isEmpty() ? null : out;
    }
}
