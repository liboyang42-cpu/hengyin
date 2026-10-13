package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MerchantCircleSupplyProfile;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantCircleSupplyProfileMapper;
import com.chengyinhub.business.service.impl.CircleThemeSupplyServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 少于3家有效商户的城市实例必须对玩家自动隐藏。 */
class CircleThemeSupplyServiceTest {
    private CmsTopicMapper topicMapper;
    private MerchantCircleSupplyProfileMapper supplyMapper;
    private CircleThemeSupplyServiceImpl service;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        supplyMapper = mock(MerchantCircleSupplyProfileMapper.class);
        service = new CircleThemeSupplyServiceImpl();
        ReflectionTestUtils.setField(service, "topicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "supplyMapper", supplyMapper);

        CmsTopic topic = new CmsTopic();
        topic.setId(9L);
        topic.setCircleThemeCode("SOLO");
        topic.setStatus(1);
        topic.setUserStatus(1);
        topic.setDelFlag(0);
        topic.setCircleReviewedAt(new Date());
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic);
    }

    @Test
    void fewerThanThreeDistinctEffectiveMerchantsReturnsNoPublicInstance() {
        when(supplyMapper.countEffectiveMerchantsByTopicId(9L)).thenReturn(2);

        assertEquals(Collections.emptyList(), service.listPublicSupplies(9L));

        verify(supplyMapper, never()).selectEffectiveByTopicId(9L);
    }

    @Test
    void threeDistinctEffectiveMerchantsExposesOnlyEffectiveSnapshots() {
        MerchantCircleSupplyProfile profile = new MerchantCircleSupplyProfile();
        when(supplyMapper.countEffectiveMerchantsByTopicId(9L)).thenReturn(3);
        when(supplyMapper.selectEffectiveByTopicId(9L)).thenReturn(Collections.singletonList(profile));

        assertEquals(Collections.singletonList(profile), service.listPublicSupplies(9L));
    }

    @Test
    void moreThanFourDistinctMerchantsHidesTheMisconfiguredInstance() {
        when(supplyMapper.countEffectiveMerchantsByTopicId(9L)).thenReturn(5);

        assertEquals(Collections.emptyList(), service.listPublicSupplies(9L));
        verify(supplyMapper, never()).selectEffectiveByTopicId(9L);
    }

    @Test
    void missingOrExpiredCityAgentReviewHidesTheInstance() {
        CmsTopic topic = topicMapper.selectCmsTopicById(9L);
        topic.setCircleReviewedAt(new Date(System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000));

        assertEquals(Collections.emptyList(), service.listPublicSupplies(9L));
        verify(supplyMapper, never()).countEffectiveMerchantsByTopicId(9L);
    }
}
