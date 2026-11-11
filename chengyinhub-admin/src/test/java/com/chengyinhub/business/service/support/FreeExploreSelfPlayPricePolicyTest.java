package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.vo.PublicationProblem;
import com.chengyinhub.business.exception.TopicPublicationContentException;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** f-money3:自由探索 + 非零自玩价没有结算出口,判据只有这一处。 */
class FreeExploreSelfPlayPricePolicyTest {

    private static final Integer FE = ProductType.FREE_EXPLORE;
    private static final Integer OR = ProductType.ORIENTEERING;

    @Test
    void onlyFreeExploreWithOpenSelfPlayAndNonZeroPriceViolates() {
        assertTrue(FreeExploreSelfPlayPricePolicy.violates(FE, 1, new BigDecimal("39.90")));
        assertTrue(FreeExploreSelfPlayPricePolicy.violates(FE, 1, new BigDecimal("-1")), "负价同样非零,失败关闭");
        assertFalse(FreeExploreSelfPlayPricePolicy.violates(FE, 1, BigDecimal.ZERO));
        assertFalse(FreeExploreSelfPlayPricePolicy.violates(FE, 1, new BigDecimal("0.00")));
        assertFalse(FreeExploreSelfPlayPricePolicy.violates(FE, 1, null));
        assertFalse(FreeExploreSelfPlayPricePolicy.violates(FE, 0, new BigDecimal("39.90")), "关着的自玩票不收钱");
        assertFalse(FreeExploreSelfPlayPricePolicy.violates(FE, null, new BigDecimal("39.90")));
        assertFalse(FreeExploreSelfPlayPricePolicy.violates(OR, 1, new BigDecimal("39.90")), "经典定向的自玩票有结算出口");
        assertFalse(FreeExploreSelfPlayPricePolicy.violates(null, 1, new BigDecimal("39.90")), "存量未定型不在本闸范围");
    }

    @Test
    void priceOnlyEntryTreatsANonZeroPriceAsOpeningSelfPlay() {
        assertTrue(FreeExploreSelfPlayPricePolicy.violatesPrice(FE, BigDecimal.TEN));
        assertFalse(FreeExploreSelfPlayPricePolicy.violatesPrice(FE, null));
        assertFalse(FreeExploreSelfPlayPricePolicy.violatesPrice(FE, BigDecimal.ZERO));
        assertFalse(FreeExploreSelfPlayPricePolicy.violatesPrice(OR, BigDecimal.TEN));
    }

    @Test
    void refusalCarriesIssueCodeFieldAndChineseMessage() {
        TopicPublicationContentException refused = assertThrows(TopicPublicationContentException.class,
                () -> FreeExploreSelfPlayPricePolicy.assertAllowed(FE, 1, new BigDecimal("39.90")));
        assertEquals("自由探索主题不能设置收费自玩票，请改为给主题门票定价", refused.getMessage());
        PublicationProblem issue = refused.getData().getIssues().get(0);
        assertEquals("FREE_EXPLORE_SELF_PLAY_PRICED", issue.getCode());
        assertEquals("selfPlayPrice", issue.getField());
        assertEquals(PublicationProblem.LEVEL_ERROR, issue.getLevel());
        assertEquals(refused.getMessage(), issue.getMessage(), "UserSafeMessage 不得把这句换成兜底文案");
        assertNull(FreeExploreSelfPlayPricePolicy.problem(OR, 1, BigDecimal.TEN));
        assertDoesNotThrow(() -> FreeExploreSelfPlayPricePolicy.assertAllowed(FE, 0, BigDecimal.TEN));
    }
}
