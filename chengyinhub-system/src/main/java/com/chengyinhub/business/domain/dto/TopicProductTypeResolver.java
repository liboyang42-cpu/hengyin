package com.chengyinhub.business.domain.dto;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.chengyinhub.common.constant.ProductType;

/**
 * 主题产品类型的唯一解析口径。
 *
 * <p>新版请求显式传 {@code productType} 时它是唯一真源；仅为兼容未升级的小程序，才回退读取票的
 * {@code mode}。控制器的权限门禁和服务层落库必须共用此处，避免同一请求被两套规则判成不同模式。</p>
 */
public final class TopicProductTypeResolver
{
    private TopicProductTypeResolver()
    {
    }

    public static Integer resolve(TopicCreateDTO dto)
    {
        if (dto != null && dto.getProductType() != null) {
            if (!ProductType.isValid(dto.getProductType())) {
                throw new IllegalArgumentException("主题产品类型不合法:" + dto.getProductType());
            }
            return dto.getProductType();
        }
        List<ActivityTicketRequest> tickets = dto == null ? null : dto.getTickets();
        if (tickets == null || tickets.isEmpty())
        {
            return ProductType.ORIENTEERING;
        }
        Set<Integer> modes = ticketModes(tickets);
        if (modes.isEmpty())
        {
            return ProductType.ORIENTEERING;
        }
        if (modes.size() == 1)
        {
            return modes.iterator().next();
        }
        Integer first = firstNonNullMode(tickets);
        return ProductType.isValid(first) ? first : ProductType.ORIENTEERING;
    }

    public static boolean hasMixedTicketModes(TopicCreateDTO dto)
    {
        return dto != null && ticketModes(dto.getTickets()).size() > 1;
    }

    private static Set<Integer> ticketModes(List<ActivityTicketRequest> tickets)
    {
        Set<Integer> modes = new HashSet<>();
        if (tickets == null)
        {
            return modes;
        }
        for (ActivityTicketRequest ticket : tickets)
        {
            if (ticket != null && ticket.getMode() != null)
            {
                if (!ProductType.isValid(ticket.getMode())) {
                    throw new IllegalArgumentException("票产品类型不合法:" + ticket.getMode());
                }
                modes.add(ticket.getMode());
            }
        }
        return modes;
    }

    private static Integer firstNonNullMode(List<ActivityTicketRequest> tickets)
    {
        if (tickets == null)
        {
            return null;
        }
        for (ActivityTicketRequest ticket : tickets)
        {
            if (ticket != null && ticket.getMode() != null)
            {
                return ticket.getMode();
            }
        }
        return null;
    }
}
