package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P-16：人民广场的真实站点必须放行，只拒编辑器地图未动过的兜底中心。 */
class TopicNodeDefaultCoordinateTest {

    @Test
    void deliberatePeoplesSquareStationIsAccepted() {
        assertFalse(TopicBundleReviewService.isUntouchedEditorDefault(31.2304d, 121.4737d));
        assertFalse(TopicBundleReviewService.isUntouchedEditorDefault(31.230500d, 121.473800d));
    }

    @Test
    void untouchedEditorFallbackCenterIsRefused() {
        assertTrue(TopicBundleReviewService.isUntouchedEditorDefault(31.230416d, 121.473701d));
    }
}
