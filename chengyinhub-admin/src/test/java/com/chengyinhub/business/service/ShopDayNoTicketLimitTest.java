package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.TopicSaleSnapshot;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * C-22(2026-11-02 业主裁决)：取消「每人限 1 张」，探店日四店版同样取消。
 * 售票前只剩开售快照门禁，不再按账号查现有票。
 */
class ShopDayNoTicketLimitTest {
    private static final Long TOPIC_ID = 83L;

    private CmsRegistrationServiceImpl service;
    private CmsRegistrationMapper registrationMapper;
    private TopicSaleSnapshotMapper snapshotMapper;

    @BeforeEach
    void setUp() {
        service = new CmsRegistrationServiceImpl();
        registrationMapper = mock(CmsRegistrationMapper.class);
        snapshotMapper = mock(TopicSaleSnapshotMapper.class);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", mock(CmsTopicMapper.class));
        ReflectionTestUtils.setField(service, "topicSaleSnapshotMapper", snapshotMapper);
        when(snapshotMapper.selectSnapshotByTopicId(TOPIC_ID)).thenReturn(snapshot());
        when(registrationMapper.lockExploreTopicForUpdate(TOPIC_ID)).thenReturn(TOPIC_ID);
    }

    @Test
    void shopDayGateReadsOnlyFrozenSnapshotAndNeverCountsTheMembersTickets() {
        assertDoesNotThrow(() -> invoke(topic(ProductType.FREE_EXPLORE)));

        org.mockito.InOrder order = inOrder(snapshotMapper, registrationMapper);
        order.verify(snapshotMapper).selectSnapshotByTopicId(TOPIC_ID);
        order.verify(registrationMapper).lockExploreTopicForUpdate(TOPIC_ID);
        verifyNoMoreInteractions(registrationMapper);
    }

    @Test
    void classicPlayKeepsOriginalRuleAndDoesNotReadCanonicalSnapshot() {
        invoke(topic(ProductType.ORIENTEERING));

        verify(snapshotMapper, never()).selectSnapshotByTopicId(TOPIC_ID);
        verify(registrationMapper, never()).lockExploreTopicForUpdate(TOPIC_ID);
    }

    @Test
    void perAccountLiveTicketQueryAndLimitMessageAreGone() throws Exception {
        String xml = read("../chengyinhub-system/src/main/resources/mapper/business/CmsRegistrationMapper.xml");
        assertFalse(xml.contains("selectLiveExploreTicketIdsForUpdate"));
        String controller = read("src/main/java/com/chengyinhub/web/controller/api/ApiRegistrationController.java");
        assertFalse(controller.contains("SHOP_DAY_ACCOUNT_TICKET_LIMIT_REACHED"));
        assertFalse(controller.contains("购票上限"));
    }

    @Test
    void allThreeRealPurchaseEntrypointsStillInvokeTheSnapshotGate() throws Exception {
        String source = read(
                "../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsRegistrationServiceImpl.java")
                .replaceAll("\\s+", "");

        assertEquals(1, occurrences(source, "assertExploreSaleSnapshotFrozen(sellerTopic);"));
        assertEquals(1, occurrences(source, "assertExploreSaleSnapshotFrozen(orderTopic);"));
        assertEquals(1, occurrences(source, "assertExploreSaleSnapshotFrozen(topic);"));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) count++;
        return count;
    }

    private void invoke(CmsTopic topic) {
        ReflectionTestUtils.invokeMethod(service, "assertExploreSaleSnapshotFrozen", topic);
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
