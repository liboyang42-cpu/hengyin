package com.chengyinhub.business.citygame;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 28-day season Monte Carlo (CITY-MONOPOLY-SPEC §2.12) driven by the production rule functions and approved defaults.
 * One currency (积分): sources are stipend, rent (after the owner daily cap), visitor reward, chance tasks and challenge
 * compensation; sinks are claims, upgrades, upkeep, challenge fees and cosmetics. Asserts that the earn/spend ratio and
 * the per-day / per-season money supply stay inside the spec's bounds.
 */
class CityMonopolySeasonSimulationTest {
    private static final CityMonopolyConfig C = CityMonopolyConfig.defaults();
    private static final int RUNS = 400;
    private static final int DAYS = 28;

    record Profile(String name, int activeDaysPerWeek, int checkinsPerDay, int chancePerDay, int targetHoldings,
                   double[] tierMix, int challengeFromDay, int challengeEveryDays, long cosmeticThreshold, double cosmeticRate) {}

    static final Profile CASUAL = new Profile("casual", 2, 4, 1, 2, new double[]{0.5, 0.5, 0, 0}, 99, 99, 600, 0.3);
    static final Profile REGULAR = new Profile("regular", 4, 8, 2, 6, new double[]{0.35, 0.4, 0.25, 0}, 3, 7, 500, 0.3);
    static final Profile HARDCORE = new Profile("hardcore", 7, 10, 2, 12, new double[]{0.05, 0.15, 0.4, 0.4}, 5, 1, 700, 0.5);

    record Result(double source, double sink, double maxDaySourceExStipend, double seasonSource, long endBalance) {}

    private static final class Prop {
        int tier;
        int level;
        long invested;
        int lastVisitDay;

        Prop(int tier, int day) {
            this.tier = tier;
            this.invested = CityMonopolyRules.claimCost(C, tier);
            this.lastVisitDay = day;
        }
    }

    static int poisson(Random r, double lambda) {
        double l = Math.exp(-lambda), p = 1;
        int k = 0;
        do { k++; p *= r.nextDouble(); } while (p > l);
        return k - 1;
    }

    static int pickTier(Random r, double[] mix) {
        double x = r.nextDouble(), acc = 0;
        for (int i = 0; i < mix.length; i++) { acc += mix[i]; if (x < acc) return i + 1; }
        return 1;
    }

    static LocalTime visitTime(Random r) {
        double x = r.nextDouble();
        if (x < 0.15) return LocalTime.of(8, 0);
        if (x < 0.60) return LocalTime.of(13, 0);
        if (x < 0.90) return LocalTime.of(19, 0);
        return LocalTime.of(22, 0);
    }

