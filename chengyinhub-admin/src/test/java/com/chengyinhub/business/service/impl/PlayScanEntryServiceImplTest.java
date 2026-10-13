package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.PlayScanEntry;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 门口游戏码：没票去买主题，有票进游玩。 */
class PlayScanEntryServiceImplTest {

    private static final String CODE = "0f1e2d3c4b5a69788796a5b4c3d2e1f0";
    private static final long PLAYER = 41L;

    private ICmsTopicNodeService nodes;
    private ICmsRegistrationService registrations;
    private ICmsActivityService activities;
    private PlayScanEntryService service;

    @BeforeEach
    void setUp() {
        nodes = mock(ICmsTopicNodeService.class);
        registrations = mock(ICmsRegistrationService.class);
        activities = mock(ICmsActivityService.class);
        service = new PlayScanEntryService(nodes, registrations, activities);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(77L);
        node.setTopicId(100L);
        node.setCheckinCode(CODE);
        when(nodes.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(node));
        when(registrations.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.emptyList());
    }

    @Test
    void unknownCodeIsInvalid() {
        when(nodes.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Collections.emptyList());
        ServiceException thrown = assertThrows(ServiceException.class, () -> service.resolve(PLAYER, CODE));
        assertEquals("无效的打卡码", thrown.getMessage());
    }

    @Test
    void unregisteredPlayerGoesToPurchase() {
        PlayScanEntry entry = service.resolve(PLAYER, CODE);
        assertEquals(PlayScanEntry.ACTION_PURCHASE, entry.action);
        assertEquals(100L, entry.topicId);
        assertEquals(77L, entry.nodeId);
        assertNull(entry.activityId);
    }

    @Test
    void selfPlayPassGoesToPlay() {
        CmsRegistration pass = new CmsRegistration();
        pass.setOwnerType(1L);
        pass.setOwnerId(100L);
        pass.setRegistrationStatus(2);
        pass.setExpiresAt(new Date(System.currentTimeMillis() + 86_400_000L));
        when(registrations.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenAnswer(invocation -> {
                    CmsRegistration probe = invocation.getArgument(0);
                    if (Long.valueOf(1L).equals(probe.getOwnerType())) {
                        return Collections.singletonList(pass);
                    }
                    return Collections.emptyList();
                });

        PlayScanEntry entry = service.resolve(PLAYER, CODE);
        assertEquals(PlayScanEntry.ACTION_PLAY, entry.action);
        assertEquals(100L, entry.topicId);
        assertNull(entry.activityId);
    }

    @Test
    void paidActivityGoesToPlayWithActivityId() {
        CmsRegistration paid = new CmsRegistration();
        paid.setOwnerType(2L);
        paid.setOwnerId(43L);
        paid.setRegistrationStatus(2);
        CmsActivity activity = new CmsActivity();
        activity.setId(43L);
        activity.setTopicId(100L);
        when(registrations.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenAnswer(invocation -> {
                    CmsRegistration probe = invocation.getArgument(0);
                    if (Long.valueOf(2L).equals(probe.getOwnerType())) {
                        return Collections.singletonList(paid);
                    }
                    return Collections.emptyList();
                });
        when(activities.selectCmsActivityById(43L)).thenReturn(activity);

        PlayScanEntry entry = service.resolve(PLAYER, CODE);
        assertEquals(PlayScanEntry.ACTION_PLAY, entry.action);
        assertEquals(43L, entry.activityId);
        assertEquals(100L, entry.topicId);
    }

    @Test
    void activityEndedOneMillisecondAgoFallsThroughToSelfPlay() {
        CmsRegistration paid = new CmsRegistration();
        paid.setOwnerType(2L);
        paid.setOwnerId(43L);
        paid.setRegistrationStatus(2);
        CmsActivity ended = new CmsActivity();
        ended.setId(43L);
        ended.setTopicId(100L);
        ended.setEndDate(new Date(System.currentTimeMillis() - 1L));
        CmsRegistration pass = new CmsRegistration();
        pass.setOwnerType(1L);
        pass.setOwnerId(100L);
        pass.setRegistrationStatus(2);
        pass.setExpiresAt(new Date(System.currentTimeMillis() + 86_400_000L));
        when(registrations.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenAnswer(invocation -> {
                    CmsRegistration probe = invocation.getArgument(0);
                    if (Long.valueOf(2L).equals(probe.getOwnerType())) {
                        return Collections.singletonList(paid);
                    }
                    if (Long.valueOf(1L).equals(probe.getOwnerType())) {
                        return Collections.singletonList(pass);
                    }
                    return Collections.emptyList();
                });
        when(activities.selectCmsActivityById(43L)).thenReturn(ended);

        PlayScanEntry entry = service.resolve(PLAYER, CODE);
        assertEquals(PlayScanEntry.ACTION_PLAY, entry.action);
        assertNull(entry.activityId);
        assertEquals(100L, entry.topicId);

        CmsRegistration next = new CmsRegistration();
        next.setOwnerType(2L);
        next.setOwnerId(44L);
        CmsActivity active = new CmsActivity();
        active.setId(44L);
        active.setTopicId(100L);
        active.setStartDate(new Date(System.currentTimeMillis() - 60_000L));
        active.setEndDate(new Date(System.currentTimeMillis() + 60_000L));
        when(activities.selectCmsActivityById(44L)).thenReturn(active);
        when(registrations.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(java.util.Arrays.asList(paid, next));
        assertEquals(44L, service.resolve(PLAYER, CODE).activityId);
    }
}
