package com.chengyinhub.business.service.channel.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.chengyinhub.business.mapper.channel.platform.ChatErasureMapper;

/**
 * SOC-14: runs the production ChatErasureMapper.xml on H2 (MySQL mode). Member 7 deletes the account; member 8 is
 * a bystander. Channel 50 is live, channel 60 was deleted 8 days ago, channel 61 was deleted yesterday.
 */
class ChatErasureServiceTest
{
    private static final long ERASED = 7L;
    private static final long OTHER = 8L;

    @Test
    void scrubRemovesOnlyTheMembersTextKeepsRowsAndIsIdempotent() throws Exception
    {
        Fixture f = new Fixture("SCRUB", "SCRUB");
        f.seedChat();

        Map<String, Integer> counts = f.service.eraseMember(ERASED);

        assertEquals(3, counts.get("im_message"));
        // Rows stay (threads keep their shape); text, attachments and visibility go.
        assertEquals(3, f.count("select count(*) from im_message where sender_id = 7"));
        assertEquals(0, f.count("select count(*) from im_message where sender_id = 7 and (content <> '' or extra_json is not null)"));
        assertEquals(0, f.count("select count(*) from im_message where sender_id = 7 and status = 0"));
        assertEquals(1, f.count("select count(*) from im_message where id = 103 and status = 2"), "a held message keeps its held status");
        // The bystander's messages are untouched.
        assertEquals("hi from 8", f.db.queryForObject("select content from im_message where id = 201", String.class));
        assertEquals(0, f.count("select status from im_message where id = 201"));
        // Channel clients get a RECALL at the new version; the DM gets none (not a channel).
        assertEquals(1, f.count("select count(*) from im_message_mutation where message_id = 101 and kind = 'RECALL' and version = 2"));
        assertEquals(2, f.count("select version from im_message where id = 101"));
        assertEquals(0, f.count("select count(*) from im_message_mutation where message_id = 102"));
        // Edit history, pins and the inbox preview lose the text.
        assertNull(f.db.queryForObject("select content from im_message_revision where message_id = 101", String.class));
        assertEquals("old 8", f.db.queryForObject("select content from im_message_revision where message_id = 201", String.class));
        assertEquals(0, f.count("select count(*) from im_message_pin where message_id = 101"));
        assertEquals(1, f.count("select count(*) from im_message_pin where message_id = 201"));
        assertNull(f.db.queryForObject("select last_msg_text from im_conversation where id = 70", String.class));
        assertEquals("hi from 8", f.db.queryForObject("select last_msg_text from im_conversation where id = 50", String.class));
        // Operator ids.
        assertEquals(0, f.count("select count(*) from im_channel_op_log where operator_id = 7"));
        assertEquals(1, f.count("select count(*) from im_channel_op_log where operator_id is null"));
        assertEquals(0, f.count("select count(*) from im_channel_request_receipt where operator_id = 7"));
        assertEquals(1, f.count("select count(*) from im_channel_request_receipt where operator_id = 8"));

        Map<String, Integer> again = f.service.eraseMember(ERASED);
        assertEquals(0, again.get("im_message"));
        assertEquals(0, again.get("im_message_mutation"));
        assertEquals(1, f.count("select count(*) from im_message_mutation"), "no duplicate mutations on a rerun");
    }

    @Test
    void keepLeavesChatTextAndOperatorIdsAlone() throws Exception
    {
        Fixture f = new Fixture("KEEP", "KEEP");
        f.seedChat();
        assertTrue(f.service.eraseMember(ERASED).isEmpty());
        assertEquals("hello from 7", f.db.queryForObject("select content from im_message where id = 101", String.class));
        assertEquals(1, f.count("select count(*) from im_channel_op_log where operator_id = 7"));
    }

    @Test
    void unknownSwitchValuesFallBackToScrub()
    {
        ChatErasureService s = new ChatErasureService(null, "nonsense", null);
        assertEquals(ChatErasureService.Policy.SCRUB, s.messagePolicy());
        assertEquals(ChatErasureService.Policy.SCRUB, s.operatorIdPolicy());
        assertEquals(ChatErasureService.Policy.KEEP, new ChatErasureService(null, " keep ", "KEEP").messagePolicy());
    }

