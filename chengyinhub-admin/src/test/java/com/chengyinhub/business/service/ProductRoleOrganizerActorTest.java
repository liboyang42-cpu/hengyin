package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.ProductRoleMapper;
import com.chengyinhub.business.service.impl.ProductRoleServiceImpl;
import com.chengyinhub.common.constant.ProductSlot;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.RoleEntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.util.Collections;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 4-3：ORGANIZER actor 只经角色槽深模块修改，开售后 fail-closed。 */
class ProductRoleOrganizerActorTest {
    private static final Long TOPIC = 83L;
    private static final Long OPERATOR = 9001L;

    private ProductRoleMapper mapper;
    private ITopicOperationsScopeAuthorizer scopeAuthorizer;
    private ProductRoleServiceImpl service;
    private AtomicReference<ProductRole> active;

    @BeforeEach
    void setUp() {
        mapper = mock(ProductRoleMapper.class);
        scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        when(scopeAuthorizer.canViewTopic(TOPIC)).thenReturn(true);
        when(mapper.selectTopicForOrganizerUpdate(TOPIC)).thenReturn(topic(TopicLifecycleState.PRICING));
        active = new AtomicReference<ProductRole>();
        when(mapper.selectActiveByTopicAndSlot(TOPIC, ProductSlot.ORGANIZER)).thenAnswer(invocation ->
                active.get() == null ? Collections.<ProductRole>emptyList()
                        : Collections.singletonList(active.get()));
        when(mapper.deactivateByTopicAndSlot(TOPIC, ProductSlot.ORGANIZER)).thenAnswer(invocation -> {
            ProductRole before = active.getAndSet(null);
            return before == null ? 0 : 1;
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            ProductRole row = invocation.getArgument(0);
            row.setId(91L);
            row.setCreatedAt(new Date(123456L));
            active.set(row);
            return 1;
        }).when(mapper).insertProductRole(any(ProductRole.class));
        service = new ProductRoleServiceImpl();
        ReflectionTestUtils.setField(service, "productRoleMapper", mapper);
        ReflectionTestUtils.setField(service, "topicOperationsScopeAuthorizer", scopeAuthorizer);
    }

    @Test
    void shopDayPlatformOrganizerFreezesActorIdentityAndOperator() {
        assertTrue(service.declareRequiredOrganizerSlot(
                TOPIC, OPERATOR));

        ArgumentCaptor<ProductRole> role = ArgumentCaptor.forClass(ProductRole.class);
        verify(mapper).insertProductRole(role.capture());
        assertEquals(ProductSlot.ORGANIZER, role.getValue().getSlot());
        assertEquals(RoleEntityType.PLATFORM, role.getValue().getEntityType());
        assertNull(role.getValue().getEntityId());
        assertEquals(OPERATOR, role.getValue().getDeclaredBy());
    }

    @Test
    void sellingAndEveryLaterStateRejectBeforeAnyOrganizerMutation() {
        for (TopicLifecycleState state : new TopicLifecycleState[] {
                TopicLifecycleState.SELLING, TopicLifecycleState.RUNNING,
                TopicLifecycleState.SETTLING, TopicLifecycleState.CLOSED}) {
            when(mapper.selectTopicForOrganizerUpdate(TOPIC)).thenReturn(topic(state));

            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> service.declareRequiredOrganizerSlot(
                            TOPIC, OPERATOR));

            assertEquals("SALE_ORGANIZER_IMMUTABLE", error.getMessage());
        }
        verify(mapper, never()).deactivateByTopicAndSlot(anyLong(), anyString());
        verify(mapper, never()).insertProductRole(any(ProductRole.class));
    }

    @Test
    void pendingReviewCodeSevenIsNotMistakenForPostSale() {
        when(mapper.selectTopicForOrganizerUpdate(TOPIC))
                .thenReturn(topic(TopicLifecycleState.PENDING_REVIEW));

        assertTrue(service.declareRequiredOrganizerSlot(
                TOPIC, OPERATOR));

        verify(mapper).insertProductRole(any(ProductRole.class));
    }

    @Test
    void unknownLifecycleFailsClosed() {
        CmsTopic topic = topic(TopicLifecycleState.PRICING);
        topic.setLifecycle(99);
        when(mapper.selectTopicForOrganizerUpdate(TOPIC)).thenReturn(topic);

        assertThrows(IllegalArgumentException.class, () -> service.declareRequiredOrganizerSlot(
                TOPIC, OPERATOR));

        verify(mapper, never()).insertProductRole(any(ProductRole.class));
    }

    @Test
    void outOfScopeOperatorCannotMutateOrganizer() {
        when(scopeAuthorizer.canViewTopic(TOPIC)).thenReturn(false);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.declareRequiredOrganizerSlot(
                        TOPIC, OPERATOR));

        assertEquals("主题不存在或当前账号无操作范围", error.getMessage());
        verify(mapper, never()).selectTopicForOrganizerUpdate(anyLong());
        verify(mapper, never()).insertProductRole(any(ProductRole.class));
    }

    @Test
    void organizerMutationEntryIsScopedToShopDayOnly() {
        CmsTopic classic = topic(TopicLifecycleState.PRICING);
        classic.setProductType(ProductType.ORIENTEERING);
        when(mapper.selectTopicForOrganizerUpdate(TOPIC)).thenReturn(classic);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.declareRequiredOrganizerSlot(TOPIC, OPERATOR));

        assertEquals("SALE_ORGANIZER_ONLY_FREE_EXPLORE", error.getMessage());
        verify(mapper, never()).insertProductRole(any(ProductRole.class));
    }

    @Test
    void idempotentDeclarationLeavesTopicAndBothOperatorsInAuditLog() {
        ProductRole existing = new ProductRole();
        existing.setId(90L);
        existing.setTopicId(TOPIC);
        existing.setSlot(ProductSlot.ORGANIZER);
        existing.setEntityType(RoleEntityType.PLATFORM);
        existing.setStatus(1);
        existing.setDeclaredBy(8001L);
        existing.setCreatedAt(new Date(123456L));
        active.set(existing);

        java.util.List<String> messages = new java.util.ArrayList<String>();
        Logger logger = (Logger) LoggerFactory.getLogger(ProductRoleServiceImpl.class);
        Level originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        AppenderBase<ILoggingEvent> appender = new AppenderBase<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                messages.add(event.getFormattedMessage());
            }
        };
        appender.start();
        logger.addAppender(appender);
        try {
            assertTrue(service.declareRequiredOrganizerSlot(TOPIC, OPERATOR));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }

        assertTrue(messages.stream().anyMatch(message -> message.contains("ORGANIZER actor 幂等声明")
                        && message.contains("topicId=83")
                        && message.contains("originalOperatorId=8001")
                        && message.contains("retryOperatorId=9001")),
                "幂等成功必须留下主题、原声明人和本次操作人的可回读审计日志:" + messages);
        verify(mapper, never()).deactivateByTopicAndSlot(anyLong(), anyString());
        verify(mapper, never()).insertProductRole(any(ProductRole.class));
    }

    private static CmsTopic topic(TopicLifecycleState lifecycle) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setLifecycle(lifecycle.getCode());
        topic.setDelFlag(0);
        return topic;
    }
}
