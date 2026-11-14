package com.chengyinhub.business.service.support.appattest;

/** The caller must atomically require and store a strict increase against its persisted counter. */
public final class AssertionResult
{
    private final long counter;
    private final String bundleVersion;
    private final long distributionCategory;

    AssertionResult(long counter, String bundleVersion, long distributionCategory)
    {
        this.counter = counter;
        this.bundleVersion = bundleVersion;
        this.distributionCategory = distributionCategory;
    }

    public long getCounter() { return counter; }
    public String getBundleVersion() { return bundleVersion; }
    public long getDistributionCategory() { return distributionCategory; }
}