    static Result season(Profile p, double density, Random r) {
        long balance = 0;
        double source = 0, sink = 0, maxDay = 0;
        List<Prop> props = new ArrayList<>();
        boolean[] active = new boolean[DAYS + 1];
        for (int w = 0; w < 4; w++) {
            List<Integer> days = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7));
            java.util.Collections.shuffle(days, r);
            for (int i = 0; i < p.activeDaysPerWeek(); i++) active[w * 7 + days.get(i)] = true;
        }
        int lastActive = 0;
        for (int day = 1; day <= DAYS; day++) {
            double daySource = 0, daySink = 0;
            if (day == 1) { balance += C.integer("city.monopoly.season.stipend"); source += C.integer("city.monopoly.season.stipend"); }
            if (active[day]) lastActive = day;
            boolean rain = r.nextDouble() < 0.2;
            double event = day == 27 ? 1.5 : 1.0;
            // rent (others visit my properties), owner daily cap
            long rawToday = 0, rentToday = 0;
            for (Prop pr : new ArrayList<>(props)) {
                if (active[day]) pr.lastVisitDay = day;
                int idle = day - pr.lastVisitDay;
                CityMonopolyRules.Decay decay = CityMonopolyRules.decay(C, idle, 1);
                if (decay.state() == CityMonopolyRules.DecayState.RELEASED) { props.remove(pr); continue; }
                double lambda = C.num("city.monopoly.tiers.T" + pr.tier + ".expected_visits_launch") * density;
                int visits = Math.min(poisson(r, lambda), C.integer("city.monopoly.yield.per_property_daily_visits"));
                for (int v = 0; v < visits; v++) {
                    CityMonopolyRules.YieldQuote q = CityMonopolyRules.computeYield(C, new CityMonopolyRules.YieldInput(pr.tier, pr.level,
                            CityMonopolyRules.SetState.NONE, visitTime(r), rain ? CityMonopolyRules.Weather.LIGHT_RAIN : CityMonopolyRules.Weather.CLEAR,
                            event, 1.0, false, 0, decay.state() == CityMonopolyRules.DecayState.WEATHERED, false));
                    long granted = CityMonopolyRules.applyOwnerDailyCap(C, rawToday, q.raw(), 0).granted();
                    rawToday += q.raw();
                    rentToday += granted;
                }
            }
            daySource += rentToday;
            if (active[day]) {
                daySource += Math.min(p.checkinsPerDay(), C.integer("city.monopoly.yield.visitor_daily_yielding_checkins")) * C.num("city.monopoly.yield.visitor_reward");
                daySource += Math.min(p.chancePerDay(), C.integer("city.monopoly.chance.per_day")) * C.num("city.monopoly.chance.reward");
            }
            double nonComp = daySource;
            // being challenged
            for (Prop pr : new ArrayList<>(props)) {
                double pChallenged = new double[]{0.01, 0.02, 0.04, 0.06}[pr.tier - 1];
                if (r.nextDouble() >= pChallenged) continue;
                long fee = CityMonopolyRules.challengeFee(C, pr.invested, day >= 22, false);
                int dc = CityMonopolyRules.challengeDc(C, pr.level, day - lastActive <= 3, CityMonopolyRules.SetState.NONE, day - pr.lastVisitDay >= 7);
                boolean lost = r.nextDouble() < CityMonopolyRules.winProbability(C, dc, 2);
                daySource += CityMonopolyRules.feeSplit(C, fee, lost, false).defenderComp();
                if (lost) props.remove(pr);
            }
            balance += Math.round(daySource);
            source += daySource;
            maxDay = Math.max(maxDay, nonComp);
            // upkeep
            for (Prop pr : props) {
                long u = Math.min(balance, CityMonopolyRules.upkeep(C, pr.invested, 1));
                balance -= u;
                daySink += u;
            }
            if (active[day]) {
                int claims = 0;
                while (props.size() < Math.min(p.targetHoldings(), CityMonopolyRules.holdingLimit(C, 1))
                        && claims < C.integer("city.monopoly.claim.per_member_per_day")) {
                    int tier = pickTier(r, p.tierMix());
                    long cost = CityMonopolyRules.claimCost(C, tier);
                    if (balance < cost) break;
                    balance -= cost;
                    daySink += cost;
                    props.add(new Prop(tier, day));
                    claims++;
                }
                int ups = 0;
                for (Prop pr : props) {
                    if (ups >= C.integer("city.monopoly.upgrade.per_member_per_day") || pr.level >= 3) continue;
                    long cost = CityMonopolyRules.upgradeCost(C, pr.tier, pr.level);
                    if (balance < cost) continue;
                    balance -= cost;
                    daySink += cost;
                    pr.level++;
                    pr.invested += cost;
                    ups++;
                }
                if (day >= p.challengeFromDay() && (day - p.challengeFromDay()) % p.challengeEveryDays() == 0) {
                    int tier = pickTier(r, p.tierMix());
                    int lvl = r.nextInt(3);
                    long fee = CityMonopolyRules.challengeFee(C, CityMonopolyRules.investedAt(C, tier, lvl), day >= 22, false);
                    if (balance >= fee) {
                        balance -= fee;
                        daySink += fee;
                        int dc = CityMonopolyRules.challengeDc(C, lvl, r.nextBoolean(), CityMonopolyRules.SetState.NONE, false);
                        if (r.nextDouble() < CityMonopolyRules.winProbability(C, dc, 1) && props.size() < CityMonopolyRules.holdingLimit(C, 1)) {
                            Prop won = new Prop(tier, day);
                            won.level = CityMonopolyRules.levelAfterTakeover(C, lvl);
                            won.invested = CityMonopolyRules.investedAt(C, tier, won.level);
                            props.add(won);
                        }
                    }
                }
                if (balance > p.cosmeticThreshold()) {
                    long spend = Math.round((balance - p.cosmeticThreshold()) * p.cosmeticRate());
                    balance -= spend;
                    daySink += spend;
                }
            }
            sink += daySink;
        }
        return new Result(source, sink, maxDay, source, balance);
    }

    static double[] summarize(Profile p, double density) {
        Random r = new Random(20261115L + p.name().hashCode() + (long) (density * 1000));
        double src = 0, snk = 0, maxDay = 0, maxSeason = 0;
        long[] ends = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            Result res = season(p, density, r);
            src += res.source();
            snk += res.sink();
            maxDay = Math.max(maxDay, res.maxDaySourceExStipend());
            maxSeason = Math.max(maxSeason, res.seasonSource());
            ends[i] = res.endBalance();
        }
        Arrays.sort(ends);
        double mean = Arrays.stream(ends).average().orElse(0);
        return new double[]{src / RUNS / DAYS, snk / RUNS / DAYS, snk / src, maxDay, maxSeason, mean, ends[(int) (RUNS * 0.95)]};
    }

    @Test
    void earnSpendRatioAndMoneySupplyStayBounded() {
        long dailyHardBound = CityMonopolyRules.ownerDailyMax(C, 0)
                + C.integer("city.monopoly.yield.visitor_daily_yielding_checkins") * C.integer("city.monopoly.yield.visitor_reward")
                + C.integer("city.monopoly.chance.per_day") * C.integer("city.monopoly.chance.reward");
        assertEquals(350, dailyHardBound, "rent ≤ T+100, visitor ≤ 20, chance ≤ 30");
        long seasonBound = C.integer("city.monopoly.season.stipend") + DAYS * 450L;
        StringBuilder report = new StringBuilder("profile density source/day sink/day sink/source maxDayExComp maxSeason endMean endP95\n");
        for (double density : new double[]{1.0, 3.0}) {
            for (Profile p : List.of(CASUAL, REGULAR, HARDCORE)) {
                double[] s = summarize(p, density);
                report.append(String.format("%-8s x%.0f %8.1f %8.1f %6.2f %8.0f %8.0f %7.0f %7.0f%n", p.name(), density, s[0], s[1], s[2], s[3], s[4], s[5], s[6]));
                assertTrue(s[3] <= dailyHardBound, p.name() + " daily source (excl. stipend/comp) ≤ hard bound: " + s[3]);
                assertTrue(s[0] <= 450, p.name() + " mean daily source ≲ 450 (spec bound): " + s[0]);
                assertTrue(s[4] <= seasonBound, p.name() + " season supply ≤ 300 + 28 × 450: " + s[4]);
                assertTrue(s[2] >= 0.5 && s[2] <= 1.0, p.name() + " x" + density + " sink/source in [0.5, 1.0]: " + s[2]);
                assertTrue(s[1] <= s[0], "spend never exceeds earnings (no negative balances)");
            }
        }
        double[] hardcoreLaunch = summarize(HARDCORE, 1.0);
        assertTrue(hardcoreLaunch[2] >= 0.85, "hardcore players recycle almost everything at launch density: " + hardcoreLaunch[2]);
        assertTrue(hardcoreLaunch[6] <= C.num("city.monopoly.kpi_alerts.hardcore_p95_balance"), "hardcore P95 balance under KPI alert: " + hardcoreLaunch[6]);
        System.out.println(report);
    }

    @Test
    void ownerDailyCapIsTheBindingConstraintAtExtremeDensity() {
        Random r = new Random(7);
        Profile whale = new Profile("whale", 7, 10, 2, 12, new double[]{0, 0, 0, 1}, 99, 99, 1_000_000, 0);
        Result res = season(whale, 25.0, r);
        double rentPerDay = (res.source() - C.integer("city.monopoly.season.stipend")) / DAYS;
        assertTrue(rentPerDay <= CityMonopolyRules.ownerDailyMax(C, 0) + 20 + 30 + 400, "rent saturates at T+100: " + rentPerDay);
        assertTrue(res.maxDaySourceExStipend() <= 350);
    }
}
