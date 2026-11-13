package com.chengyinhub.business.service.team.pool;

import com.chengyinhub.business.service.channel.platform.ChannelRejection;

/** 共享资源池的确定性拒绝（与频道合同同一 HTTP 外壳：status == code + errorCode）。 */
public class TeamPoolRejection extends ChannelRejection
{
    private static final long serialVersionUID = 1L;

    public TeamPoolRejection(int status, String errorCode, String message)
    {
        super(status, errorCode, message);
    }
}
