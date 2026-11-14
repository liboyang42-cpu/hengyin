package com.chengyinhub.business.service.merchantauthoring;

import java.util.Set;

/**
 * Approved authoring rules. Every field stays null until a caller supplies it.
 * A null field refuses the action that needs it. This type is not a Spring bean.
 */
public final class MerchantAuthoringPolicy {
    private final String modelVersion;
    private final String promptVersion;
    private final Integer costLimit;
    private final Set<String> templateIds;
    private final Set<String> inStoreTemplateIds;
    private final Boolean merchantReview;
    private final Boolean themeReview;
    private final String visibility;
    private final String timezone;
    private final Set<String> branches;
    private final String environmentProvider;
    private final Set<String> weatherSafeBranches;

    public MerchantAuthoringPolicy(String modelVersion, String promptVersion, Integer costLimit,
            Set<String> templateIds, Set<String> inStoreTemplateIds, Boolean merchantReview,
            Boolean themeReview, String visibility, String timezone, Set<String> branches,
            String environmentProvider, Set<String> weatherSafeBranches) {
        this.modelVersion = modelVersion;
        this.promptVersion = promptVersion;
        this.costLimit = costLimit;
        this.templateIds = templateIds;
        this.inStoreTemplateIds = inStoreTemplateIds;
        this.merchantReview = merchantReview;
        this.themeReview = themeReview;
        this.visibility = visibility;
        this.timezone = timezone;
        this.branches = branches;
        this.environmentProvider = environmentProvider;
        this.weatherSafeBranches = weatherSafeBranches;
    }

    public String getModelVersion() { return modelVersion; }
    public String getPromptVersion() { return promptVersion; }
    public Integer getCostLimit() { return costLimit; }
    public Set<String> getTemplateIds() { return templateIds; }
    public Set<String> getInStoreTemplateIds() { return inStoreTemplateIds; }
    public Boolean getMerchantReview() { return merchantReview; }
    public Boolean getThemeReview() { return themeReview; }
    public String getVisibility() { return visibility; }
    public String getTimezone() { return timezone; }
    public Set<String> getBranches() { return branches; }
    public String getEnvironmentProvider() { return environmentProvider; }
    public Set<String> getWeatherSafeBranches() { return weatherSafeBranches; }
}
