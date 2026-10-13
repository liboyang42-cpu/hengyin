package com.chengyinhub.business.badge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuleEvaluatorTest {

    private final RuleEvaluator ev = new RuleEvaluator();

    @Test
    void parse_extractsAggregateAndThreshold() {
        BadgeRule r = ev.parse("{\"aggregate\":\"sum\",\"aggregate_field\":\"mileage\",\"threshold\":50}");
        assertEquals("sum", r.getAggregate());
        assertEquals("mileage", r.getAggregateField());
        assertEquals(50L, r.getThreshold());
    }

    @Test
    void isThresholdMet_true_whenValueReachesThreshold() {
        BadgeRule r = ev.parse("{\"aggregate\":\"exists\",\"threshold\":1}");
        assertTrue(ev.isThresholdMet(r, 1));
        assertTrue(ev.isThresholdMet(r, 5));
    }

    @Test
    void isThresholdMet_false_whenBelow() {
        BadgeRule r = ev.parse("{\"aggregate\":\"distinct\",\"aggregate_field\":\"topicId\",\"threshold\":10}");
        assertFalse(ev.isThresholdMet(r, 9));
    }

    @Test
    void parse_nullOrBlank_returnsSafeNeverAward() {
        assertFalse(ev.isThresholdMet(ev.parse(null), 999999));
        assertFalse(ev.isThresholdMet(ev.parse(""), 999999));
        assertFalse(ev.isThresholdMet(ev.parse("not-json"), 999999));
    }

    @Test
    void isThresholdMet_nullRule_returnsFalse() {
        assertFalse(ev.isThresholdMet(null, 100));
    }
}
