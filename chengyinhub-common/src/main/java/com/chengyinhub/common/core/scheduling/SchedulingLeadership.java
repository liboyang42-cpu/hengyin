package com.chengyinhub.common.core.scheduling;

/**
 * R9 BE-26: "is this instance the one that runs background work right now?"
 *
 * <p>Implemented by the quartz module's MySQL-session leader ({@code MySqlSchedulerCoordinator}). Quartz jobs and
 * coordinator plans already run only on the leader; Spring {@code @Scheduled} methods do not, so with two nodes each
 * would run them twice. Those methods call {@link #allows(SchedulingLeadership)} first.
 */
public interface SchedulingLeadership
{
    boolean isLeader();

    /** No leadership bean (unit tests, tools) keeps the old single-node behaviour: run. */
    static boolean allows(SchedulingLeadership leadership)
    {
        return leadership == null || leadership.isLeader();
    }
}
