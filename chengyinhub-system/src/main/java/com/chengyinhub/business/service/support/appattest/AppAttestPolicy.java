package com.chengyinhub.business.service.support.appattest;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Explicit production App ID and extension allowlists. The prefix is the verified App ID
 * prefix, which is not necessarily the developer's Team ID. No default App ID or version.
 */
public final class AppAttestPolicy
{
    private final String appId;
    private final Set<Integer> allowedDistributionCategories;
    private final Set<String> allowedBundleVersions;

    public AppAttestPolicy(String appId, Set<Integer> allowedDistributionCategories,
                          Set<String> allowedBundleVersions)
    {
        if (appId == null || !appId.matches("[A-Za-z0-9]{10}\\.[A-Za-z0-9][A-Za-z0-9.-]{0,243}")
                || allowedDistributionCategories == null || allowedDistributionCategories.isEmpty()
                || allowedDistributionCategories.size() > 3 || allowedBundleVersions == null
                || allowedBundleVersions.isEmpty() || allowedBundleVersions.size() > 64)
        {
            throw new AppAttestVerificationException("INVALID_CONFIGURATION");
        }
        for (Integer category : allowedDistributionCategories)
        {
            // Production iOS distribution: TestFlight, App Store, enterprise/ad hoc.
            if (category == null || (category != 2 && category != 4 && category != 5))
            {
                throw new AppAttestVerificationException("UNSUPPORTED_DISTRIBUTION_CATEGORY");
            }
        }
        for (String version : allowedBundleVersions)
        {
            if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}"))
            {
                throw new AppAttestVerificationException("INVALID_BUNDLE_VERSION_CONFIGURATION");
            }
        }
        this.appId = appId;
        this.allowedDistributionCategories = Collections.unmodifiableSet(new HashSet<>(allowedDistributionCategories));
        this.allowedBundleVersions = Collections.unmodifiableSet(new HashSet<>(allowedBundleVersions));
    }

    public String getAppId()
    {
        return appId;
    }

    void checkExtensions(long category, String bundleVersion)
    {
        if (category < 0 || category > 0xffffffffL
                || !allowedDistributionCategories.contains((int) category))
        {
            throw new AppAttestVerificationException("DISTRIBUTION_CATEGORY_NOT_ALLOWED");
        }
        if (!allowedBundleVersions.contains(bundleVersion))
        {
            throw new AppAttestVerificationException("BUNDLE_VERSION_NOT_ALLOWED");
        }
    }
}
