package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.impl.ProjectHomeReadServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectHomeHostEditWindowTest {
    private ICmsTopicService topics;
    private ProjectHomeReadServiceImpl service;
    private CmsTopic topic;

    @BeforeEach
    void setUp() {
        topics = mock(ICmsTopicService.class);
        service = new ProjectHomeReadServiceImpl(topics, mock(ICmsTopicNodeService.class),
                mock(ICmsRegistrationMerchantService.class), mock(ICmsRegistrationService.class),
                mock(ICoopInviteService.class), mock(IClubService.class), mock(IMmsMerchantService.class),
                mock(IMerchantRegistrationDetailReadService.class), mock(IMerchantChapterApplicationService.class));
        topic = new CmsTopic();
        topic.setId(79L);
        topic.setMemberId(9L);
        topic.setStatus(1);
        topic.setLifecycle(1);
        topic.setStartDate(new Date(System.currentTimeMillis() - 60000L));
        when(topics.selectCmsTopicById(79L)).thenReturn(topic);
        when(topics.selectTopicIsDraft(79L)).thenReturn(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> host() {
        return (Map<String, Object>) service.getProjectHome(9L, 79L).get("host");
    }

    @Test
    void alreadyStartedPublishedTopicDoesNotAdvertiseAnUnusableEditor() {
        Map<String, Object> host = host();
        assertEquals(false, host.get("canEdit"));
        assertTrue(String.valueOf(host.get("editReason")).contains("主题已开始"));
    }

    @Test
    void returnedPrivateDraftAllowsFullCorrectionDespiteItsPastDate() {
        topic.setLifecycle(0);
        when(topics.selectTopicIsDraft(79L)).thenReturn(1);
        Map<String, Object> host = host();
        assertEquals(true, host.get("canEdit"));
        assertEquals("FULL", host.get("editScope"));
        assertEquals(null, host.get("editReason"));
    }

    @Test
    void futureApprovedTopicAdvertisesOnlyItsExistingWhitelistScope() {
        topic.setStartDate(new Date(System.currentTimeMillis() + 60000L));
        Map<String, Object> host = host();
        assertEquals(true, host.get("canEdit"));
        assertEquals("WHITELIST", host.get("editScope"));
    }

    @Test
    void allSaleStartedStatesStayFrozenEvenWithAStaleDraftFlag() {
        topic.setStartDate(new Date(System.currentTimeMillis() + 60000L));
        when(topics.selectTopicIsDraft(79L)).thenReturn(1);
        for (int lifecycle : new int[] {3, 4, 5, 6}) {
            topic.setLifecycle(lifecycle);
            Map<String, Object> host = host();
            assertEquals(false, host.get("canEdit"));
            assertEquals("SELLING_FROZEN", host.get("editScope"));
        }
    }

    @Test
    void unknownOrMissingLifecycleNeverAdvertisesEditing() {
        for (Integer lifecycle : new Integer[] {99, null}) {
            topic.setLifecycle(lifecycle);
            Map<String, Object> host = host();
            assertEquals(false, host.get("canEdit"));
            assertTrue(String.valueOf(host.get("editReason")).contains("生命周期"));
        }
    }

    @Test
    void pendingOrRejectedContentKeepsTheExistingFullEditWindow() {
        topic.setLifecycle(0);
        for (int status : new int[] {0, 2}) {
            topic.setStatus(status);
            Map<String, Object> host = host();
            assertEquals(true, host.get("canEdit"));
            assertEquals("FULL", host.get("editScope"));
        }
    }
}
