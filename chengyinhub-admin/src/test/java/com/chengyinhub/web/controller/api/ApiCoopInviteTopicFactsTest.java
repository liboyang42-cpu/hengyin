package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.service.ICmsTopicService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 邀约卡的主题信息现填(稿 261:277 · 261:349)。
 *
 * <p>coop_invite 表里只有 topic_id,而卡面上商家要看的是「哪条路线、什么时候、在哪」。
 * 补这一段之前,前端只能显示「主题 #123」—— 把一个内部 id 摆给商家看,
 * 他既认不出是哪条路线,也没法据此决定接不接。
 */
class ApiCoopInviteTopicFactsTest {

    private ApiCoopController controller;
    private ICmsTopicService topicService;

    @BeforeEach
    void setUp() {
        controller = new ApiCoopController();
        topicService = mock(ICmsTopicService.class);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
    }

    private static CoopInvite invite(Long topicId) {
        CoopInvite it = new CoopInvite();
        it.setTopicId(topicId);
        return it;
    }

    @SuppressWarnings("unchecked")
    private void fill(List<CoopInvite> sent, List<CoopInvite> received) {
        ReflectionTestUtils.invokeMethod(controller, "fillTopicFacts", sent, received);
    }

    @Test
    void 四个展示字段都填上() {
        CmsTopic topic = new CmsTopic();
        topic.setId(42L);
        topic.setName("外滩夜行路线");
        topic.setImgUrl("a.jpg;b.jpg");
        topic.setStartDate(new Date());
        topic.setAddressName("黄浦区");
        when(topicService.selectCmsTopicById(42L)).thenReturn(topic);

        CoopInvite it = invite(42L);
        fill(new ArrayList<>(java.util.Collections.singletonList(it)), new ArrayList<>());

        assertEquals("外滩夜行路线", it.getTopicName());
        assertEquals("a.jpg;b.jpg", it.getTopicImgUrl());
        assertEquals("黄浦区", it.getTopicAddressName());
        assertEquals(topic.getStartDate(), it.getTopicStartDate());
    }

    /**
     * ★ 这条是本次真正要防的东西:两条列表加起来可能有几十条邀约,
     * 但它们往往指向同几条路线。不按 topicId 去重就是 N+1,而这是一个**首屏接口**。
     */
    @Test
    void 同一个主题只查一次() {
        CmsTopic topic = new CmsTopic();
        topic.setId(42L);
        topic.setName("外滩夜行路线");
        when(topicService.selectCmsTopicById(42L)).thenReturn(topic);

        List<CoopInvite> sent = new ArrayList<>();
        List<CoopInvite> received = new ArrayList<>();
        for (int i = 0; i < 5; i++) sent.add(invite(42L));
        for (int i = 0; i < 5; i++) received.add(invite(42L));

        fill(sent, received);

        verify(topicService, times(1)).selectCmsTopicById(42L);
        for (CoopInvite it : sent) assertEquals("外滩夜行路线", it.getTopicName());
        for (CoopInvite it : received) assertEquals("外滩夜行路线", it.getTopicName());
    }

    /** 查不到的主题(已删/无权)保持字段为空 —— 与其编一个名字,不如让前端退回原样。 */
    @Test
    void 查不到的主题不编名字_而且不反复查() {
        when(topicService.selectCmsTopicById(99L)).thenReturn(null);

        List<CoopInvite> sent = new ArrayList<>();
        sent.add(invite(99L));
        sent.add(invite(99L));
        sent.add(invite(99L));

        fill(sent, new ArrayList<>());

        for (CoopInvite it : sent) assertNull(it.getTopicName());
        // 查不到也要记进缓存,否则同一个坏 id 会被反复查
        verify(topicService, times(1)).selectCmsTopicById(99L);
    }

    /** 没有 topicId 的邀约(游戏级)不该去查主题。 */
    @Test
    void 没有主题的邀约不查库() {
        List<CoopInvite> sent = new ArrayList<>();
        sent.add(invite(null));
        sent.add(invite(0L));

        fill(sent, new ArrayList<>());

        verify(topicService, times(0)).selectCmsTopicById(org.mockito.ArgumentMatchers.anyLong());
    }
}
