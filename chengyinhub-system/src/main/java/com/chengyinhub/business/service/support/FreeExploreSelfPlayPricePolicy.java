package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.vo.PublicationProblem;
import com.chengyinhub.business.exception.TopicPublicationContentException;
import com.chengyinhub.common.constant.ProductType;

import java.math.BigDecimal;
import java.util.List;

/**
 * f-money3:自由探索主题不得带收费自玩票。
 *
 * <p>主题结算只认经典定向,场次结算只认场次票,自由探索上的自玩票款没有结算出口:
 * 开关全关时钱留在平台、俱乐部没有余额;卖家收款路由 + 分账开时分账单转人工
 * ({@code NO_LEDGER_COMPONENTS}),钱冻结在卖家二级商户。见 f-money2 RESULT 第五节 2。
 *
 * <p>业主默认:在发布 / 编辑 / 锁价 / 开售各写入口拒绝这个组合,不改存量数据。
 * 各入口只调用这一处判据,不各写一遍。
 */
public final class FreeExploreSelfPlayPricePolicy {

    public static final String CODE = PublicationProblem.CODE_FREE_EXPLORE_SELF_PLAY_PRICED;
    public static final String FIELD = "selfPlayPrice";
    public static final String MESSAGE = "自由探索主题不能设置收费自玩票，请改为给主题门票定价";

    private FreeExploreSelfPlayPricePolicy() {}

    /** 自玩票价是否非零(null 视为未设)。 */
    public static boolean nonZero(BigDecimal price) {
        return price != null && price.signum() != 0;
    }

    /** 自由探索 + 开自玩票 + 非零自玩价 ⇒ 违规。selfPlay 为 null 时按未开处理。 */
    public static boolean violates(Integer productType, Integer selfPlay, BigDecimal selfPlayPrice) {
        return Integer.valueOf(ProductType.FREE_EXPLORE).equals(productType)
                && Integer.valueOf(1).equals(selfPlay)
                && nonZero(selfPlayPrice);
    }

    /** 锁价入口没有 selfPlay 开关,传了非零自玩价即视为要开。 */
    public static boolean violatesPrice(Integer productType, BigDecimal selfPlayPrice) {
        return violates(productType, nonZero(selfPlayPrice) ? Integer.valueOf(1) : null, selfPlayPrice);
    }

    /** 发布检查用的一条问题(无违规时返回 null)。 */
    public static PublicationProblem problem(Integer productType, Integer selfPlay, BigDecimal selfPlayPrice) {
        return violates(productType, selfPlay, selfPlayPrice)
                ? PublicationProblem.at(CODE, null, FIELD, MESSAGE) : null;
    }

    /** 发布 / 编辑入口:违规即抛,带 issues[]{code,message,field}。 */
    public static void assertAllowed(Integer productType, Integer selfPlay, BigDecimal selfPlayPrice) {
        PublicationProblem problem = problem(productType, selfPlay, selfPlayPrice);
        if (problem != null) {
            throw new TopicPublicationContentException(MESSAGE, List.of(problem));
        }
    }
}
