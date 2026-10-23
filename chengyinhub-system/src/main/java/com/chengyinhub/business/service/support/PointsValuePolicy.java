package com.chengyinhub.business.service.support;

import com.chengyinhub.common.constant.CmsConstants;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.system.service.ISysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 积分价值的唯一服务端真源(owner 2026-10-23 拍板 D1 / 审计 P0-01)。
 *
 * <p>报名结算与积分商城读同一个汇率、同一个单笔上限:
 * <ul>
 *   <li>{@value #CONFIG_RATE}:1 积分 = 多少元,缺省 = 报名侧现行 {@link CmsConstants#POINT_DEDUCT_RATE}(0.02),
 *       所以落新 jar 后报名价格不变;</li>
 *   <li>{@value #CONFIG_MAX_PER_ORDER}:单笔订单积分最多抵多少元,缺省 = 报名侧现行
 *       {@link CmsConstants#MAX_POINT_DEDUCT_AMOUNT}(50.00)。</li>
 * </ul>
 * 键缺失、为空或不是正数时一律按缺省值走并记 error 日志 —— 配错不能把价格算成 0 或无上限。
 * 抵扣额只由服务端算;端上只展示服务端报价。
 */
@Component
public class PointsValuePolicy
{
    public static final String CONFIG_RATE = "points.deduct.rate_yuan_per_point";
    public static final String CONFIG_MAX_PER_ORDER = "points.deduct.max_yuan_per_order";

    public static final BigDecimal DEFAULT_RATE = CmsConstants.POINT_DEDUCT_RATE;
    public static final BigDecimal DEFAULT_MAX_PER_ORDER = CmsConstants.MAX_POINT_DEDUCT_AMOUNT;

    private static final Logger logger = LoggerFactory.getLogger(PointsValuePolicy.class);

    @Autowired
    private ISysConfigService configService;

    /** 非 Spring 构造(单测/手工装配)时用:不读配置,只给缺省值。 */
    public static PointsValuePolicy codeDefaults()
    {
        return new PointsValuePolicy();
    }

    /** 1 积分 = 多少元。 */
    public BigDecimal rate()
    {
        return positiveConfig(CONFIG_RATE, DEFAULT_RATE);
    }

    /** 单笔订单积分最多抵扣的金额(元)。 */
    public BigDecimal maxPerOrder()
    {
        return positiveConfig(CONFIG_MAX_PER_ORDER, DEFAULT_MAX_PER_ORDER);
    }

    /** 积分折合金额(元,两位小数)。 */
    public BigDecimal yuanOf(long points)
    {
        return BigDecimal.valueOf(points).multiply(rate()).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 积分商城下单:商品按积分标价,整单花费的积分折合金额不得超过单笔上限。
     * 只拦截,不改扣分数额;超出时给出可读的上限说明。
     */
    public void requireMallOrderWithinCap(long pointsCost)
    {
        BigDecimal rate = rate();
        BigDecimal cap = maxPerOrder();
        if (BigDecimal.valueOf(pointsCost).multiply(rate).compareTo(cap) > 0) {
            long maxPoints = cap.divide(rate, 0, RoundingMode.DOWN).longValue();
            throw new ServiceException("单笔订单最多使用 " + maxPoints + " 积分(折合 ¥"
                    + cap.setScale(2, RoundingMode.HALF_UP).toPlainString() + ")");
        }
    }

    private BigDecimal positiveConfig(String key, BigDecimal fallback)
    {
        if (configService == null) {
            return fallback;
        }
        String raw;
        try {
            raw = configService.selectConfigByKey(key);
        } catch (RuntimeException e) {
            logger.error("积分配置 {} 读取失败,按缺省 {} 处理", key, fallback.toPlainString(), e);
            return fallback;
        }
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            BigDecimal value = new BigDecimal(raw.trim());
            if (value.signum() > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // 落到下面的 error 日志
        }
        logger.error("积分配置 {}={} 不是正数,按缺省 {} 处理", key, raw, fallback.toPlainString());
        return fallback;
    }
}
