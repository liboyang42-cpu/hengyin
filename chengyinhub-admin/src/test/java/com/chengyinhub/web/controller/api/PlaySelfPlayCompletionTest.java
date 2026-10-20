package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * ⚠️ 负控已实测:去掉 advanceProgress 里 done 探针的 `setTopicId(...)`
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
        ReflectionTestUtils.setField(controller, "playerNodeProgressService", progressService);
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", nodeService);
        // [P0-17] 通关口径分流会调 resolveMode(查主题 product_type)与 perkLedgerService。
        // 本类测的是 ① / ② 的 done 探针收窄,主题返 null ⇒ resolveMode 兜底 LINEAR_ORIENTEERING
        // ⇒ 走 done>=total 老口径,正是本类要测的路径。
        ReflectionTestUtils.setField(controller, "cmsTopicService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.ICmsTopicService.class));
        ReflectionTestUtils.setField(controller, "perkLedgerService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IPerkLedgerService.class));
        // resolveMode 的 fallback 链会走到 firstTicketMode(查票 mode);返空 ⇒ 兜底 LINEAR_ORIENTEERING。
        ReflectionTestUtils.setField(controller, "omsTicketService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IOmsTicketService.class));
        ReflectionTestUtils.setField(controller, "pointsAwardService",
                mock(com.chengyinhub.business.service.IPointsAwardService.class));
        ReflectionTestUtils.setField(controller, "playerProgressionService",
                mock(com.chengyinhub.business.service.IPlayerProgressionService.class));
        ReflectionTestUtils.setField(controller, "growthService",
                mock(com.chengyinhub.business.service.IGrowthService.class));
        ReflectionTestUtils.setField(controller, "xpBudgetService",
                mock(com.chengyinhub.business.service.IXpBudgetService.class));
        ReflectionTestUtils.setField(controller, "rewardDropService",
                mock(com.chengyinhub.business.service.IRewardDropService.class));
        ReflectionTestUtils.setField(controller, "playerBadgeMapper",
                mock(com.chengyinhub.business.mapper.PlayerBadgeMapper.class));
        ReflectionTestUtils.setField(controller, "playAttemptLogMapper",
                mock(com.chengyinhub.business.mapper.PlayAttemptLogMapper.class));
        ReflectionTestUtils.setField(controller, "memberCollectibleMapper",
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
        ReflectionTestUtils.invokeMethod(controller, "advanceProgress", MEMBER, node(), actId, null);
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
     * 活动会话同样限定本人、主题与活动；运行时按这一三元组选局，不读入其它主题的完成行。
     * 兼容风险仍在：migration_m2_self_play.sql 的旧活动行 topic_id=0（另有 nullable 旧表形态）。
     * 本例只验证当前局隔离合同，不证明历史行已回填；归属恢复须另做审查，不能靠放宽查询发奖。
     */
    @Test
    void activitySession_doneProbeUsesExactOwnerTopicAndActivity() {
        advance(ACTIVITY);
        PlayerNodeProgress probe = lastDoneProbe();
        assertEquals(ACTIVITY, probe.getActivityId());
        assertEquals(MEMBER, probe.getMemberId());
        assertEquals(TOPIC, probe.getTopicId(), "活动完成计数同样绑定运行时的精确主题归属");
    }
}
