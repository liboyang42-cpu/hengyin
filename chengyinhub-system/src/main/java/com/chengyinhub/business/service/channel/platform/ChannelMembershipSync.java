package com.chengyinhub.business.service.channel.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;

import static com.chengyinhub.business.service.channel.platform.ChannelCapability.VIEW_CHANNEL;
import static com.chengyinhub.business.service.channel.platform.ChannelGuildAccess.toLong;

/**
 * W7 x-channel-reach (SOC-2): every member who can see a channel has a row in its conversation from the moment
 * they join the guild or the channel is created, so pushes, @mentions and unread counts reach people who never
 * opened the channel.
 * <ul>
 * <li>Rows are only an index (authorization stays live in {@link ChannelGuildAccess}); push still re-checks read access.</li>
 * <li>Inserts use INSERT IGNORE: a row the member already has (also a hidden one) is left as it is.
 *     New rows start read up to the latest message, so history does not show as unread.</li>
 * <li>Joins and new channels enrol after commit and never fail the caller; a failure is logged and the backfill
 *     job ({@code channelMembershipBackfillTask}) or the member's first open repairs it.</li>
 * <li>Leaving a guild deletes the member's rows in that guild's channels inside the caller's transaction.</li>
 * </ul>
 */
@Service
public class ChannelMembershipSync
{
    private static final Logger log = LoggerFactory.getLogger(ChannelMembershipSync.class);
    static final int MEMBER_PAGE = 500;
    static final int BACKFILL_MAX_PAGE = 500;

    private final ChannelPlatformMapper mapper;
    private final ChannelGuildAccess access;

    @Autowired(required = false)
    @Qualifier("threadPoolTaskExecutor")
    private Executor executor;

    @Autowired
    public ChannelMembershipSync(ChannelPlatformMapper mapper, ChannelGuildAccess access)
    {
        this.mapper = mapper;
        this.access = access;
    }

    // ------------------------------------------------------------------ hooks (never throw)

    /** A member joined a club (1) or team (3): enrol them after commit into the channels they can see. */
    public void memberJoinedAfterCommit(int guildKind, long guildId, long memberId)
    {
        if (guildId <= 0 || memberId <= 0) return;
        afterCommit(() -> enrollMember(guildKind, guildId, memberId), false);
    }

    /** A channel was created or its overwrites changed: enrol every guild member who can see it, after commit. */
    public void channelChangedAfterCommit(long channelId)
    {
        if (channelId <= 0) return;
        afterCommit(() ->
        {
            ChannelRow row = mapper.selectChannel(channelId);
            if (row != null) enrollChannel(row);
        }, true);
    }

    /** A member left or was removed: drop their channel rows in that guild (same transaction as the leave). */
    public void memberLeft(int guildKind, long guildId, long memberId)
    {
        if (guildId <= 0 || memberId <= 0) return;
        try
        {
            mapper.deleteGuildMemberships(guildKind, guildId, memberId);
        }
        catch (RuntimeException e)
        {
            // Push and reads re-check guild membership live, so a stale row never leaks content.
            log.warn("channel leave sync skipped guildKind={} guildId={} reason={}", guildKind, guildId, e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ work

    /** @return conversations the member was enrolled into (0 when they already had every row). */
    public int enrollMember(int guildKind, long guildId, long memberId)
    {
        if (access.standing(guildKind, guildId, memberId) == ChannelPermissionCalculator.GuildStanding.NONE) return 0;
        List<Long> visible = new ArrayList<>();
        for (ChannelRow row : mapper.selectGuildChannels(guildKind, guildId, false))
        {
            if (!enrollable(row)) continue;
            if (ChannelPermissionCalculator.has(access.effective(row, memberId), VIEW_CHANNEL)) visible.add(row.getId());
        }
        return visible.isEmpty() ? 0 : mapper.insertIgnoreMemberChannels(memberId, visible);
    }

    /** @return rows inserted for this channel. Members are read in pages; a channel without overwrites skips the per-member check. */
    public int enrollChannel(ChannelRow row)
    {
        if (!enrollable(row)) return 0;
        int guildKind = row.getGuildKind();
        long guildId = row.getGuildId();
        boolean open = mapper.selectOverwrites(row.getId()).isEmpty();
        long lastRead = mapper.selectLatestMessageId(row.getId());
        int inserted = 0;
        long after = 0;
        while (true)
        {
            List<Map<String, Object>> page = mapper.selectGuildMembers(guildKind, guildId, after, MEMBER_PAGE);
            if (page == null || page.isEmpty()) break;
            List<Long> ids = new ArrayList<>();
            for (Map<String, Object> m : page)
            {
                long member = toLong(m.get("memberId"));
                after = Math.max(after, member);
                if (member <= 0) continue;
                if (open || ChannelPermissionCalculator.has(access.effective(row, member), VIEW_CHANNEL)) ids.add(member);
            }
            if (!ids.isEmpty()) inserted += mapper.insertIgnoreMembers(row.getId(), ids, lastRead);
            if (page.size() < MEMBER_PAGE) break;
        }
        return inserted;
    }

    /**
     * One-off backfill for members who joined before enrolment existed: walks every live channel in pages of
     * {@code pageSize} and enrols each one. Idempotent. A channel that fails is logged and skipped.
     * @return rows inserted
     */
    public int backfill(int pageSize)
    {
        int size = Math.max(1, Math.min(pageSize, BACKFILL_MAX_PAGE));
        int inserted = 0;
        long after = 0;
        while (true)
        {
            List<ChannelRow> rows = mapper.selectLiveChannelsAfter(after, size);
            if (rows == null || rows.isEmpty()) break;
            for (ChannelRow row : rows)
            {
                after = Math.max(after, row.getId());
                try { inserted += enrollChannel(row); }
                catch (RuntimeException e) { log.warn("channel membership backfill skipped channelId={}", row.getId(), e); }
            }
            if (rows.size() < size) break;
        }
        return inserted;
    }

    static boolean enrollable(ChannelRow row)
    {
        return row != null && !row.isCategory() && row.getStatus() != null && row.getStatus() == 0
                && row.getGuildKind() != null && row.getGuildId() != null && row.getId() != null
                && (row.getDelFlag() == null || row.getDelFlag() == 0);
    }

    /** In a transaction: run after commit (rolled-back joins enrol nobody). Outside: run now. Never throws. */
    void afterCommit(Runnable task, boolean background)
    {
        Runnable guarded = () ->
        {
            try { task.run(); }
            catch (RuntimeException failure)
            {
                log.warn("channel membership sync failed reason={}", failure.getClass().getSimpleName(), failure);
            }
        };
        Runnable submit = () ->
        {
            if (background && executor != null)
            {
                try { executor.execute(guarded); return; }
                catch (RuntimeException rejected) { log.warn("channel membership sync ran inline: executor rejected the task"); }
            }
            guarded.run();
        };
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
            {
                @Override
                public void afterCommit() { submit.run(); }
            });
        }
        else submit.run();
    }
}
