package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ImConversation;
import com.chengyinhub.business.domain.ImConversationMember;
import com.chengyinhub.business.domain.ImMessage;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * N-04「屏蔽后的角标」:举报审核把一条已计入未读的消息屏蔽后,收件人的会话未读与角标总数
 * 必须当场减掉,不能等下一次 markRead 重算才回落;恢复时对称加回;重复结论不重复减。
 */
class ImUnreadAfterModerationBlockMyBatisTest {
    private static final long CONV = 100L;
    private static final long SENDER = 1L;
    private static final long READER = 2L;
    private static final long LATE_JOINER = 3L;

    private JdbcTemplate jdbc;
    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:im_unread_block_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table im_message(id bigint primary key,conversation_id bigint,sender_id bigint,receiver_id bigint,msg_type int,content varchar(512),extra_json varchar(512),status int,check_status int,trace_id varchar(128),client_message_id varchar(64),request_hash varchar(64),create_time timestamp(6),visible_at timestamp(6))");
        jdbc.execute("create table im_conversation(id bigint primary key,type int,biz_key varchar(64),last_msg_type int,last_msg_text varchar(255),last_msg_at timestamp,status int)");
        jdbc.execute("create table im_conversation_member(id bigint auto_increment primary key,conversation_id bigint,member_id bigint,role int,unread_count int default 0,last_read_msg_id bigint default 0,last_read_visible_at timestamp(6),muted int default 0,deleted int default 0,create_time timestamp)");
        jdbc.update("insert into im_conversation(id,type,status) values (?,2,0)", CONV);
        // 1 已读;2、3 是发给读者的未读文字;4 是待审图片(从未计入未读);5 是读者自己发的
        message(1, SENDER, 1, 0, 1, "2026-10-01 10:00:00");
        message(2, SENDER, 1, 0, 1, "2026-10-01 10:01:00");
        message(3, SENDER, 1, 0, 1, "2026-10-01 10:02:00");
        jdbc.update("insert into im_message(id,conversation_id,sender_id,msg_type,content,status,check_status,create_time,visible_at) values (4,?,?,2,'img',0,0,timestamp '2026-10-01 10:03:00',null)", CONV, SENDER);
        message(5, READER, 1, 0, 1, "2026-10-01 10:04:00");
        member(SENDER, 1, 5, "2026-10-01 10:04:00", "2026-09-01 00:00:00");
        member(READER, 2, 1, "2026-10-01 10:00:00", "2026-09-01 00:00:00");
        // 10:01:30 才入群:消息 2 不在其未读里,消息 3 在
        member(LATE_JOINER, 1, 0, null, "2026-10-01 10:01:30");

        Configuration configuration = new Configuration(new Environment(
                "im-unread-block", new JdbcTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAlias("ImMessage", ImMessage.class);
        configuration.getTypeAliasRegistry().registerAlias("ImConversation", ImConversation.class);
        configuration.getTypeAliasRegistry().registerAlias("ImConversationMember", ImConversationMember.class);
        configuration.addMapper(ImMessageMapper.class);
        configuration.addMapper(ImConversationMemberMapper.class);
        for (String resource : new String[] {"mapper/business/ImMessageMapper.xml",
                "mapper/business/ImConversationMemberMapper.xml"}) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (input == null) throw new IllegalStateException("missing " + resource);
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
    }

    private void message(long id, long sender, int type, int status, int check, String at) {
        jdbc.update("insert into im_message(id,conversation_id,sender_id,msg_type,content,status,check_status,create_time,visible_at) "
                + "values (?,?,?,?,'t',?,?,timestamp '" + at + "',timestamp '" + at + "')", id, CONV, sender, type, status, check);
    }

    private void member(long memberId, int unread, long lastReadId, String lastReadAt, String joinedAt) {
        jdbc.update("insert into im_conversation_member(conversation_id,member_id,role,unread_count,last_read_msg_id,last_read_visible_at,create_time) "
                + "values (?,?,0,?,?," + (lastReadAt == null ? "null" : "timestamp '" + lastReadAt + "'") + ",timestamp '" + joinedAt + "')",
                CONV, memberId, unread, lastReadId);
    }

    private int unread(long memberId) {
        return jdbc.queryForObject("select unread_count from im_conversation_member where conversation_id=? and member_id=?",
                Integer.class, CONV, memberId);
    }

    /** 与 AuditTaskServiceImpl#applyImModeration 同序:条件迁移命中 1 行才修正未读。 */
    private void moderate(SqlSession session, long messageId, boolean down) {
        ImMessageMapper messages = session.getMapper(ImMessageMapper.class);
        int from = down ? ImMessage.STATUS_NORMAL : ImMessage.STATUS_BLOCKED;
        int to = down ? ImMessage.STATUS_BLOCKED : ImMessage.STATUS_NORMAL;
        if (messages.transitionStatus(messageId, from, to) == 1) {
            session.getMapper(ImConversationMemberMapper.class).adjustUnreadForModeratedMessage(messageId, down ? -1 : 1);
        } else {
            messages.updateStatus(messageId, to);
        }
    }

    @Test
    void badgeDropsImmediatelyWhenAnUnreadMessageIsBlockedAndComesBackOnRestore() {
        try (SqlSession session = factory.openSession(true)) {
            ImConversationMemberMapper members = session.getMapper(ImConversationMemberMapper.class);
            assertEquals(Integer.valueOf(2), members.sumUnread(READER));

            moderate(session, 3L, true);
            assertEquals(1, unread(READER), "屏蔽一条未读 → 会话未读当场 -1");
            assertEquals(Integer.valueOf(1), members.sumUnread(READER), "角标总数同步回落,不等 markRead");
            assertEquals(1, unread(SENDER), "发送者自己的未读不受影响");
            assertEquals(0, unread(LATE_JOINER), "后入群成员也计着消息 3,同样回落");

            moderate(session, 3L, true);
            assertEquals(1, unread(READER), "重复/并发的同向结论不再减");

            moderate(session, 3L, false);
            assertEquals(2, unread(READER), "举报不成立恢复 → 对称加回");
            assertEquals(1, unread(LATE_JOINER));
        }
    }

    @Test
    void messagesThatWereNeverCountedDoNotTouchTheBadge() {
        try (SqlSession session = factory.openSession(true)) {
            moderate(session, 1L, true);   // 读者已读过
            moderate(session, 4L, true);   // 待审图片从未计入未读
            moderate(session, 2L, true);   // 晚入群成员入会前的消息
            assertEquals(1, unread(READER), "只有消息 2 计在读者未读里");
            assertEquals(1, unread(LATE_JOINER), "入会前的消息不扣后入群成员的未读");
            assertEquals(1, unread(SENDER));
            assertEquals(Integer.valueOf(ImMessage.STATUS_BLOCKED),
                    jdbc.queryForObject("select status from im_message where id=4", Integer.class));
        }
    }

    @Test
    void neverGoesNegative() {
        jdbc.update("update im_conversation_member set unread_count=0 where member_id=?", READER);
        try (SqlSession session = factory.openSession(true)) {
            moderate(session, 3L, true);
            assertEquals(0, unread(READER));
        }
    }
}
