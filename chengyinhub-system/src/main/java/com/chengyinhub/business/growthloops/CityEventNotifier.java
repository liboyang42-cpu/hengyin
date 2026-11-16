package com.chengyinhub.business.growthloops;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.push.MemberNotice;
import com.chengyinhub.business.push.PushMessage;
import com.chengyinhub.business.service.IPushFanoutService;

/** {@link ICityEventNotifyService} 的 APNs 实现。 */
@Service
public class CityEventNotifier implements ICityEventNotifyService
{
    static final String PAGE = "/pages/roam/index";
    static final long TTL = 24L * 3600L;
    private static final int MAX_REF = 40;

    @Autowired(required = false)
    private IPushFanoutService push;

    /** 每周产出：同一 (会员, 周) 本实例只推一次。 */
    private final java.util.Set<String> yieldSent = ConcurrentHashMap.newKeySet();

    public CityEventNotifier() {}

    CityEventNotifier(IPushFanoutService push) { this.push = push; }

    @Override
    public void challenged(long holderMemberId, String placeRef, String placeName)
    {
        String place = place(placeName);
        send(holderMemberId, new MemberNotice(PushMessage.KIND_CITY, thread(placeRef), PAGE,
                "你的据点正被挑战", place.isEmpty() ? "回去守住它。" : "「" + place + "」有人来抢了，回去守住它。",
                "Your place is being challenged", place.isEmpty() ? "Go back and hold it." : place + " is under challenge. Go back and hold it.",
                TTL));
    }

    @Override
    public void lost(long formerHolderMemberId, String placeRef, String placeName)
    {
        String place = place(placeName);
        send(formerHolderMemberId, new MemberNotice(PushMessage.KIND_CITY, thread(placeRef), PAGE,
                "你失去了一个据点", place.isEmpty() ? "去把它拿回来。" : "「" + place + "」被别人占了，去把它拿回来。",
                "You lost a place", place.isEmpty() ? "Go and take it back." : place + " was taken. Go and take it back.",
                TTL));
    }

    @Override
    public void weeklyYield(long holderMemberId, String weekKey, int placesHeld, Long coins)
    {
        if (weekKey == null || weekKey.isBlank() || !yieldSent.add(holderMemberId + ":" + weekKey)) return;
        if (yieldSent.size() > 200_000) yieldSent.clear();
        String zh = coins == null ? "你守住了 " + placesHeld + " 个据点。" : "你守住了 " + placesHeld + " 个据点，得到 " + coins + " 城市币。";
        String en = coins == null ? "You held " + placesHeld + " places." : "You held " + placesHeld + " places and earned " + coins + " city coins.";
        send(holderMemberId, new MemberNotice(PushMessage.KIND_CITY, "city-yield", PAGE, "本周领地收成", zh, "This week's territory", en, 3 * TTL));
    }

    // ------------------------------------------------------------ W7 CITY-11 City Monopoly

    @Override
    public void monopolyLost(long formerHolderMemberId, String placeRef, String placeName, long revengeHours, double revengeRate)
    {
        String place = place(placeName);
        String zhPlace = place.isEmpty() ? "你的地点" : "「" + place + "」";
        String enPlace = place.isEmpty() ? "Your place" : place;
        boolean half = Math.abs(revengeRate - 0.5) < 1e-9;
        String zh;
        String en;
        if (revengeHours > 0 && revengeRate > 0) {
            zh = zhPlace + "被夺走了。" + revengeHours + " 小时内反击，挑战费" + (half ? "减半。" : "打折。");
            en = enPlace + " was taken. Strike back within " + revengeHours + " hours " + (half ? "for half the fee." : "at a lower fee.");
        } else {
            zh = zhPlace + "被夺走了，去把它拿回来。";
            en = enPlace + " was taken. Go and take it back.";
        }
        send(formerHolderMemberId, new MemberNotice(PushMessage.KIND_CITY, thread(placeRef), PAGE,
                "你失去了一个地点", zh, "You lost a place", en, TTL));
    }

    @Override
    public void defended(long holderMemberId, String placeRef, String placeName, int total, int dc)
    {
        String place = place(placeName);
        String zh = (place.isEmpty() ? "你的地点" : "「" + place + "」") + "守住了！对手掷出 " + total + "，没有达到 " + dc + "。";
        String en = (place.isEmpty() ? "Your place" : place) + " held! The challenger rolled " + total + ", short of " + dc + ".";
        send(holderMemberId, new MemberNotice(PushMessage.KIND_CITY, thread(placeRef), PAGE,
                "地点守住了", zh, "Place defended", en, TTL));
    }

    @Override
    public void decaying(long holderMemberId, String placeRef, String placeName, long days)
    {
        String place = place(placeName);
        String zh = (place.isEmpty() ? "你的地点" : "「" + place + "」") + "已经 " + days + " 天没人照看，快回去看看，不然会降级。";
        String en = "Nobody has looked after " + (place.isEmpty() ? "your place" : place) + " for " + days + " days. Visit soon or it loses a level.";
        send(holderMemberId, new MemberNotice(PushMessage.KIND_CITY, thread(placeRef), PAGE,
                "地点需要照看", zh, "Your place needs a visit", en, TTL));
    }

    @Override
    public void released(long formerHolderMemberId, String placeRef, String placeName)
    {
        String place = place(placeName);
        String zh = (place.isEmpty() ? "你的地点" : "「" + place + "」") + "因为太久没人照看，已重新开放。";
        String en = (place.isEmpty() ? "Your place" : place) + " was left alone too long and is open again.";
        send(formerHolderMemberId, new MemberNotice(PushMessage.KIND_CITY, thread(placeRef), PAGE,
                "地点已重新开放", zh, "Place open again", en, TTL));
    }

    @Override
    public void seasonResult(long memberId, String seasonId, int rank, int participants, int xp)
    {
        if (rank <= 0) return;
        String zh = "本赛季你排第 " + rank + " 名" + (participants > 0 ? "（共 " + participants + " 人）" : "")
                + (xp > 0 ? "，获得 " + xp + " 经验。" : "。") + "所有地点已重新开放。";
        String en = "This season you placed #" + rank + (participants > 0 ? " of " + participants : "")
                + (xp > 0 ? " and earned " + xp + " XP." : ".") + " All places are open again.";
        send(memberId, new MemberNotice(PushMessage.KIND_CITY, thread("season:" + (seasonId == null ? "" : seasonId)), PAGE,
                "赛季结束", zh, "Season over", en, 3 * TTL));
    }

    private void send(long member, MemberNotice notice)
    {
        if (push == null || member <= 0) return;
        try { push.notifyMemberAfterCommit(member, notice); }
        catch (RuntimeException ignored) { /* 通知是附带效果，绝不影响领地结算 */ }
    }

    static String thread(String placeRef)
    {
        if (placeRef == null || placeRef.isBlank()) return "city";
        String safe = placeRef.trim().replaceAll("[^A-Za-z0-9_.:-]", "");
        if (safe.length() > MAX_REF) safe = safe.substring(0, MAX_REF);
        return safe.isEmpty() ? "city" : "city-" + safe;
    }

    static String place(String name)
    {
        return WinbackPolicy.clip(name);
    }
}
