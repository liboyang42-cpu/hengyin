package com.chengyinhub.business.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.service.channel.platform.ChannelPlatformService;

/**
 * club-reach backfill: open clubs that have no channel rows get 闲聊 / 公告 / 活动.
 * Registered paused by migration_20261115_club_reach.sql; ops runs it once from the job console.
 * Invoke target: clubChannelDefaultsTask.backfill(200)
 */
@Component("clubChannelDefaultsTask")
public class ClubChannelDefaultsTask
{
    private static final Logger log = LoggerFactory.getLogger(ClubChannelDefaultsTask.class);

    @Autowired
    private ChannelPlatformService channels;

    public void backfill(Integer limit)
    {
        int seeded = channels.backfillClubDefaults(limit == null ? 200 : limit);
        log.info("club channel defaults backfill seeded {} clubs", seeded);
    }
}