    @Test
    void purgeDeletesOnlyChannelsPastTheRestoreWindow() throws Exception
    {
        Fixture f = new Fixture("SCRUB", "SCRUB");
        f.seedChat();
        f.db.update("insert into im_channel values(60, 3, 0, dateadd('DAY', -1, current_timestamp), 5, null)");
        f.db.update("insert into im_channel values(61, 3, 0, dateadd('DAY', 6, current_timestamp), 5, null)");
        f.db.update("insert into im_conversation values(60, 5, 'bye', 1)");
        f.db.update("insert into im_conversation values(61, 5, 'bye', 1)");
        for (long id = 600; id < 1800; id++) f.db.update("insert into im_message values(?, 60, 8, 'x', null, 0, 1)", id);
        f.db.update("insert into im_message values(1900, 61, 8, 'restorable', null, 0, 1)");
        f.db.update("insert into im_message_reaction values(600, 8, 'heart', 60)");
        f.db.update("insert into im_message_mention values(601, 'USER', 7)");
        f.db.update("insert into im_message_ref values(602, 60)");
        f.db.update("insert into im_message_revision(message_id, revision_no, content) values(603, 1, 'x')");
        f.db.update("insert into im_message_pin(conversation_id, message_id) values(60, 604)");
        f.db.update("insert into im_message_mutation(conversation_id, message_id, version, kind) values(60, 605, 2, 'EDIT')");

        assertEquals(1, f.service.purgeDeletedChannels(50, new Date()));

        assertEquals(0, f.count("select count(*) from im_message where conversation_id = 60"));
        for (String table : new String[]{"im_message_reaction", "im_message_mention", "im_message_ref"})
            assertEquals(0, f.count("select count(*) from " + table + " where message_id between 600 and 1799"), table);
        assertEquals(0, f.count("select count(*) from im_message_revision where message_id = 603"));
        assertEquals(0, f.count("select count(*) from im_message_pin where conversation_id = 60"));
        assertEquals(0, f.count("select count(*) from im_message_mutation where conversation_id = 60"));
        assertEquals(4, f.count("select status from im_channel where id = 60"));
        assertNull(f.db.queryForObject("select last_msg_text from im_conversation where id = 60", String.class));
        // Still restorable: untouched.
        assertEquals(3, f.count("select status from im_channel where id = 61"));
        assertEquals(1, f.count("select count(*) from im_message where conversation_id = 61"));
        // Live channel and its messages: untouched.
        assertEquals(0, f.count("select status from im_channel where id = 50"));
        assertTrue(f.count("select count(*) from im_message where conversation_id = 50") > 0);

        assertEquals(0, f.service.purgeDeletedChannels(50, new Date()), "nothing left to purge");
    }

    static final class Fixture
    {
        final JdbcTemplate db;
        final ChatErasureService service;

        Fixture(String messages, String operators) throws Exception
        {
            DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:chat_erasure_"
                    + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
            db = new JdbcTemplate(source);
            db.execute("create table im_conversation(id bigint primary key, type tinyint, last_msg_text varchar(500), status tinyint)");
            db.execute("create table im_message(id bigint primary key, conversation_id bigint not null, sender_id bigint not null,"
                    + " content varchar(2000), extra_json varchar(1000), status tinyint not null default 0, version int not null default 1)");
            db.execute("create table im_message_mutation(id bigint auto_increment primary key, conversation_id bigint not null,"
                    + " message_id bigint not null, version int not null, kind varchar(16) not null,"
                    + " create_time timestamp(3) default current_timestamp(3))");
            db.execute("create table im_message_revision(id bigint auto_increment primary key, message_id bigint not null,"
                    + " revision_no int not null, content varchar(2000))");
            db.execute("create table im_message_pin(id bigint auto_increment primary key, conversation_id bigint not null,"
                    + " message_id bigint not null)");
            db.execute("create table im_message_reaction(message_id bigint not null, member_id bigint not null,"
                    + " emoji varchar(16) not null, conversation_id bigint not null, primary key(message_id, member_id, emoji))");
            db.execute("create table im_message_mention(message_id bigint not null, mention_type varchar(16) not null,"
                    + " target_id bigint not null default 0, primary key(message_id, mention_type, target_id))");
            db.execute("create table im_message_ref(message_id bigint primary key, conversation_id bigint not null)");
            db.execute("create table im_channel(id bigint primary key, status tinyint not null, del_flag tinyint not null,"
                    + " purge_at timestamp(3) null, meta_version bigint not null, update_time timestamp(3) null)");
            db.execute("create table im_channel_op_log(id bigint auto_increment primary key, channel_id bigint, operator_id bigint)");
            db.execute("create table im_channel_request_receipt(operator_id bigint not null, family varchar(16) not null,"
                    + " request_id varchar(64) not null, primary key(operator_id, family, request_id))");

            Configuration config = new Configuration(new Environment("chat-erasure", new SpringManagedTransactionFactory(), source));
            String xml = "mapper/business/channel/platform/ChatErasureMapper.xml";
            try (InputStream input = Resources.getResourceAsStream(xml))
            {
                new XMLMapperBuilder(input, config, xml, config.getSqlFragments()).parse();
            }
            SqlSessionTemplate sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
            service = new ChatErasureService(sessions.getMapper(ChatErasureMapper.class), messages, operators);
        }

        void seedChat()
        {
            // Channel 50 (live) and DM 70.
            db.update("insert into im_channel values(50, 0, 0, null, 1, null)");
            db.update("insert into im_conversation values(50, 5, 'hi from 8', 0)");
            db.update("insert into im_conversation values(70, 1, 'dm from 7', 0)");
            db.update("insert into im_message values(101, 50, 7, 'hello from 7', '{\"img\":1}', 0, 1)");
            db.update("insert into im_message values(103, 50, 7, 'held', null, 2, 1)");
            db.update("insert into im_message values(201, 50, 8, 'hi from 8', null, 0, 1)");
            db.update("insert into im_message values(102, 70, 7, 'dm from 7', null, 0, 1)");
            db.update("insert into im_message_revision(message_id, revision_no, content) values(101, 1, 'old 7')");
            db.update("insert into im_message_revision(message_id, revision_no, content) values(201, 1, 'old 8')");
            db.update("insert into im_message_pin(conversation_id, message_id) values(50, 101)");
            db.update("insert into im_message_pin(conversation_id, message_id) values(50, 201)");
            db.update("insert into im_channel_op_log(channel_id, operator_id) values(50, ?)", ERASED);
            db.update("insert into im_channel_request_receipt values(?, 'MSG', 'r1')", ERASED);
            db.update("insert into im_channel_request_receipt values(?, 'MSG', 'r2')", OTHER);
        }

        int count(String sql)
        {
            Integer n = db.queryForObject(sql, Integer.class);
            return n == null ? 0 : n;
        }
    }
}
