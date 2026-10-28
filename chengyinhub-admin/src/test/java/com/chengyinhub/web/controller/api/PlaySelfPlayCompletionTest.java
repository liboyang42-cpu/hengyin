package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自玩会话通关判定的 done 计数必须按 topicId 收窄。
 *
 * <p>病:自玩会话 {@code activity_id} 全部 = 0(不是每主题一个),所以按 (memberId, activityId=0)
 * 查进度会把该玩家**所有自玩主题**的打卡全算进 done。而 total 是按 topicId 数的 ⇒
 * done 被别的主题撑大 ⇒ 提前判 completed ⇒ **提前发通关积分 / 通关 XP / 通关勋章**。
 *
 * <p>本仓自己已经在 {@code nodes()} 里发现并修过这个坑(那里有一句注释:「自玩会话 activity_id
 * 全部=0,多主题共享同一探针会互相污染 done 计数,须按 topicId 再收窄」),但通关判定这一处漏了 ——
 * 而这一处才是发奖的地方。
 *
 * <p>不在施工图的清单里,是核对基线时顺带发现的。
 *
 * ⚠️ 负控已实测:去掉 advanceProgress 里的 `if (actId == null || actId == 0L) setTopicId(...)`
 *    ⇒ selfPlaySession_doneProbeNarrowedByTopic 红。
 */
class PlaySelfPlayCompletionTest {

    private static final Long MEMBER = 88L;
    private static final Long TOPIC = 300L;
    private static final Long NODE = 401L;
    private static final Long ACTIVITY = 500L;

    private IPlayerNodeProgressService progressService;
    private ICmsTopicNodeService nodeService;
    private ApiPlayProgressController controller;

    @BeforeEach
    void setUp() {
        progressService = mock(IPlayerNodeProgressService.class);
        nodeService = mock(ICmsTopicNodeService.class);

        controller = new ApiPlayProgressController();
        PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        // [P0-17] 通关口径分流会调 resolveMode(查主题 product_type)与 perkLedgerService。
        // 本类测的是 ① / ② 的 done 探针收窄,主题返 null ⇒ resolveMode 兜底 LINEAR_ORIENTEERING
        // ⇒ 走 done>=total 老口径,正是本类要测的路径。
        PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.ICmsTopicService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "perkLedgerService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IPerkLedgerService.class));
        // resolveMode 的 fallback 链会走到 firstTicketMode(查票 mode);返空 ⇒ 兜底 LINEAR_ORIENTEERING。
        PlayNodeAccessTestFixtures.setField(controller, "omsTicketService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IOmsTicketService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "pointsAwardService",
                mock(com.chengyinhub.business.service.IPointsAwardService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerProgressionService",
                mock(com.chengyinhub.business.service.IPlayerProgressionService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "growthService",
                mock(com.chengyinhub.business.service.IGrowthService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "xpBudgetService",
                mock(com.chengyinhub.business.service.IXpBudgetService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "rewardDropService",
                mock(com.chengyinhub.business.service.IRewardDropService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerBadgeMapper",
                mock(com.chengyinhub.business.mapper.PlayerBadgeMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playAttemptLogMapper",
                mock(com.chengyinhub.business.mapper.PlayAttemptLogMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "memberCollectibleMapper",
                mock(com.chengyinhub.business.mapper.MemberCollectibleMapper.class));

        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(new ArrayList<CmsTopicNode>());
        when(progressService.selectPlayerNodeProgressList(any()))
                .thenReturn(new ArrayList<PlayerNodeProgress>());
    }

    private CmsTopicNode node() {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(NODE);
        n.setTopicId(TOPIC);
        return n;
    }

    /** 捕获**通关判定那一处**的 done 探针(它是最后一个 selectPlayerNodeProgressList 调用)。 */
    private PlayerNodeProgress lastDoneProbe() {
        ArgumentCaptor<PlayerNodeProgress> captor = ArgumentCaptor.forClass(PlayerNodeProgress.class);
        verify(progressService, org.mockito.Mockito.atLeastOnce())
                .selectPlayerNodeProgressList(captor.capture());
        List<PlayerNodeProgress> all = captor.getAllValues();
        return all.get(all.size() - 1);
    }

    private void advance(Long actId) {
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.invokeMethod(controller, "advanceProgress", MEMBER, node(), actId, null);
    }

    /**
     * 自玩会话(actId=0)⇒ done 探针必须带 topicId,否则把别的自玩主题的进度也算进来。
     * 这一条挂了 = 玩家玩完 A 主题的 3 个点,再买 B 主题打 1 个点就「通关」B 并领奖。
     */
    @Test
    void selfPlaySession_doneProbeNarrowedByTopic() {
        advance(0L);
        assertEquals(TOPIC, lastDoneProbe().getTopicId(),
                "自玩会话 activity_id 恒为 0,不按 topicId 收窄就会跨主题污染 done ⇒ 提前发通关奖");
    }

    /** actId 为 null 同样按自玩处理。 */
    @Test
    void nullActivityId_alsoNarrowedByTopic() {
        advance(null);
        assertEquals(TOPIC, lastDoneProbe().getTopicId());
    }

    /**
     * 活动会话 ⇒ **不加** topicId 收窄。activityId 天然一对一绑定主题,加了是多余条件;
     * 且历史进度行的 topic_id 未必回填过,加了反而可能少算 done。
     */
    @Test
    void activitySession_doneProbeNotNarrowed() {
        advance(ACTIVITY);
        PlayerNodeProgress probe = lastDoneProbe();
        assertEquals(ACTIVITY, probe.getActivityId());
        assertNull(probe.getTopicId(), "活动会话 activityId 已唯一确定主题,不该再加 topicId 条件");
    }
}
