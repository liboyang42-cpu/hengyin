package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ImConversation;
import com.chengyinhub.business.domain.ImMessage;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * CL-39：IM 图片在「待审 / 驳回」时对会话另一方不可见，作者看得到自己的待审图。
 *
 * <p>状态来源：ImServiceImpl.send 对图片 checkMediaAsync 拿 trace，写 check_status=0、visible_at=NULL；
 * 送检失败(trace 为空)直接抛错不落库(见 ImServiceImplTest#imageMessageFailsClosedBeforeInsertWhenWechatSubmissionFails)。
 * 回调 applyMediaCheckResult：通过 → check_status=1 + visible_at；违规 → check_status=2 + status=2。
 * 他人读路径：selectVisiblePage(消息页、游标翻页、会话列表最新一条预览)；
 * 会话缓存预览 im_conversation.last_msg_text 对图片只写占位「[图片]」，被屏蔽时改写为「[消息已屏蔽]」。
 *
 * <p>MySQL 真库语义(applyMediaCheckResult 的 UPDATE JOIN、now(6))由
 * ImSendIdempotencyMybatisIntegrationTest 覆盖；这里用 H2 只锁读侧过滤。
 */
public class ImMessageReviewVisibilityMyBatisTest {
    private static final long CONV = 100L;
    private static final long SENDER = 1L;
    private static final long OTHER = 2L;

    /** H2 无 DATE_FORMAT；只为 selectImMessageVo 的 visible_order_at 投影提供等价实现。 */
    public static String dateFormat(Timestamp value, String ignoredPattern) {
        return value == null ? null
                : value.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"));
    }

    @Test
    void pendingAndRejectedImagesAreHiddenFromTheOtherMemberAndApprovedImageIsShown() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        schema(jdbc);
        message(jdbc, 1, SENDER, 1, "hello", 0, 1, "now()");
        message(jdbc, 2, SENDER, 2, "https://cdn.example/pending.jpg", 0, 0, null);
        message(jdbc, 3, SENDER, 2, "https://cdn.example/rejected.jpg", 2, 2, null);
        message(jdbc, 4, SENDER, 2, "https://cdn.example/approved.jpg", 0, 1, "now()");
        try (SqlSession session = factory(ds).openSession(true)) {
            ImMessageMapper mapper = session.getMapper(ImMessageMapper.class);
            assertEquals(List.of(4L, 1L), ids(mapper.selectVisiblePage(CONV, OTHER, null, 20)));
            List<ImMessage> own = mapper.selectVisiblePage(CONV, SENDER, null, 20);
            assertEquals(List.of(4L, 2L, 1L), ids(own.stream()
                    .sorted((a, b) -> Long.compare(b.getId(), a.getId())).collect(Collectors.toList())),
                    "sender keeps own pending image; rejected one is withdrawn from both sides");
            ImMessage pending = own.stream().filter(m -> m.getId() == 2L).findFirst().orElseThrow();
            assertEquals(Integer.valueOf(0), pending.getCheckStatus(), "sender sees the pending state");
        }
    }

    @Test
    void cursorPagingAndLatestPreviewNeverSurfaceUnapprovedImagesToTheOtherMember() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        schema(jdbc);
        message(jdbc, 1, SENDER, 1, "older", 0, 1, "dateadd('SECOND',-10,now())");
        message(jdbc, 2, SENDER, 1, "newer", 0, 1, "dateadd('SECOND',-5,now())");
        message(jdbc, 3, SENDER, 2, "https://cdn.example/pending.jpg", 0, 0, null);
        message(jdbc, 4, SENDER, 2, "https://cdn.example/rejected.jpg", 2, 2, null);
        jdbc.update("insert into im_conversation(id,type,last_msg_type,last_msg_text,last_msg_at,status) values (?,1,2,'[图片]',now(),0)", CONV);
        try (SqlSession session = factory(ds).openSession(true)) {
            ImMessageMapper mapper = session.getMapper(ImMessageMapper.class);
            // 会话列表「最新一条」预览走 selectVisiblePage(...,1)。
            assertEquals(List.of(2L), ids(mapper.selectVisiblePage(CONV, OTHER, null, 1)));
            assertEquals(List.of(1L), ids(mapper.selectVisiblePage(CONV, OTHER, 2L, 1)));
            // 用不可见消息 id 作游标也翻不出它自己或其它不可见消息。
            assertEquals(List.of(), ids(mapper.selectVisiblePage(CONV, OTHER, 3L, 20)));
            ImConversation cached = session.getMapper(ImConversationMapper.class).selectImConversationById(CONV);
            assertEquals("[消息已屏蔽]", cached.getLastMsgText(), "cached preview masks a blocked latest message");
            jdbc.update("delete from im_message where id=4");
            session.clearCache();
            assertEquals("[图片]", session.getMapper(ImConversationMapper.class).selectImConversationById(CONV)
                    .getLastMsgText(), "cached preview of a pending image is a placeholder, never the URL");
        }
    }

    @Test
    void imageWhoseCallbackNeverArrivesStaysHiddenForever() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        schema(jdbc);
        jdbc.update("insert into im_message(id,conversation_id,sender_id,receiver_id,msg_type,content,status,check_status,trace_id,create_time,visible_at) "
                + "values (9,?,?,?,2,'https://cdn.example/stale.jpg',0,0,'trace-lost',dateadd('DAY',-30,now()),null)",
                CONV, SENDER, OTHER);
        try (SqlSession session = factory(ds).openSession(true)) {
            ImMessageMapper mapper = session.getMapper(ImMessageMapper.class);
            assertEquals(List.of(), ids(mapper.selectVisiblePage(CONV, OTHER, null, 20)));
            assertEquals(List.of(9L), ids(mapper.selectVisiblePage(CONV, SENDER, null, 20)));
            assertNull(jdbc.queryForObject("select visible_at from im_message where id=9", Timestamp.class),
                    "no read-side timeout promotes the image");
        }
    }

    private static List<Long> ids(List<ImMessage> rows) {
        return rows.stream().map(ImMessage::getId).collect(Collectors.toList());
    }

    private void message(JdbcTemplate jdbc, long id, long sender, int type, String content, int status,
                         int checkStatus, String visibleAtExpr) {
        String visibleAt = visibleAtExpr == null ? "null" : visibleAtExpr;
        String createdAt = visibleAtExpr == null ? "now()" : visibleAtExpr;
        jdbc.update("insert into im_message(id,conversation_id,sender_id,receiver_id,msg_type,content,status,check_status,create_time,visible_at) "
                + "values (?,?,?,?,?,?,?,?," + createdAt + "," + visibleAt + ")",
                id, CONV, sender, OTHER, type, content, status, checkStatus);
    }

    private void schema(JdbcTemplate jdbc) {
        jdbc.execute("create alias date_format for \"" + getClass().getName() + ".dateFormat\"");
        jdbc.execute("create table im_message(id bigint primary key,conversation_id bigint,sender_id bigint,receiver_id bigint,msg_type int,content varchar(512),extra_json varchar(512),status int,check_status int,trace_id varchar(128),client_message_id varchar(64),request_hash varchar(64),create_time timestamp(6),visible_at timestamp(6))");
        jdbc.execute("create table im_conversation(id bigint primary key,type int,biz_key varchar(64),last_msg_type int,last_msg_text varchar(255),last_msg_at timestamp,status int,create_by varchar(64),create_time timestamp,update_by varchar(64),update_time timestamp,remark varchar(255))");
    }

    private DriverManagerDataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:im_review_visibility_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        return ds;
    }

    private SqlSessionFactory factory(DriverManagerDataSource dataSource) throws Exception {
        Configuration configuration = new Configuration(new Environment(
                "im-review-visibility", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAlias("ImMessage", ImMessage.class);
        configuration.getTypeAliasRegistry().registerAlias("ImConversation", ImConversation.class);
        configuration.addMapper(ImMessageMapper.class);
        configuration.addMapper(ImConversationMapper.class);
        for (String resource : new String[] {"mapper/business/ImMessageMapper.xml",
                "mapper/business/ImConversationMapper.xml"}) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (input == null) throw new IllegalStateException("missing " + resource);
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
