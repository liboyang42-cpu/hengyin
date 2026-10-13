package com.chengyinhub.business.support;

import java.math.BigDecimal;
import java.util.Date;

import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;

/**
 * 票务业务不变量的唯一校验点。
 *
 * <p>后台通用票务页和小程序主题/活动发布都能直接写 {@code oms_ticket}，因此不能只把
 * 校验放在其中一个 Controller 或 Service。这里不含权限/归属判断，只守所有创建入口共享的数据约束。
 */
public final class OmsTicketWriteValidator
{
    private OmsTicketWriteValidator()
    {
    }

    public static void validateNewTicket(OmsTicket ticket)
    {
        validatePrice(ticket.getPrice());
        validateTicketTimeWindow(ticket.getStartTime(), ticket.getEndTime());
        validateSaleTimeWindow(ticket.getSaleStartTime(), ticket.getSaleEndTime());
        validateTotalInventory(ticket.getTotalInventory());
    }

    public static void validatePrice(BigDecimal price)
    {
        if (price != null && price.compareTo(BigDecimal.ZERO) < 0)
        {
            throw new ServiceException("票务价格不能为负数", HttpStatus.BAD_REQUEST);
        }
    }

    public static void validateTicketTimeWindow(Date startTime, Date endTime)
    {
        if (startTime == null || endTime == null || !endTime.after(startTime))
        {
            throw new ServiceException("票务结束时间必须晚于开始时间", HttpStatus.BAD_REQUEST);
        }
    }

    public static void validateSaleTimeWindow(Date saleStartTime, Date saleEndTime)
    {
        // 售票日期可不填，也允许同一天售票；同时有值时不能倒序。
        if (saleStartTime != null && saleEndTime != null && saleEndTime.before(saleStartTime))
        {
            throw new ServiceException("售票结束日期不能早于开始日期", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * CU-C-48:票种时间 = 场次时间,必须落在所属活动的 start_date–end_date 内。
     *
     * <p>票的 startTime/endTime 与活动的 startDate/endDate 本是两组字段，写口原来只校验
     * {@code end > start}，于是能造出「活动顶部 9.24、票种 10.1/10.2、报名截止晚于活动结束」
     * 的自相矛盾数据 —— 用户看到的是两个互相打架的日期。
     *
     * <p>活动窗口为 null 时不拦：缺日期属另一类数据缺失，不能因为判不了就把票务全拒。
     */
    public static void validateTicketWindowWithinActivity(Date ticketStartTime, Date ticketEndTime,
                                                          Date activityStartDate, Date activityEndDate)
    {
        if (activityStartDate == null || activityEndDate == null)
        {
            return;
        }
        if (ticketStartTime != null && ticketStartTime.before(activityStartDate))
        {
            throw new ServiceException("票种开始时间不能早于活动开始时间", HttpStatus.BAD_REQUEST);
        }
        if (ticketEndTime != null && ticketEndTime.after(activityEndDate))
        {
            throw new ServiceException("票种结束时间不能晚于活动结束时间", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 改总容量时保持已占用量不变，同步折算可售余量。
     *
     * <p>后台改票与主题编辑整体重建都要算这一步：总容量不是「把 remaining 直接压到这个数」，
     * 否则已售席位会被复活（改大）或总量掉到余量之下把后续取消卡成永久 409（改小）。
     * 两条写路径必须同口径，否则改一处、另一处照旧算错。
     */
    public static Long remainingKeepingOccupiedSeats(OmsTicket existing, Long requestedTotalInventory)
    {
        Long existingTotal = existing.getTotalInventory();
        Long existingRemaining = existing.getRemainingInventory();
        if (existingTotal == null || existingRemaining == null || existingTotal < 0
                || existingRemaining < 0 || existingRemaining > existingTotal)
        {
            throw new ServiceException("票务库存数据异常，请先核对后再调整", HttpStatus.CONFLICT);
        }
        long occupied = existingTotal - existingRemaining;
        if (requestedTotalInventory == null || requestedTotalInventory < occupied)
        {
            throw new ServiceException("票务总库存不能低于当前已占用库存", HttpStatus.BAD_REQUEST);
        }
        return requestedTotalInventory - occupied;
    }

    public static void validateTotalInventory(Long totalInventory)
    {
        if (totalInventory == null || totalInventory < 0)
        {
            throw new ServiceException("票务总库存不能为负数", HttpStatus.BAD_REQUEST);
        }
    }
}
