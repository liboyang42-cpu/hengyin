package com.chengyinhub.business.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.service.channel.platform.ChannelMembershipSync;

/**
 * W7 x-channel-reach (SOC-2) backfill: guild members who joined before channel enrolment existed get a row in
 * every channel they can see, so pushes, @mentions and unread counts reach them.
 * Registered paused by migration_20261116_channel_membership_backfill.sql; ops runs it once from the job console.
 * Invoke target: channelMembershipBackfillTask.backfill(200)
 */
@Component("channelMembershipBackfillTask")
public class ChannelMembershipBackfillTask
{
    private static final Logger log = LoggerFactory.getLogger(ChannelMembershipBackfillTask.class);

    @Autowired
    private ChannelMembershipSync membership;

    public void backfill(Integer pageSize)
    {
        int inserted = membership.backfill(pageSize == null ? 200 : pageSize);
        log.info("channel membership backfill enrolled {} rows", inserted);
    }
}
