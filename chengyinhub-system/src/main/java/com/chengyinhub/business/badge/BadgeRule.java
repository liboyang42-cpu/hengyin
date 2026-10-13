package com.chengyinhub.business.badge;

/** unlock_rule_json 解析结果。P0 只用 aggregate + threshold。 */
public class BadgeRule {
    private String aggregate;
    private String aggregateField;
    private long threshold;

    public String getAggregate() { return aggregate; }
    public void setAggregate(String aggregate) { this.aggregate = aggregate; }
    public String getAggregateField() { return aggregateField; }
    public void setAggregateField(String aggregateField) { this.aggregateField = aggregateField; }
    public long getThreshold() { return threshold; }
    public void setThreshold(long threshold) { this.threshold = threshold; }
}
