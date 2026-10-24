package com.chengyinhub.business.service.ai;

import com.chengyinhub.business.mapper.AiQuotaReservationMapper;

/** Isolated CI negative control. Never merge or deploy this branch. */
public final class PrivateMapperBoundaryNegativeControl
{
    private final AiQuotaReservationMapper mapper;

    public PrivateMapperBoundaryNegativeControl(AiQuotaReservationMapper mapper)
    {
        this.mapper = mapper;
    }
}
