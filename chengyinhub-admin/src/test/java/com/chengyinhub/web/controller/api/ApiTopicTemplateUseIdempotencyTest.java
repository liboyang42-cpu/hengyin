package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.AuthorWriteReceiptMapper;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.service.support.AuthorWriteReceiptService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * qx-c1 #8: {@code /api/template/topic-template/use} accepts an optional requestId on the existing author-write receipt.
 * Same id + same payload replays the original result without a second copy; same id + different payload is 409.
 */
class ApiTopicTemplateUseIdempotencyTest {
    private static final long PRINCIPAL = 901L;
    private static final long TEMPLATE_ID = 42L;

    private ApiTopicTemplateController controller;
    private ICmsTopicService topics;
    private JdbcTemplate jdbc;
    private final AtomicLong copies = new AtomicLong(7000L);

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:use_" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE app_author_write_receipt (actor_member_id BIGINT, operation VARCHAR(32), request_id VARCHAR(64), "
                + "request_hash VARCHAR(64), result_json LONGTEXT, PRIMARY KEY(actor_member_id,operation,request_id))");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/business/AuthorWriteReceiptMapper.xml"));
        AuthorWriteReceiptMapper mapper = new SqlSessionTemplate(factory.getObject()).getMapper(AuthorWriteReceiptMapper.class);
        AuthorWriteReceiptService receipts = new AuthorWriteReceiptService(mapper, new DataSourceTransactionManager(source));

        controller = spy(new ApiTopicTemplateController());
        topics = mock(ICmsTopicService.class);
        doReturn(PRINCIPAL).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topics);
        ReflectionTestUtils.setField(controller, "merchantAccessService", mock(IMerchantAccessService.class));
        ReflectionTestUtils.setField(controller, "authorWriteReceipts", receipts);
        when(topics.selectTopicIsTemplate(TEMPLATE_ID)).thenReturn(1);
        when(topics.copyCmsTopic(eq(TEMPLATE_ID), any())).thenAnswer(call -> copies.incrementAndGet());
        when(topics.selectCmsTopicById(anyLong())).thenAnswer(call -> {
            CmsTopic topic = new CmsTopic();
            topic.setId(call.getArgument(0));
            topic.setMemberId(PRINCIPAL);
            topic.setCityCode("310100");
            return topic;
        });
    }

    @SuppressWarnings("unchecked")
    private static Object topicId(AjaxResult result) {
        return ((Map<String, Object>) result.get(AjaxResult.DATA_TAG)).get("topicId");
    }

    @Test
    void sameRequestIdAndPayloadReplaysTheOriginalDraftWithoutASecondCopy() {
        AjaxResult first = controller.use("42", null, "SAME_CITY", null, null, "use-req-0001");
        assertEquals(200, first.get(AjaxResult.CODE_TAG));
        assertEquals("COMPLETED", ((Map<?, ?>) first.get("writeReceipt")).get("state"));
        AjaxResult replay = controller.use("42", null, "SAME_CITY", "", null, "use-req-0001");
        assertEquals(200, replay.get(AjaxResult.CODE_TAG));
        assertEquals(String.valueOf(topicId(first)), String.valueOf(topicId(replay)), "same draft, not a second copy");
        verify(topics, times(1)).copyCmsTopic(eq(TEMPLATE_ID), any());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_author_write_receipt WHERE operation='TOPIC_TEMPLATE_USE'", Integer.class));
    }

    @Test
    void sameRequestIdWithADifferentPayloadIsRejected() {
        controller.use("42", null, "SAME_CITY", null, null, "use-req-0002");
        AjaxResult changed = controller.use("42", null, "NEW_CITY", "320100", null, "use-req-0002");
        assertEquals(409, changed.get(AjaxResult.CODE_TAG));
        assertEquals("原请求号对应的内容不一致", changed.get(AjaxResult.MSG_TAG));
        verify(topics, times(1)).copyCmsTopic(eq(TEMPLATE_ID), any());
    }

    @Test
    void aRejectedFirstAttemptDoesNotConsumeTheRequestId() {
        when(topics.selectTopicIsTemplate(TEMPLATE_ID)).thenReturn(0);
        AjaxResult refused = controller.use("42", null, null, null, null, "use-req-0003");
        assertEquals("该主题不是可用模板", refused.get(AjaxResult.MSG_TAG));
        verify(topics, never()).copyCmsTopic(anyLong(), any());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_author_write_receipt", Integer.class));
        when(topics.selectTopicIsTemplate(TEMPLATE_ID)).thenReturn(1);
        assertEquals(200, controller.use("42", null, null, null, null, "use-req-0003").get(AjaxResult.CODE_TAG));
    }

    @Test
    void withoutRequestIdEveryCallStillCopiesAsBefore() {
        controller.use("42", null, null, null, null, null);
        controller.use("42", null, null, null, null, " ");
        verify(topics, times(2)).copyCmsTopic(eq(TEMPLATE_ID), any());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_author_write_receipt", Integer.class));
    }

    @Test
    void anotherMemberReusingTheRequestIdGetsTheirOwnDraft() {
        controller.use("42", null, null, null, null, "use-req-0005");
        doReturn(902L).when(controller).getAppUserId();
        AjaxResult other = controller.use("42", null, null, null, null, "use-req-0005");
        assertEquals(200, other.get(AjaxResult.CODE_TAG));
        verify(topics, times(2)).copyCmsTopic(eq(TEMPLATE_ID), any());
    }
}
