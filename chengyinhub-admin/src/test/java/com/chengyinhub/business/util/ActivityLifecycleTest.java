package com.chengyinhub.business.util;

import org.junit.jupiter.api.Test;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CU-M-106：一场活动在某一时刻的状态只有一个判定，「我的项目」列表与活动详情共用它。
 *
 * cms_activity.status 是审核码（0 待审 / 1 通过 / 2 未过），不是 official_event 那套
 * 0..9 生命周期码。把 1 当成「即将开始」查表，就会出现走查读到的：同一行在列表说「进行中」、
 * 在详情说「即将开始」。这里钉住的是「档期覆盖现在 = 进行中」这一条，以及审核/上架优先于时间。
 */
class ActivityLifecycleTest
{
    private static Date at(int daysFromNow, int hour)
    {
        Calendar c = Calendar.getInstance(TimeZone.getDefault());
        c.add(Calendar.DAY_OF_MONTH, daysFromNow);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }

    @Test
    void approvedAndShelvedActivityInsideItsWindowIsRunning()
    {
        String state = ActivityLifecycle.state(1L, 1L, at(-1, 9), at(5, 18));

        assertEquals("running", state, "审核通过 + 在架 + 档期覆盖现在，就是进行中");
        assertEquals("进行中", ActivityLifecycle.stateText(state));
    }

    @Test
    void windowEdgeStillDecidesBeforeAndAfter()
    {
        assertEquals("notStarted", ActivityLifecycle.state(1L, 1L, at(1, 9), at(5, 18)));
        assertEquals("未开始", ActivityLifecycle.stateText(ActivityLifecycle.state(1L, 1L, at(1, 9), at(5, 18))));
        assertEquals("completed", ActivityLifecycle.state(1L, 1L, at(-10, 9), at(-1, 18)));
        assertEquals("已完成", ActivityLifecycle.stateText(ActivityLifecycle.state(1L, 1L, at(-10, 9), at(-1, 18))));
    }

    /** 审核与上架状态必须压过时间：一个还没过的活动不因档期到了就被说成进行中。 */
    @Test
    void auditAndShelfGateTheTimeBucket()
    {
        Date past = at(-1, 9);
        Date future = at(5, 18);
        assertEquals("pending", ActivityLifecycle.state(0L, 1L, past, future), "status=0 是待审，不是草稿也不是进行中");
        assertEquals("审核中", ActivityLifecycle.stateText("pending"));
        assertEquals("rejected", ActivityLifecycle.state(2L, 1L, past, future), "status=2 是审核未过");
        assertEquals("未通过", ActivityLifecycle.stateText("rejected"));
        assertEquals("offline", ActivityLifecycle.state(1L, 0L, past, future));
        assertEquals("已下架", ActivityLifecycle.stateText("offline"));
    }

    /** 缺档期不等于「未开始」：没有日期可依据时按在架处理，与列表既有口径一致。 */
    @Test
    void missingWindowFallsBackToRunningNotToAPromise()
    {
        assertEquals("running", ActivityLifecycle.state(1L, 1L, null, null));
    }
}
