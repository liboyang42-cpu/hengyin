package com.chengyinhub.business.gamemech;

import com.chengyinhub.business.gamemech.mapper.GameMechanicsMapper;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.friend.FriendService;
import com.chengyinhub.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * G2 / GAM-06 asynchronous duel: challenge a friend or a club mate on one journey. Each player plays on their own
 * time; the server reads both completions from {@code topic_route_session} (never from the client) and compares
 * the time from duel start (or run start, whichever is later) to the journey end. Faster wins; equal is a draw.
 * Both players get an in-app message when the duel is sent, accepted and decided. There is no stake and no money.
 */
@Service
public class DuelService {
    private static final Logger log = LoggerFactory.getLogger(DuelService.class);
    static final long TTL_MS = 7L * 24 * 3600 * 1000;
    static final int DAILY_LIMIT = 20;
    public static final String PENDING = "PENDING", ACTIVE = "ACTIVE", DECLINED = "DECLINED", DONE = "DONE", EXPIRED = "EXPIRED";

    private final GameMechanicsMapper mapper;
    private final FriendService friends;
    private final INotifyService notify;
    LongSupplier clock = System::currentTimeMillis;

    @Autowired
    public DuelService(GameMechanicsMapper mapper, FriendService friends, INotifyService notify) {
        this.mapper = mapper;
        this.friends = friends;
        this.notify = notify;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> challenge(Long challengerId, Long opponentId, Long topicId) {
        if (challengerId == null || challengerId <= 0) throw new ServiceException("请先登录", 401);
        if (opponentId == null || opponentId <= 0 || opponentId.equals(challengerId)) throw new ServiceException("请选择一位对手", 400);
        Map<String, Object> topic = topicId == null ? null : mapper.selectTopicBrief(topicId);
        if (topic == null || TreasureHuntService.asInt(topic.get("status")) != 1) throw new ServiceException("这条路线现在不能对决", 404);
        String relation = relation(challengerId, opponentId);
        if (relation == null) throw new ServiceException("只能向好友或同一俱乐部的伙伴发起对决", 403);
        if (mapper.countOpenDuels(challengerId, opponentId, topicId) > 0) throw new ServiceException("你们在这条路线上已有一场对决", 409);
        if (mapper.countDuelsSince(challengerId, new Date(clock.getAsLong() - 24L * 3600 * 1000)) >= DAILY_LIMIT) {
            throw new ServiceException("今天发起的对决已经够多了,明天再来", 429);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("topicId", topicId);
        row.put("challengerId", challengerId);
        row.put("opponentId", opponentId);
        row.put("relation", relation);
        row.put("expiresAt", new Date(clock.getAsLong() + TTL_MS));
        mapper.insertDuel(row);
        Long id = TreasureHuntService.asLong(row.get("id"));
        send(opponentId, id, nick(challengerId) + " 邀你在「" + topic.get("name") + "」上对决,7 天内走完比用时");
        return view(mapper.selectDuel(id), challengerId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> respond(Long memberId, Long duelId, boolean accept) {
        Map<String, Object> duel = requireParticipant(duelId, memberId);
        if (!memberId.equals(TreasureHuntService.asLong(duel.get("opponentId")))) throw new ServiceException("只有被邀请的一方可以回应", 403);
        if (!PENDING.equals(duel.get("status")) || expired(duel)) throw new ServiceException("这场对决已不能回应", 409);
        int version = TreasureHuntService.asInt(duel.get("version"));
        if (mapper.updateDuelStatus(duelId, accept ? ACTIVE : DECLINED, accept, version) != 1) {
            throw new ServiceException("对决状态已变化,请刷新", 409);
        }
        Long challenger = TreasureHuntService.asLong(duel.get("challengerId"));
        send(challenger, duelId, nick(memberId) + (accept ? " 接受了「" : " 婉拒了「") + duel.get("topicName") + "」的对决");
        return view(mapper.selectDuel(duelId), memberId);
    }

    public List<Map<String, Object>> mine(Long memberId) {
        if (memberId == null || memberId <= 0) throw new ServiceException("请先登录", 401);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> duel : mapper.selectMemberDuels(memberId)) out.add(view(settle(duel), memberId));
        return out;
    }

    public Map<String, Object> detail(Long memberId, Long duelId) {
        return view(settle(requireParticipant(duelId, memberId)), memberId);
    }

    /** Reads completions, writes them once, and decides the duel when both are in. Idempotent. */
    Map<String, Object> settle(Map<String, Object> duel) {
        String status = String.valueOf(duel.get("status"));
        if (PENDING.equals(status) && expired(duel)) {
            mapper.updateDuelStatus(TreasureHuntService.asLong(duel.get("id")), EXPIRED, false, TreasureHuntService.asInt(duel.get("version")));
            return mapper.selectDuel(TreasureHuntService.asLong(duel.get("id")));
        }
        if (!ACTIVE.equals(status)) return duel;
        Long id = TreasureHuntService.asLong(duel.get("id"));
        Long topicId = TreasureHuntService.asLong(duel.get("topicId"));
        Date start = TreasureHuntService.asDate(duel.get("acceptedAt"));
        if (start == null) return duel;
        Integer cs = intOrNull(duel.get("challengerSeconds")), os = intOrNull(duel.get("opponentSeconds"));
        Date cd = TreasureHuntService.asDate(duel.get("challengerDoneAt")), od = TreasureHuntService.asDate(duel.get("opponentDoneAt"));
        Date deadline = TreasureHuntService.asDate(duel.get("expiresAt"));
        if (cs == null) { Object[] r = completion(TreasureHuntService.asLong(duel.get("challengerId")), topicId, start, deadline); if (r != null) { cs = (Integer) r[0]; cd = (Date) r[1]; } }
        if (os == null) { Object[] r = completion(TreasureHuntService.asLong(duel.get("opponentId")), topicId, start, deadline); if (r != null) { os = (Integer) r[0]; od = (Date) r[1]; } }
        boolean changed = !java.util.Objects.equals(cs, intOrNull(duel.get("challengerSeconds")))
                || !java.util.Objects.equals(os, intOrNull(duel.get("opponentSeconds")));
        String result = null, next = ACTIVE;
        if (cs != null && os != null) {
            result = cs < os ? "CHALLENGER" : os < cs ? "OPPONENT" : "DRAW";
            next = DONE;
        } else if (deadline != null && clock.getAsLong() > deadline.getTime()) {
            next = EXPIRED;
            result = cs != null ? "CHALLENGER" : os != null ? "OPPONENT" : null;
        }
        if (!changed && ACTIVE.equals(next)) return duel;
        if (mapper.updateDuelResult(id, cs, cd, os, od, result, next, TreasureHuntService.asInt(duel.get("version"))) == 1
                && !ACTIVE.equals(next)) {
            String text = "「" + duel.get("topicName") + "」对决有结果了,打开看看谁更快";
            send(TreasureHuntService.asLong(duel.get("challengerId")), id, text);
            send(TreasureHuntService.asLong(duel.get("opponentId")), id, text);
        }
        return mapper.selectDuel(id);
    }

    private Object[] completion(Long memberId, Long topicId, Date start, Date deadline) {
        Map<String, Object> run = mapper.selectFirstCompletionSince(memberId, topicId, start);
        Date done = run == null ? null : TreasureHuntService.asDate(run.get("completedAt"));
        if (done == null) return null;
        if (deadline != null && done.getTime() > deadline.getTime()) return null;
        long from = Math.max(start.getTime(), TreasureHuntService.asDate(run.get("startedAt")) != null ? TreasureHuntService.asDate(run.get("startedAt")).getTime() : start.getTime());
        long seconds = Math.max(1L, (done.getTime() - from) / 1000L);
        return new Object[]{(int) Math.min(Integer.MAX_VALUE, seconds), done};
    }

    String relation(Long a, Long b) {
        try {
            Object state = friends.relationship(a, b).get("state");
            if (state instanceof Number n && n.intValue() == 1) return "FRIEND";
        } catch (RuntimeException e) {
            log.warn("duel friend check failed reason={}", e.getClass().getSimpleName());
        }
        return mapper.countSharedClubs(a, b) > 0 ? "CLUB" : null;
    }

    private Map<String, Object> requireParticipant(Long duelId, Long memberId) {
        if (memberId == null || memberId <= 0) throw new ServiceException("请先登录", 401);
        Map<String, Object> duel = duelId == null ? null : mapper.selectDuel(duelId);
        if (duel == null || !(memberId.equals(TreasureHuntService.asLong(duel.get("challengerId")))
                || memberId.equals(TreasureHuntService.asLong(duel.get("opponentId"))))) {
            throw new ServiceException("对决不存在", 404);
        }
        return duel;
    }

    private boolean expired(Map<String, Object> duel) {
        Date d = TreasureHuntService.asDate(duel.get("expiresAt"));
        return d != null && clock.getAsLong() > d.getTime();
    }

    private Map<String, Object> view(Map<String, Object> duel, Long viewer) {
        Map<String, Object> out = new LinkedHashMap<>();
        Long challenger = TreasureHuntService.asLong(duel.get("challengerId"));
        Long opponent = TreasureHuntService.asLong(duel.get("opponentId"));
        boolean mineIsChallenger = viewer.equals(challenger);
        out.put("id", TreasureHuntService.asLong(duel.get("id")));
        out.put("topicId", TreasureHuntService.asLong(duel.get("topicId")));
        out.put("topicName", duel.get("topicName"));
        out.put("relation", duel.get("relation"));
        out.put("status", duel.get("status"));
        out.put("role", mineIsChallenger ? "CHALLENGER" : "OPPONENT");
        Long peer = mineIsChallenger ? opponent : challenger;
        out.put("peerId", peer);
        out.put("peerName", nick(peer));
        out.put("createdAt", duel.get("createdAt"));
        out.put("acceptedAt", duel.get("acceptedAt"));
        out.put("expiresAt", duel.get("expiresAt"));
        out.put("mySeconds", duel.get(mineIsChallenger ? "challengerSeconds" : "opponentSeconds"));
        // The other side's time is shown only once the duel is decided, so nobody plays against a known target.
        boolean decided = DONE.equals(duel.get("status")) || EXPIRED.equals(duel.get("status"));
        out.put("peerFinished", duel.get(mineIsChallenger ? "opponentDoneAt" : "challengerDoneAt") != null);
        if (decided) out.put("peerSeconds", duel.get(mineIsChallenger ? "opponentSeconds" : "challengerSeconds"));
        String result = duel.get("result") == null ? null : String.valueOf(duel.get("result"));
        out.put("outcome", result == null ? null : "DRAW".equals(result) ? "DRAW"
                : (result.equals("CHALLENGER") == mineIsChallenger) ? "WON" : "LOST");
        out.put("canRespond", PENDING.equals(duel.get("status")) && !mineIsChallenger && !expired(duel));
        return out;
    }

    private String nick(Long memberId) {
        try {
            String n = memberId == null ? null : mapper.selectNickname(memberId);
            return n == null || n.isBlank() ? "一位玩家" : n;
        } catch (RuntimeException e) {
            return "一位玩家";
        }
    }

    private void send(Long memberId, Long duelId, String text) {
        try {
            if (notify != null) notify.notifyGameDuel(memberId, duelId, text);
        } catch (RuntimeException e) {
            log.warn("duel notify failed member={} duel={} reason={}", memberId, duelId, e.getClass().getSimpleName());
        }
    }

    private static Integer intOrNull(Object raw) {
        Long v = TreasureHuntService.asLong(raw);
        return v == null ? null : v.intValue();
    }
}
