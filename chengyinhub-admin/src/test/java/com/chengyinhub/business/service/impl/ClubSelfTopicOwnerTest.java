package com.chengyinhub.business.service.impl;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ClubSelfTopicOwnerTest {
 @Test void actualSelfClubOwnerOnlyActiveAndLiveTopic() {
  ClubMemberServiceImpl service=new ClubMemberServiceImpl();
  CmsTopicMapper topics=mock(CmsTopicMapper.class);ClubMapper clubs=mock(ClubMapper.class);CoopOrderMapper orders=mock(CoopOrderMapper.class);
  ReflectionTestUtils.setField(service,"cmsTopicMapper",topics);ReflectionTestUtils.setField(service,"clubMapper",clubs);ReflectionTestUtils.setField(service,"coopOrderMapper",orders);
  when(orders.selectEffectiveOrdersByTopicId(7L)).thenReturn(Collections.emptyList());
  CmsTopic topic=new CmsTopic();topic.setId(7L);topic.setClubId(8L);topic.setDelFlag(0);
  Club club=new Club();club.setId(8L);club.setMemberId(9L);club.setStatus(1L);club.setDelFlag(0);
  when(topics.selectCmsTopicById(7L)).thenReturn(topic);when(clubs.selectClubById(8L)).thenReturn(club);
  assertTrue(service.isTopicOwner(7L,8L,9L));assertTrue(service.isTopicOwner(7L,9L));
  assertFalse(service.isTopicOwner(7L,8L,10L));assertFalse(service.isTopicOwner(7L,99L,9L));
  CoopOrder foreign = new CoopOrder();foreign.setToType("club");foreign.setToId(99L);
  when(orders.selectEffectiveOrdersByTopicId(7L)).thenReturn(Collections.singletonList(foreign));
  when(clubs.selectClubById(99L)).thenReturn(club);
  assertFalse(service.isTopicOwner(7L,99L,9L), "外俱乐部合作单不得替代自办主题真实clubId");
  club.setStatus(0L);assertFalse(service.isTopicOwner(7L,8L,9L));
  club.setStatus(1L);club.setDelFlag(2);assertFalse(service.isTopicOwner(7L,8L,9L));
  club.setDelFlag(0);topic.setDelFlag(2);assertFalse(service.isTopicOwner(7L,8L,9L));
  topic.setDelFlag(null);assertFalse(service.isTopicOwner(7L,8L,9L));assertFalse(service.isTopicOwner(7L,9L));
  topic.setDelFlag(1);assertFalse(service.isTopicOwner(7L,8L,9L));assertFalse(service.isTopicOwner(7L,9L));
  topic.setDelFlag(0);when(topics.selectCmsTopicById(7L)).thenReturn(null);assertFalse(service.isTopicOwner(7L,8L,9L));
 }
}
