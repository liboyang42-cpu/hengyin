package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.UmsMemberProfileVersion;
import com.chengyinhub.business.domain.WxContentCallbackInbox;
import com.chengyinhub.business.mapper.RoamShareSnapshotMapper;
import com.chengyinhub.business.mapper.UmsMemberProfileVersionMapper;
import com.chengyinhub.business.mapper.WxContentCallbackInboxMapper;
import com.chengyinhub.business.service.impl.WxContentCallbackInboxServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Lane W10b 真迁移 + 真 MyBatis XML + 真 MySQL:
 * #25 资料版本号按会员递增、头像回执按媒体哈希读 inbox;#23 分享版本 +1、按令牌撤销只动本人那一条。
 * 迁移各跑两遍,证明幂等。由 ci/backend-test.sh 的 MYBATIS_ITS 点名执行。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LaneW10bReadbackMySqlIT {
    private static final String T1 = "0123456789abcdef0123456789abcdef";
    private static final String T2 = "fedcba9876543210fedcba9876543210";

    private JdbcTemplate db;
    private UmsMemberProfileVersionMapper versions;
    private WxContentCallbackInboxMapper inbox;
    private RoamShareSnapshotMapper shares;

    @BeforeAll
    void schema() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(MybatisItDatabase.url("w10b_readback"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        db = new JdbcTemplate(source);
        for (int pass = 0; pass < 2; pass++) {
            for (String migration : new String[] {
                    "sql/migration_20260917_roam_share_snapshot.sql",
                    "sql/migration_20261012_roam_share_retention.sql",
                    "sql/migration_roam_share_version_20261022.sql",
                    "sql/migration_wx_content_callback_inbox_20260724.sql",
                    "sql/migration_member_profile_version_20261022.sql" }) {
                new ResourceDatabasePopulator(new FileSystemResource(migration)).execute(source);
            }
        }
        assertEquals(Integer.valueOf(1), db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()"
                + " AND table_name = 'roam_share_snapshot' AND column_name = 'version' AND data_type = 'int'"
                + " AND is_nullable = 'NO' AND column_default = '1'", Integer.class), "迁移自带断言在真库成立");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/business/UmsMemberProfileVersionMapper.xml"),
                new ClassPathResource("mapper/business/WxContentCallbackInboxMapper.xml"),
                new ClassPathResource("mapper/business/RoamShareSnapshotMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        versions = session.getMapper(UmsMemberProfileVersionMapper.class);
        inbox = session.getMapper(WxContentCallbackInboxMapper.class);
        shares = session.getMapper(RoamShareSnapshotMapper.class);
    }

    @BeforeEach
    void clean() {
        db.update("DELETE FROM ums_member_profile_version");
        db.update("DELETE FROM wx_content_callback_inbox");
        db.update("DELETE FROM roam_share_snapshot");
    }

    private static UmsMemberProfileVersion row(long member, String avatarRef, String hash) {
        UmsMemberProfileVersion v = new UmsMemberProfileVersion();
        v.setMemberId(member);
        v.setChangedFields(avatarRef == null ? "name" : "avatar");
        v.setAvatarRef(avatarRef);
        v.setAvatarMediaHash(hash);
        return v;
    }

    @Test
    void profileVersionsIncrementPerMemberAndLatestAvatarIsTracked() {
        String hash = WxContentCallbackInboxServiceImpl.sha256Hex("https://img.example.test/a.png");
        versions.insertNextVersion(row(9L, "media-asset:1", hash));
        versions.insertNextVersion(row(9L, null, null));
        versions.insertNextVersion(row(8L, null, null));
        assertEquals(Integer.valueOf(2), versions.selectLatest(9L).getVersionNo());
        assertEquals(Integer.valueOf(1), versions.selectLatest(8L).getVersionNo(), "版本号按会员独立递增");
        UmsMemberProfileVersion avatar = versions.selectLatestAvatarVersion(9L);
        assertEquals(Integer.valueOf(1), avatar.getVersionNo());
        assertEquals(hash, avatar.getAvatarMediaHash());
        assertNotNull(avatar.getCreateTime());
        assertNull(versions.selectLatestAvatarVersion(8L));
    }

    @Test
    void inboxReceiptReadsLatestRowForThatTargetAndMediaOnly() {
        String hash = WxContentCallbackInboxServiceImpl.sha256Hex("https://img.example.test/a.png");
        insertInbox("trace-1", 4, 9L, hash, 2, 0);
        insertInbox("trace-2", 4, 9L, hash, 0, null);
        insertInbox("trace-3", 4, 10L, hash, 2, 1);
        WxContentCallbackInbox latest = inbox.selectLatestByTargetMedia(4, 9L, hash);
        assertEquals(Integer.valueOf(0), latest.getState(), "取的是最近一次送检");
        assertNull(latest.getVerdict());
        assertEquals(Integer.valueOf(1), inbox.selectLatestByTargetMedia(4, 10L, hash).getVerdict());
        assertNull(inbox.selectLatestByTargetMedia(6, 9L, hash), "不同业务类型不串");
        assertNull(inbox.selectLatestByTargetMedia(4, 9L, WxContentCallbackInboxServiceImpl.sha256Hex("other")));
    }

    private void insertInbox(String trace, int bizType, long bizId, String mediaHash, int state, Integer verdict) {
        db.update("INSERT INTO wx_content_callback_inbox(trace_hash,biz_type,biz_id,media_hash,state,verdict)"
                + " VALUES(UNHEX(?),?,?,UNHEX(?),?,?)", WxContentCallbackInboxServiceImpl.sha256Hex(trace),
                bizType, bizId, mediaHash, state, verdict);
    }

    @Test
    void shareRepublishBumpsVersionAndTokenRevokeIsOwnerScoped() {
        shares.upsertOwnedSnapshot(T1, 9L, 100L, 1L, "{}");
        assertEquals(Integer.valueOf(1), shares.selectVersionByMemberTs(9L, 100L));
        shares.upsertOwnedSnapshot(T2, 9L, 100L, 1L, "{\"v\":2}");
        assertEquals(Integer.valueOf(2), shares.selectVersionByMemberTs(9L, 100L));
        assertEquals(T1, shares.selectTokenByMemberTs(9L, 100L));
        shares.upsertOwnedSnapshot(T2, 9L, 101L, 1L, "{}");
        assertEquals(0, shares.deleteByMemberToken(8L, T1), "别人的令牌撤不动");
        assertEquals(1, shares.deleteByMemberToken(9L, T1));
        assertNull(shares.selectVersionByMemberTs(9L, 100L));
        assertEquals(1, shares.countByMember(9L), "同一人的其它分享还在");
    }
}
