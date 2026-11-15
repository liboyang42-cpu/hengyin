package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.gamemech.DuelService;
import com.chengyinhub.business.gamemech.TreasureHuntService;
import com.chengyinhub.business.gamemech.WalkTestService;
import com.chengyinhub.business.locationtrust.LocationFix;
import com.chengyinhub.business.locationtrust.LocationSignals;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.common.exception.ErrorCode;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.function.Supplier;

/**
 * G2 game mechanics for the apps: treasure hunts (play + create), asynchronous duels and the walk-test records.
 * All new routes; nothing existing is renamed. No route here touches money.
 */
@RestController
@Tag(name = "G2 游戏机制")
public class ApiGameMechanicsController extends BaseController {

    @Autowired
    private TreasureHuntService treasure;
    @Autowired
    private DuelService duels;
    @Autowired
    private WalkTestService walkTests;
    @Autowired
    private ICmsTopicService topics;

    Long uid() {
        try { return getAppUserId(); } catch (Exception e) { return 0L; }
    }

    private AjaxResult run(Supplier<Object> body) {
        try {
            return success(body.get());
        } catch (ServiceException e) {
            Integer code = e.getCode();
            if (code != null && code == 401) return refuse(ErrorCode.SIGN_IN_REQUIRED, e.getMessage());
            if (code != null && code == 404) return refuse(ErrorCode.NOT_FOUND, e.getMessage());
            return error(e.getMessage());
        } catch (org.springframework.dao.DataAccessException e) {
            logger.error("game mechanics storage unavailable reason={}", e.getClass().getSimpleName());
            return error("这个玩法暂时还没开放，请稍后再试");
        }
    }

    // ------------------------------------------------------------------ treasure hunt (players)

    @Operation(summary = "进行中的寻宝")
    @GetMapping("/api/treasure/list")
    public AjaxResult treasureList(String cityCode) {
        return run(() -> treasure.liveList(cityCode, uid()));
    }

    @Operation(summary = "寻宝详情(线索与大致范围,不含精确位置)")
    @GetMapping("/api/treasure/{id}")
    public AjaxResult treasureDetail(@PathVariable("id") Long id) {
        return run(() -> treasure.playerDetail(id, uid()));
    }

    @Operation(summary = "在当前位置寻找宝藏(定位可信)")
    @PostMapping("/api/treasure/{id}/find")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    public AjaxResult treasureFind(@PathVariable("id") Long id, Double lat, Double lng) {
        Long memberId = uid();
        if (memberId == null || memberId <= 0) return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录");
        if (lat == null || lng == null) return refuse(ErrorCode.BAD_REQUEST, "请先打开定位");
        LocationFix fix = LocationSignals.current(lat, lng);
        return run(() -> treasure.find(id, memberId, fix));
    }

    // ------------------------------------------------------------------ treasure hunt (creators)

    @Operation(summary = "我创作的寻宝")
    @GetMapping("/api/treasure/creator/mine")
    public AjaxResult treasureMine() {
        return run(() -> treasure.creatorList(uid()));
    }

    @Operation(summary = "我创作的一场寻宝(含宝藏位置)")
    @GetMapping("/api/treasure/creator/{id}")
    public AjaxResult treasureMineDetail(@PathVariable("id") Long id) {
        return run(() -> treasure.creatorDetail(uid(), id));
    }

    @Operation(summary = "保存寻宝草稿")
    @PostMapping("/api/treasure/creator")
    @RateLimiter(time = 60, count = 20, limitType = LimitType.MEMBER)
    public AjaxResult treasureSave(@RequestBody Map<String, Object> body) {
        return run(() -> treasure.creatorSave(uid(), body));
    }

    @Operation(summary = "提交寻宝审核")
    @PostMapping("/api/treasure/creator/{id}/submit")
    public AjaxResult treasureSubmit(@PathVariable("id") Long id) {
        return run(() -> treasure.creatorSubmit(uid(), id));
    }

    // ------------------------------------------------------------------ duel

    @Operation(summary = "向好友或俱乐部伙伴发起路线对决(无押注)")
    @PostMapping("/api/duel")
    @RateLimiter(time = 60, count = 10, limitType = LimitType.MEMBER)
    public AjaxResult duelCreate(Long opponentId, Long topicId) {
        return run(() -> duels.challenge(uid(), opponentId, topicId));
    }

    @Operation(summary = "接受对决")
    @PostMapping("/api/duel/{id}/accept")
    public AjaxResult duelAccept(@PathVariable("id") Long id) {
        return run(() -> duels.respond(uid(), id, true));
    }

    @Operation(summary = "婉拒对决")
    @PostMapping("/api/duel/{id}/decline")
    public AjaxResult duelDecline(@PathVariable("id") Long id) {
        return run(() -> duels.respond(uid(), id, false));
    }

    @Operation(summary = "我的对决")
    @GetMapping("/api/duel/mine")
    public AjaxResult duelMine() {
        return run(() -> duels.mine(uid()));
    }

    @Operation(summary = "对决详情(服务端读两边的完成记录)")
    @GetMapping("/api/duel/{id}")
    public AjaxResult duelDetail(@PathVariable("id") Long id) {
        return run(() -> duels.detail(uid(), id));
    }

    // ------------------------------------------------------------------ walk test

    private CmsTopic ownTopic(Long topicId) {
        if (topicId == null || topicId <= 0) throw new ServiceException("参数有误", 400);
        CmsTopic topic = topics.selectCmsTopicById(topicId);
        Long memberId = uid();
        if (memberId == null || memberId <= 0) throw new ServiceException("请先登录", 401);
        if (topic == null || !memberId.equals(topic.getMemberId())) throw new ServiceException("路线不存在", 404);
        return topic;
    }

    @Operation(summary = "试走门槛状态(桌面排练 + 实地试走)")
    @GetMapping("/api/creator/walk-test")
    public AjaxResult walkTestStatus(Long topicId) {
        return run(() -> walkTests.status(ownTopic(topicId).getId()));
    }

    @Operation(summary = "记录一次桌面排练")
    @PostMapping("/api/creator/walk-test/desk")
    @RateLimiter(time = 60, count = 10, limitType = LimitType.MEMBER)
    public AjaxResult walkTestDesk(Long topicId, Integer nodesChecked, String note) {
        return run(() -> walkTests.recordDesk(uid(), ownTopic(topicId), nodesChecked == null ? 0 : nodesChecked, note));
    }

    @Operation(summary = "在路线地点记录一次实地试走(定位可信)")
    @PostMapping("/api/creator/walk-test/onsite")
    @RateLimiter(time = 60, count = 10, limitType = LimitType.MEMBER)
    public AjaxResult walkTestOnsite(Long topicId, Double lat, Double lng, String note) {
        if (lat == null || lng == null) return refuse(ErrorCode.BAD_REQUEST, "请先打开定位");
        LocationFix fix = LocationSignals.current(lat, lng);
        return run(() -> walkTests.recordOnsite(uid(), ownTopic(topicId), fix, note));
    }
}
