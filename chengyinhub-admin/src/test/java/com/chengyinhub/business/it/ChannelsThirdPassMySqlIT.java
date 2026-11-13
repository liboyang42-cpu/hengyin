package com.chengyinhub.business.it;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.mapper.channel.media.ImMediaMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelGovernanceMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelMessageMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelOnboardingMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.service.channel.media.ImMediaPolicy;
import com.chengyinhub.business.service.channel.media.ImMediaPolicy.Kind;
import com.chengyinhub.business.service.channel.media.ImMediaService;
import com.chengyinhub.business.service.channel.media.ImMediaStore;
import com.chengyinhub.business.service.channel.platform.ChannelAdmissionService;
import com.chengyinhub.business.service.channel.platform.ChannelGuildAccess;
import com.chengyinhub.business.service.channel.platform.ChannelMessageService;
import com.chengyinhub.business.service.channel.platform.ChannelOnboardingService;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.common.exception.ServiceException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 频道第三批真库：third_pass 迁移（im_media_upload + 入会引导三表 + 默认配置）在 MySQL 8 上落地，
 * IM 媒体台账与 app_media_asset 审核态联读、挂接唯一性、删除闸，以及入会引导补种/提交/准入回执的映射 SQL 真实执行。
 * 只在 GitHub Actions 风格的 job 本地 MySQL（127.0.0.1）上建/删本套件库；对象存储用替身（只写 app_media_asset 行）。
 */
class ChannelsThirdPassMySqlIT
{
    static final long OWNER = 1, MEMBER = 2, STRANGER = 3, CLUB = 10, TEAM = 20, DM = 70;
    static final String[] MIGRATIONS = { "migration_20261105_l8_channel_lifecycle.sql", "migration_20261112_im_channels_platform.sql",
            "migration_20261105_l9_friend_foundation.sql", "migration_20261105_l8_chain_storage.sql",
            "migration_20261113_im_channels_second_pass.sql", "migration_20261012_app_media_asset.sql",
            "migration_20261012_app_media_asset_namespaces.sql", "migration_20261109_app_media_provenance.sql",
            "migration_20261114_im_channels_third_pass.sql" };

    private static String url;
    private static Path sql;
    private static SqlSessionFactory sessions;
    private SqlSession session;
    private ImMediaService media;
    private ChannelOnboardingService onboarding;
    private ChannelAdmissionService admission;
    private final List<Long> deletedAssets = new ArrayList<>();

