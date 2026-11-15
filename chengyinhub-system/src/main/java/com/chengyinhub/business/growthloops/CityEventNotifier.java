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
