package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.TopicSaleSnapshot;
import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.business.service.support.TopicSaleCanonicalCodec;
import com.chengyinhub.common.constant.ProductType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopDayOneTicketGateTest {
    private static final Long TOPIC_ID = 83L;
    private static final Long MEMBER_ID = 901L;

    private CmsRegistrationServiceImpl service;
    private CmsRegistrationMapper registrationMapper;
    private CmsTopicMapper topicMapper;
    private TopicSaleSnapshotMapper snapshotMapper;

    @BeforeEach
    void setUp() {
        service = new CmsRegistrationServiceImpl();
        registrationMapper = mock(CmsRegistrationMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        snapshotMapper = mock(TopicSaleSnapshotMapper.class);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "topicSaleSnapshotMapper", snapshotMapper);
        when(snapshotMapper.selectSnapshotByTopicId(TOPIC_ID)).thenReturn(snapshot());
        when(registrationMapper.lockExploreTopicForUpdate(TOPIC_ID)).thenReturn(TOPIC_ID);
    }

    @Test
    void secondTicketIsRejectedFromFrozenPolicyAfterTopicLock() {
        when(registrationMapper.selectLiveExploreTicketIdsForUpdate(TOPIC_ID, MEMBER_ID))
                .thenReturn(Collections.singletonList(7001L));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> invoke(topic(ProductType.FREE_EXPLORE)));

        assertEquals("SHOP_DAY_ACCOUNT_TICKET_LIMIT_REACHED", error.getMessage());
        org.mockito.InOrder order = inOrder(snapshotMapper, registrationMapper);
        order.verify(snapshotMapper).selectSnapshotByTopicId(TOPIC_ID);
        order.verify(registrationMapper).lockExploreTopicForUpdate(TOPIC_ID);
        order.verify(registrationMapper).selectLiveExploreTicketIdsForUpdate(TOPIC_ID, MEMBER_ID);
    }

    @Test
    void classicPlayKeepsOriginalRuleAndDoesNotReadCanonicalSnapshot() {
        invoke(topic(ProductType.ORIENTEERING));

        verify(snapshotMapper, never()).selectSnapshotByTopicId(TOPIC_ID);
        verify(registrationMapper, never()).lockExploreTopicForUpdate(TOPIC_ID);
        verify(registrationMapper, never()).selectLiveExploreTicketIdsForUpdate(TOPIC_ID, MEMBER_ID);
    }

    @Test
    void mapperCurrentReadCoversDifferentActivityShellsOfSameSourceTopic() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get(
                "../chengyinhub-system/src/main/resources/mapper/business/CmsRegistrationMapper.xml")),
                StandardCharsets.UTF_8);
        int start = xml.indexOf("<select id=\"selectLiveExploreTicketIdsForUpdate\"");
        int end = xml.indexOf("</select>", start);
        String statement = xml.substring(start, end);

        assertTrue(statement.contains("left join cms_activity a"));
        assertTrue(statement.contains("a.topic_id = #{topicId}"));
        assertTrue(statement.contains("r.member_id = #{memberId}"));
        assertTrue(statement.contains("for update"));
    }

    @Test
    void allThreeRealPurchaseEntrypointsInvokeTheOneTicketGate() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsRegistrationServiceImpl.java")),
                StandardCharsets.UTF_8).replaceAll("\\s+", "");

        assertEquals(1, occurrences(source, "assertExploreAccountTicketAvailable(sellerTopic,memberId);"));
        assertEquals(1, occurrences(source, "assertExploreAccountTicketAvailable(orderTopic,memberId);"));
        assertEquals(1, occurrences(source, "assertExploreAccountTicketAvailable(topic,memberId);"));
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) count++;
        return count;
    }

    private void invoke(CmsTopic topic) {
        ReflectionTestUtils.invokeMethod(service, "assertExploreAccountTicketAvailable", topic, MEMBER_ID);
    }

    private static CmsTopic topic(int productType) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setProductType(productType);
        topic.setDelFlag(0);
        return topic;
    }

    private static TopicSaleSnapshot snapshot() {
        return TopicSaleCanonicalTestFixture.snapshot(TOPIC_ID);
    }
}
