package com.chengyinhub.web.task;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.citygame.CityMonopolyService;

/**
 * CITY-6: City Monopoly overnight jobs (sys_job rows from migration_20261127_city_monopoly_jobs.sql).
 * <ul>
 *   <li>{@code cityMonopolyTask.settle()} 03:03: PENDING rent of earlier days becomes 积分, or VOID when flagged.</li>
 *   <li>{@code cityMonopolyTask.nightly()} 03:23: legacy conversion, upkeep, decay, release and abuse detection.</li>
 *   <li>{@code cityMonopolyTask.seasonSettle()} daily 09:07: settles the previous season once (skips a settled one).</li>
 * </ul>
 * Every step is idempotent per day / event / season (city_points_flow keys and the season status), so a re-run or
 * a second node firing the same row pays nothing twice.
 */
@Component("cityMonopolyTask")
public class CityMonopolyTask
{
    private static final Logger log = LoggerFactory.getLogger(CityMonopolyTask.class);

    @Autowired
    private CityMonopolyService cityMonopolyService;

    public void settle()
    {
        Map<String, Object> r = cityMonopolyService.settle();
        log.info("event=CITY_MONOPOLY_SETTLE settled={} voided={} credited={}", r.get("settled"), r.get("voided"), r.get("creditedPoints"));
    }

    public void nightly()
    {
        Map<String, Object> r = cityMonopolyService.nightly();
        log.info("event=CITY_MONOPOLY_NIGHTLY day={} legacyConverted={} upkept={} levelDrops={} released={} abuse={}",
                r.get("day"), r.get("legacyConverted"), r.get("upkept"), r.get("levelDrops"), r.get("released"), r.get("abuse"));
    }

    public void seasonSettle()
    {
        Map<String, Object> r = cityMonopolyService.settleDueSeason();
        log.info("event=CITY_MONOPOLY_SEASON_SETTLE result={}", r);
    }
}
