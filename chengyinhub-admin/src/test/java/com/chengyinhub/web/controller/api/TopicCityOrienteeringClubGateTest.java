package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.chengyinhub.business.service.IClubLeaderService;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 经典定向(城市定向)发布门禁的「不绑俱乐部」分支。
 *
 * <p>D3(2026-09-05 用户裁决「商家城市定向只能自己玩,不能邀约就好了」):商家可无俱乐部直发。
 * 在此之前商家被硬拒,而且那是一条**看不见的死路** —— 商家端「主题」入口不带 clubId 进发布页,
 * 而归属俱乐部选择器只在 myClubs(自己创建的俱乐部)非空时才渲染,商家通常一个都没有:
 * 界面上没有那个控件,发布时却回一句「请选择发起经典定向团的合作俱乐部」。</p>
 *
 * <p>★ 这里用「配额闸的错误文案」当放行证据:配额校验紧跟在俱乐部门禁之后,
 * 只有俱乐部这道闸放行了才走得到它。断言"没有返回俱乐部那句"是不够的 ——
 * 任何一个更早的 return 都能让那句话不出现。</p>
 */
class TopicCityOrienteeringClubGateTest
{
    private static final Long MEMBER_ID = 501L;
    private static final String CLUB_REQUIRED = "请选择发起经典定向团的合作俱乐部";
    /** 俱乐部门禁之后的下一道闸,用它证明"走过去了" */
    private static final String PASSED_MARKER = "主题配额已满";

    private TestableApiTopicController controller;
    private IClubLeaderService clubLeaderService;
    private IQuotaService quotaService;

    @BeforeEach
    void setUp()
    {
        controller = new TestableApiTopicController(MEMBER_ID);
        clubLeaderService = mock(IClubLeaderService.class);
        quotaService = mock(IQuotaService.class);
        when(quotaService.checkCreate(MEMBER_ID, IQuotaService.TYPE_THEME)).thenReturn(PASSED_MARKER);
        ReflectionTestUtils.setField(controller, "clubLeaderService", clubLeaderService);
        ReflectionTestUtils.setField(controller, "quotaService", quotaService);
    }

    /** productType=1 且不带 clubId —— 门禁的目标形状 */
    private static TopicCreateDTO soloCityOrienteering()
    {
        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setProductType(1);
        dto.setClubId(null);
        return dto;
    }

    private static ClubLeader activeLeader()
    {
        ClubLeader leader = new ClubLeader();
        leader.setStatus(1);
        return leader;
    }

    @Test
    void merchantCanPublishCityOrienteeringWithoutClub()
    {
        // 商家:club_leader 档里没有他
        when(clubLeaderService.selectClubLeaderByMemberId(MEMBER_ID)).thenReturn(null);

        AjaxResult result = controller.createTopic(soloCityOrienteering());

        assertNotEquals(CLUB_REQUIRED, result.get(AjaxResult.MSG_TAG),
                "商家自办城市定向不该再被要求绑俱乐部");
        assertEquals(PASSED_MARKER, result.get(AjaxResult.MSG_TAG),
                "必须真的走到俱乐部门禁之后那道闸,才算放行");
    }

    /** D2(2026-07-07)既有行为,不能被 D3 顺手改坏 */
    @Test
    void plainPlayerStillPublishesWithoutClub()
    {
        when(clubLeaderService.selectClubLeaderByMemberId(MEMBER_ID)).thenReturn(null);

        AjaxResult result = controller.createTopic(soloCityOrienteering());

        assertEquals(PASSED_MARKER, result.get(AjaxResult.MSG_TAG));
    }

    /**
     * 负控:主理人仍须绑自己的俱乐部。
     * 放开写成"谁都不用绑"的话,这条会红 —— 主理人本来就有俱乐部,不绑等于主题归属不明。
     */
    @Test
    void clubLeaderStillMustBindAClub()
    {
        when(clubLeaderService.selectClubLeaderByMemberId(MEMBER_ID)).thenReturn(activeLeader());

        AjaxResult result = controller.createTopic(soloCityOrienteering());

        assertEquals(CLUB_REQUIRED, result.get(AjaxResult.MSG_TAG));
    }

    private static class TestableApiTopicController extends ApiTopicController
    {
        private final Long memberId;

        private TestableApiTopicController(Long memberId)
        {
            this.memberId = memberId;
        }

        @Override
        public Long getAppUserId()
        {
            return memberId;
        }
    }
}