    @BeforeAll static void createIsolatedSchema() throws Exception
    {
        if (!"true".equals(System.getenv("GITHUB_ACTIONS")) || !"127.0.0.1".equals(MybatisItDatabase.host()))
            throw new IllegalStateException("Requires GitHub Actions job-local MySQL on 127.0.0.1");
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("chengyinhub-admin/sql/migration_20261114_im_channels_third_pass.sql")))
            root = root.getParent();
        if (root == null) throw new IllegalStateException("Third-pass migration not found");
        sql = root.resolve("chengyinhub-admin/sql");
        url = MybatisItDatabase.url("im_channels_third_pass");
        try (Connection c = connection(); Statement s = c.createStatement())
        {
            ItSchema.apply(c, "it-schema/channels-platform-it-schema.sql");
            String[] paths = new String[MIGRATIONS.length];
            for (int i = 0; i < MIGRATIONS.length; i++) paths[i] = sql.resolve(MIGRATIONS[i]).toString();
            ItSchema.applyMigrations(c, paths);
            s.execute("ALTER TABLE app_media_asset ADD COLUMN safety_rejected TINYINT UNSIGNED NOT NULL DEFAULT 0");
        }
        UnpooledDataSource source = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", url, MybatisItDatabase.user(), MybatisItDatabase.password());
        Configuration config = new Configuration(new Environment("channels-p3-it", new JdbcTransactionFactory(), source));
        for (String resource : new String[] { "mapper/business/channel/platform/ChannelPlatformMapper.xml",
                "mapper/business/channel/platform/ChannelMessageMapper.xml", "mapper/business/channel/platform/ChannelGovernanceMapper.xml",
                "mapper/business/channel/platform/ChannelOnboardingMapper.xml", "mapper/business/channel/media/ImMediaMapper.xml",
                "mapper/business/AppMediaAssetMapper.xml" })
        {
            try (InputStream in = ChannelsThirdPassMySqlIT.class.getClassLoader().getResourceAsStream(resource))
            {
                if (in == null) throw new IllegalStateException("Mapper XML not found: " + resource);
                new XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
            }
        }
        sessions = new SqlSessionFactoryBuilder().build(config);
    }

    @BeforeEach void seed() throws Exception
    {
        try (Connection c = connection(); Statement s = c.createStatement())
        {
            s.execute("SET FOREIGN_KEY_CHECKS=0");
            for (String t : new String[] { "im_media_upload", "app_media_asset", "im_onboarding_answer", "im_onboarding_option", "im_onboarding_prompt",
                    "im_channel_request_receipt", "im_message", "im_conversation_member", "im_conversation", "club_member", "club",
                    "play_team_member", "play_team", "ums_member" })
                s.executeUpdate("DELETE FROM " + t);
            s.execute("SET FOREIGN_KEY_CHECKS=1");
            s.executeUpdate("INSERT INTO ums_member(id,nickname) VALUES (1,'owner'),(2,'member'),(3,'stranger')");
            s.executeUpdate("INSERT INTO club(id,member_id,name,status,del_flag) VALUES (10,1,'club',1,0)");
            s.executeUpdate("INSERT INTO club_member(club_id,member_id,status,del_flag) VALUES (10,2,1,0)");
            s.executeUpdate("INSERT INTO play_team(id,owner_type,owner_id,leader_member_id,status) VALUES (20,1,30,1,2)");
            s.executeUpdate("INSERT INTO play_team_member(team_id,member_id,status) VALUES (20,1,1)");
            s.executeUpdate("INSERT INTO im_conversation(id,type,biz_key,status) VALUES (70,1,'single_1_2',0)");
            s.executeUpdate("INSERT INTO im_conversation_member(conversation_id,member_id) VALUES (70,1),(70,2)");
        }
        session = sessions.openSession(true);
        ChannelPlatformMapper platform = session.getMapper(ChannelPlatformMapper.class);
        ChannelGuildAccess access = new ChannelGuildAccess();
        ReflectionTestUtils.setField(access, "mapper", platform);
        ChannelMessageService messages = new ChannelMessageService();
        ReflectionTestUtils.setField(messages, "messages", session.getMapper(ChannelMessageMapper.class));
        ReflectionTestUtils.setField(messages, "channels", platform);
        ReflectionTestUtils.setField(messages, "access", access);
        AppMediaAssetMapper assets = session.getMapper(AppMediaAssetMapper.class);
        ImMediaStore store = new ImMediaStore()
        {
            @Override public long put(long owner, byte[] bytes, String ext, String mime)
            {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("memberId", owner);
                row.put("objectKey", "upload/" + ImMediaPolicy.sha256(bytes).substring(0, 32) + "." + ext);
                row.put("mediaType", ext.equals("m4a") ? "AUDIO" : ext.equals("mp4") || ext.equals("mov") ? "VIDEO" : "FILE");
                assertEquals(1, assets.insert(row));
                return ((Number) row.get("id")).longValue();
            }
            @Override public String shortLivedUrl(long assetId, int seconds)
            {
                return "https://oss.example.invalid/" + assets.select(assetId).get("objectKey") + "?ttl=" + seconds;
            }
            @Override public boolean delete(long assetId) { deletedAssets.add(assetId); return true; }
        };
        media = new ImMediaService();
        ReflectionTestUtils.setField(media, "media", session.getMapper(ImMediaMapper.class));
        ReflectionTestUtils.setField(media, "store", store);
        ReflectionTestUtils.setField(media, "channels", messages);
        ReflectionTestUtils.setField(media, "conversations", session.getMapper(ChannelGovernanceMapper.class));
        onboarding = new ChannelOnboardingService();
        ReflectionTestUtils.setField(onboarding, "mapper", session.getMapper(ChannelOnboardingMapper.class));
        ReflectionTestUtils.setField(onboarding, "access", access);
        ReflectionTestUtils.setField(onboarding, "receipts", platform);
        admission = new ChannelAdmissionService();
        ReflectionTestUtils.setField(admission, "channels", platform);
    }

    @AfterEach void close() { if (session != null) session.close(); }

    static Map<String, Object> body(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static String code(Runnable r)
    {
        try { r.run(); return null; }
        catch (ChannelRejection e) { return e.getStatus() + " " + e.getErrorCode(); }
        catch (ServiceException e) { return e.getCode() + " " + e.getDetailMessage(); }
    }

    // ---------------------------------------------------------------- media

    @Test void fileUploadReviewAttachAndRecipientReadAgainstRealTables() throws Exception
    {
        byte[] pdf = "%PDF-1.7 third pass".getBytes();
        String hash = ImMediaPolicy.sha256(pdf), req = "0f8fad5b-d9cb-469f-a165-70867728950e";
        Map<String, Object> up = media.upload(OWNER, Kind.FILE, "70", req, hash, "行程.pdf", pdf);
        assertEquals("UPLOADED", up.get("state"));
        assertEquals("HOLD", up.get("review_state"));
        String asset = (String) ((Map<?, ?>) up.get("reference")).get("attachmentID");
        assertEquals("FILE", text("SELECT media_type FROM app_media_asset WHERE id=" + asset));
        assertEquals(up, media.upload(OWNER, Kind.FILE, "70", req, hash, "行程.pdf", pdf), "replay is identical, no second object");
        assertEquals(1, scalar("SELECT COUNT(*) FROM app_media_asset"));
        assertEquals("NOT_FOUND", media.receipt(MEMBER, Kind.FILE, "70", req, hash).get("state"), "receipts are owner-scoped");

        String card = "{\"attachmentId\":\"" + asset + "\",\"ext\":\"pdf\",\"mime\":\"application/pdf\",\"sha256\":\"" + hash
                + "\",\"size\":" + pdf.length + ",\"source\":\"original-im-file-v1\",\"uploadRequestId\":\"" + req + "\"}";
        assertEquals("409 MEDIA_NOT_APPROVED", code(() -> media.prepare(OWNER, DM, 12, "行程.pdf", card)));
        exec("UPDATE app_media_asset SET review_state='APPROVED' WHERE id=" + asset);
        assertEquals("CLEAN", media.receipt(OWNER, Kind.FILE, "70", req, hash).get("scan_state"));
        long uploadId = media.prepare(OWNER, DM, 12, "行程.pdf", card);
        exec("INSERT INTO im_message(id,conversation_id,sender_id,msg_type,content,extra_json,status,check_status,create_time) VALUES "
                + "(5000,70,1,12,'行程.pdf','" + card + "',0,1,NOW())");
        media.bind(uploadId, 5000);
        assertEquals("409 MEDIA_ALREADY_SENT", code(() -> media.bind(uploadId, 5001)));
        assertEquals("409 MEDIA_ALREADY_SENT", code(() -> media.prepare(OWNER, DM, 12, "行程.pdf", card)));

        assertEquals("original-im-file-authorization-v1", media.authorization(MEMBER, Kind.FILE, 5000).get("source"));
        Map<String, Object> preview = media.preview(MEMBER, Kind.FILE, 5000);
        assertTrue(((String) preview.get("url")).endsWith(".pdf?ttl=300"));
        assertEquals("404 MESSAGE_NOT_FOUND", code(() -> media.preview(STRANGER, Kind.FILE, 5000)));
        assertEquals("owner", ((Map<?, ?>) media.message(MEMBER, "5000").get("message")).get("senderName"));
        assertEquals("409 MEDIA_ATTACHED", code(() -> media.delete(OWNER, Kind.FILE, "70", req)));
        exec("UPDATE app_media_asset SET review_state='REJECTED' WHERE id=" + asset);
        assertEquals("409 MEDIA_REJECTED", code(() -> media.authorization(MEMBER, Kind.FILE, 5000)));
    }

    @Test void voiceDeleteAndLedgerConstraints() throws Exception
    {
        byte[] m4a = new byte[64];
        System.arraycopy(new byte[] { 0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ' }, 0, m4a, 0, 12);
        String hash = ImMediaPolicy.sha256(m4a), req = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
        Map<String, Object> up = media.upload(MEMBER, Kind.VOICE, "70", req, hash, "voice.m4a", m4a);
        assertEquals("UPLOADED", up.get("state"));
        assertEquals("AUDIO", text("SELECT media_type FROM app_media_asset WHERE id=" + up.get("asset_id")));
        assertEquals("404 CONVERSATION_NOT_FOUND", code(() -> media.upload(STRANGER, Kind.VOICE, "70", req, hash, "voice.m4a", m4a)));
        assertEquals(true, media.delete(MEMBER, Kind.VOICE, "70", req).get("changed"));
        assertEquals(List.of(Long.parseLong((String) up.get("asset_id"))), deletedAssets);
        assertEquals("NOT_FOUND", media.receipt(MEMBER, Kind.VOICE, "70", req, hash).get("state"));
        assertEquals("409 MEDIA_DELETED", code(() -> media.upload(MEMBER, Kind.VOICE, "70", req, hash, "voice.m4a", m4a)));

        assertThrows(SQLException.class, () -> exec("INSERT INTO im_media_upload(kind,owner_member_id,conversation_id,upload_request_id,source_sha256,"
                + "file_name,ext,mime,byte_size,state) VALUES ('FILE',1,70,'" + req + "','" + hash + "','a','pdf','application/pdf',1,'UPLOADED')"),
                "UPLOADED requires an asset");
        assertThrows(SQLException.class, () -> exec("INSERT INTO im_media_upload(kind,owner_member_id,conversation_id,upload_request_id,source_sha256,"
                + "file_name,ext,mime,byte_size) VALUES ('VOICE',2,70,'" + req + "','" + hash + "','a','m4a','audio/mp4',1)"),
                "one ledger row per owner/kind/request");
        assertThrows(SQLException.class, () -> exec("INSERT INTO im_media_upload(kind,owner_member_id,conversation_id,upload_request_id,source_sha256,"
                + "file_name,ext,mime,byte_size) VALUES ('IMAGE',2,70,'" + req + "','" + hash + "','a','png','image/png',1)"));
    }

    // ---------------------------------------------------------------- onboarding

    @Test void migrationSeedsEveryClubOnceAndServiceSeedsNewSpaces() throws Exception
    {
        exec("INSERT INTO club(id,member_id,name,status,del_flag) VALUES (11,1,'second',1,0),(12,1,'gone',1,1)");
        try (Connection c = connection())
        {
            ItSchema.applyMigrations(c, sql.resolve("migration_20261114_im_channels_third_pass.sql").toString());
            ItSchema.applyMigrations(c, sql.resolve("migration_20261114_im_channels_third_pass.sql").toString());
        }
        assertEquals(2, scalar("SELECT COUNT(*) FROM im_onboarding_prompt WHERE guild_kind=1"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM im_onboarding_prompt WHERE guild_id=12"));
        assertEquals(6, scalar("SELECT COUNT(*) FROM im_onboarding_option"));

        Map<String, Object> team = onboarding.read(OWNER, 3, TEAM);
        assertEquals("NONE", team.get("answer_state"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM im_onboarding_prompt WHERE guild_kind=3 AND guild_id=20"));
        assertEquals(team.get("configuration_hash"), onboarding.read(OWNER, 3, TEAM).get("configuration_hash"), "seeding is stable");
        assertEquals("403 GUILD_FORBIDDEN", code(() -> onboarding.read(STRANGER, 1, CLUB)));
    }

    @Test void submitStoresAnswersAndAdmissionReceiptReplaysIt() throws Exception
    {
        Map<String, Object> snap = onboarding.read(MEMBER, 1, CLUB);
        String hash = (String) snap.get("configuration_hash");
        Map<?, ?> prompt = (Map<?, ?>) ((List<?>) snap.get("prompts")).get(0);
        String promptId = (String) prompt.get("prompt_id");
        String option = (String) ((Map<?, ?>) ((List<?>) prompt.get("options")).get(1)).get("option_id");
        String req = "9b2c4e1a-3f5d-4a6b-8c7d-0e1f2a3b4c5d";
        Map<String, Object> body = body("guild_kind", 1, "guild_id", "10", "request_id", req, "configuration_hash", hash,
                "answers", List.of(body("prompt_id", promptId, "option_ids", List.of(option))));
        Map<String, Object> out = onboarding.submit(MEMBER, body);
        assertEquals(out, onboarding.submit(MEMBER, body));
        assertEquals(1, scalar("SELECT COUNT(*) FROM im_onboarding_answer WHERE member_id=2"));
        assertEquals(1, scalar("SELECT JSON_LENGTH(answers_json) FROM im_onboarding_answer WHERE member_id=2"));
        Map<String, Object> receipt = admission.receipt(MEMBER, req);
        assertEquals("ONBOARDING_SUBMIT", receipt.get("operation"));
        assertEquals(text("SELECT payload_fingerprint FROM im_channel_request_receipt WHERE request_id='" + req + "'"), receipt.get("request_hash"));
        assertEquals(out, receipt.get("result"));
        Map<String, Object> current = onboarding.read(MEMBER, 1, CLUB);
        assertEquals("CURRENT", current.get("answer_state"));
        assertEquals(1, current.get("submitted"));
        assertEquals("409 REQUEST_ID_CONFLICT", code(() -> onboarding.submit(MEMBER, body("guild_kind", 1, "guild_id", "10", "request_id", req,
                "configuration_hash", hash, "answers", List.of(body("prompt_id", promptId, "option_ids", List.of(option, option + "0")))))));
    }

    // ---------------------------------------------------------------- helpers

    private static Connection connection() throws SQLException
    {
        return DriverManager.getConnection(url, MybatisItDatabase.user(), MybatisItDatabase.password());
    }

    static void exec(String statement) throws SQLException
    {
        try (Connection c = connection(); Statement s = c.createStatement()) { s.executeUpdate(statement); }
    }

    static long scalar(String query) throws SQLException
    {
        try (Connection c = connection(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(query))
        {
            r.next();
            return r.getLong(1);
        }
    }

    static String text(String query) throws SQLException
    {
        try (Connection c = connection(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(query))
        {
            r.next();
            return r.getString(1);
        }
    }
}
