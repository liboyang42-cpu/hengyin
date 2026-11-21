package com.chengyinhub.business.task;

import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.service.channel.platform.ChatErasureService;
import com.chengyinhub.common.core.scheduling.SchedulingLeadership;

/**
 * SOC-14: purges channels deleted more than the restore window ago (status 3, purge_at passed): their messages and
 * message side rows are deleted and the channel becomes status 4.
 * Registered paused by migration_20261128_channel_purge_job.sql; ops enables it from 系统监控 → 定时任务.
 * Invoke target: channelPurgeTask.purge(50)
 */
@Component("channelPurgeTask")
public class ChannelPurgeTask
{
    private static final Logger log = LoggerFactory.getLogger(ChannelPurgeTask.class);

    @Autowired(required = false)
    private SchedulingLeadership schedulingLeadership;

    @Autowired
    private ChatErasureService erasure;

    public void purge(Integer channelLimit)
    {
        if (!SchedulingLeadership.allows(schedulingLeadership)) return; // two nodes: leader only
        int purged = erasure.purgeDeletedChannels(channelLimit == null ? 50 : channelLimit, new Date());
        if (purged > 0) log.info("channel purge removed {} deleted channels", purged);
    }
}
