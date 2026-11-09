import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.WallFilter;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import java.io.InputStream;
import java.util.*;

/**
 * Rig-only: runs every SQL statement the l8-safety branch adds or changes, parsed by MyBatis from the
 * candidate's own mapper XML, through a Druid DataSource with the app's wall filter (MySQL, multi-statement
 * off) against the rig MySQL 8. Everything happens in one transaction that is rolled back.
 * Negative controls: `BINARY word = BINARY word`, an always-false `AND 1 = 0` and a multi-statement must be refused
 * by the same wall; `CAST(x AS BINARY) = CAST(y AS BINARY)` must pass.
 */
public class MapperWallProof {
    static int pass = 0, fail = 0;
    static void check(String name, boolean ok, Object detail) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name + "  " + detail);
    }
    static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }

    public static void main(String[] a) throws Exception {
        DruidDataSource ds = new DruidDataSource();
        ds.setUrl("jdbc:mysql://127.0.0.1:3316/cy_e2e_51?useUnicode=true&characterEncoding=utf8&serverTimezone=GMT%2B8");
        ds.setUsername("root");
        ds.setPassword("");
        WallConfig wc = new WallConfig("META-INF/druid/wall/mysql");
        wc.setMultiStatementAllow(false);
        WallFilter wall = new WallFilter();
        wall.setDbType("mysql");
        wall.setConfig(wc);
        ds.setProxyFilters(Collections.singletonList(wall));
        ds.init();

        Configuration cfg = new Configuration(new Environment("rig", new JdbcTransactionFactory(), ds));
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for (String x : new String[]{"ContentSafetyRecheckMapper", "ContentSensitiveMapper", "SmsCouponMapper", "WxContentCallbackInboxMapper"}) {
            String res = "mapper/business/" + x + ".xml";
            try (InputStream in = MapperWallProof.class.getClassLoader().getResourceAsStream(res)) {
                new XMLMapperBuilder(in, cfg, res, cfg.getSqlFragments()).parse();
            }
        }
        SqlSessionFactory f = new SqlSessionFactoryBuilder().build(cfg);
        String R = "com.chengyinhub.business.mapper.ContentSafetyRecheckMapper.";
        String S = "com.chengyinhub.business.mapper.ContentSensitiveMapper.";
        String C = "com.chengyinhub.business.mapper.SmsCouponMapper.";
        String W = "com.chengyinhub.business.mapper.WxContentCallbackInboxMapper.";
        try (SqlSession s = f.openSession(false)) {
            Date now = new Date();
            // ---- content_safety_recheck ----
            com.chengyinhub.business.domain.ContentSafetyRecheck r = new com.chengyinhub.business.domain.ContentSafetyRecheck();
            r.setBizType("TOPIC"); r.setBizId(-77L); r.setMemberId(9003L); r.setTextBody("rig"); r.setMediaJson("[]");
            r.setBusinessReview(0); r.setMaxAttempts(12); r.setNextAttemptAt(new Date(now.getTime() - 1000)); r.setCreateTime(now);
            check("recheck.insertRecheck", s.insert(R + "insertRecheck", r) == 1 && r.getId() != null, r.getId());
            Object p = s.selectOne(R + "selectPendingByBiz", m("bizType", "TOPIC", "bizId", -77L));
            check("recheck.selectPendingByBiz", p != null, p);
            check("recheck.refreshPending", s.update(R + "refreshPending", m("id", r.getId(), "memberId", 9003L, "textBody", "rig2", "mediaJson", null, "businessReview", 0, "now", now)) == 1, "");
            List<Object> due = s.selectList(R + "selectDue", m("now", new Date(now.getTime() + 1000), "limit", 50));
            check("recheck.selectDue", !due.isEmpty(), due.size());
            check("recheck.reschedule (CAS on attempt_count)", s.update(R + "reschedule", m("id", r.getId(), "expectedAttempt", 0, "attemptCount", 1, "nextAttemptAt", now, "errorCode", "X", "now", now)) == 1, "");
            check("recheck.reschedule stale CAS is a no-op", s.update(R + "reschedule", m("id", r.getId(), "expectedAttempt", 0, "attemptCount", 1, "nextAttemptAt", now, "errorCode", "X", "now", now)) == 0, "");
            check("recheck.finish", s.update(R + "finish", m("id", r.getId(), "expectedAttempt", 1, "state", 1, "errorCode", "PASS", "now", now)) == 1, "");
            check("recheck.finish twice is a no-op", s.update(R + "finish", m("id", r.getId(), "expectedAttempt", 1, "state", 1, "errorCode", "PASS", "now", now)) == 0, "");

            // ---- content_sensitive_* ----
            com.chengyinhub.business.domain.ContentSensitiveWord w = new com.chengyinhub.business.domain.ContentSensitiveWord();
            w.setWord("rigmadeupword"); w.setCategory("OTHER"); w.setAction("BLOCK"); w.setEnabled(1); w.setCreateBy("rig"); w.setCreateTime(now);
            check("sensitive.insertWord", s.insert(S + "insertWord", w) == 1, w.getId());
            w.setAction("LOG");
            check("sensitive.insertWord duplicate -> ON DUPLICATE KEY UPDATE", s.insert(S + "insertWord", w) >= 1, "");
            List<Object> wl = s.selectList(S + "selectWordList", m("word", "rigmadeup", "category", "OTHER", "enabled", 1));
            check("sensitive.selectWordList (LIKE + filters)", wl.size() == 1, wl.size());
            Long wid = ((com.chengyinhub.business.domain.ContentSensitiveWord) wl.get(0)).getId();
            check("sensitive.updateWordEnabled", s.update(S + "updateWordEnabled", m("id", wid, "enabled", 0, "updateBy", "rig", "now", now)) == 1, "");
            check("sensitive.selectEnabledWords", s.selectList(S + "selectEnabledWords") != null, "");
            com.chengyinhub.business.domain.ContentSensitiveAllow al = new com.chengyinhub.business.domain.ContentSensitiveAllow();
            al.setPhrase("rigmadeupphrase"); al.setEnabled(1); al.setCreateBy("rig"); al.setCreateTime(now);
            check("sensitive.insertAllow", s.insert(S + "insertAllow", al) == 1, "");
            check("sensitive.insertAllow duplicate", s.insert(S + "insertAllow", al) >= 1, "");
            List<Object> all = s.selectList(S + "selectAllowList", m("phrase", "rigmadeup"));
            check("sensitive.selectAllowList", all.size() == 1, all.size());
            check("sensitive.updateAllowEnabled", s.update(S + "updateAllowEnabled", m("id", ((com.chengyinhub.business.domain.ContentSensitiveAllow) all.get(0)).getId(), "enabled", 0, "updateBy", "rig", "now", now)) == 1, "");
            check("sensitive.selectEnabledAllowPhrases", s.selectList(S + "selectEnabledAllowPhrases") != null, "");
            com.chengyinhub.business.domain.ContentSensitiveImage im = new com.chengyinhub.business.domain.ContentSensitiveImage();
            im.setSha256("0".repeat(63) + "1"); im.setDhash(-1234567890123L); im.setCategory("OTHER"); im.setNote("rig"); im.setCreateBy("rig"); im.setCreateTime(now);
            check("sensitive.insertImage", s.insert(S + "insertImage", im) == 1, "");
            check("sensitive.insertImage duplicate", s.insert(S + "insertImage", im) >= 1, "");
            List<Object> il = s.selectList(S + "selectImageList");
            check("sensitive.selectImageList", !il.isEmpty(), il.size());
            check("sensitive.updateImageEnabled", s.update(S + "updateImageEnabled", m("id", ((com.chengyinhub.business.domain.ContentSensitiveImage) il.get(0)).getId(), "enabled", 1, "now", now)) == 1, "");
            check("sensitive.selectEnabledImages", s.selectList(S + "selectEnabledImages") != null, "");
            String ver = s.selectOne(S + "selectLibraryVersion");
            check("sensitive.selectLibraryVersion", ver != null && ver.contains("|"), ver + " (len " + (ver == null ? 0 : ver.length()) + ")");
            com.chengyinhub.business.domain.ContentSensitiveHit h = new com.chengyinhub.business.domain.ContentSensitiveHit();
            h.setSource("RESCAN"); h.setContentType("COMMENT"); h.setContentId(-5L); h.setCategory("OTHER"); h.setAction("REPORT");
            h.setScanVersion("f".repeat(64)); h.setCreateTime(now);
            check("sensitive.insertHit (64-char scan_version)", s.insert(S + "insertHit", h) == 1, "");
            Integer cnt = s.selectOne(S + "countRescanHit", m("contentType", "COMMENT", "contentId", -5L, "scanVersion", "f".repeat(64), "action", "REPORT"));
            check("sensitive.countRescanHit", cnt != null && cnt == 1, cnt);
            check("sensitive.selectRecentHits", !s.selectList(S + "selectRecentHits", m("limit", 5)).isEmpty(), "");
            for (String t : new String[]{"COMMENT", "COMMUNITY_POST", "CLUB_POST", "TOPIC", "ACTIVITY", "MERCHANT_PROFILE", "CLUB_PROFILE"}) {
                List<Object> rows = s.selectList(S + "selectPublicText", m("contentType", t, "afterId", 0L, "limit", 3));
                check("sensitive.selectPublicText " + t, rows != null, rows.size() + " rows");
            }

            // ---- sms_coupon (Round5B-3) ----
            Long mid = s.getConnection().createStatement().executeQuery("select 1").next() ? 9002L : null;
            java.sql.Statement st = s.getConnection().createStatement();
            st.executeUpdate("insert into sms_coupon(name, coupon_type, platform, publish_count, use_count, receive_count, scope_type, member_id, status, del_flag, create_time) values ('rig held', 1, 0, 10, 0, 0, 1, " + mid + ", 5, 0, now())", java.sql.Statement.RETURN_GENERATED_KEYS);
            java.sql.ResultSet gk = st.getGeneratedKeys(); gk.next(); long cid = gk.getLong(1);
            check("coupon.tryIncrementIssuableReceiveCount refuses status=5", s.update(C + "tryIncrementIssuableReceiveCount", m("id", cid)) == 0, cid);
            check("coupon.releaseHeldCoupon 5 -> 1", s.update(C + "releaseHeldCoupon", m("id", cid)) == 1, "");
            check("coupon.releaseHeldCoupon again is a no-op (CAS on 5)", s.update(C + "releaseHeldCoupon", m("id", cid)) == 0, "");
            check("coupon.tryIncrementIssuableReceiveCount after release", s.update(C + "tryIncrementIssuableReceiveCount", m("id", cid)) == 1, "");

            // ---- callback inbox lock, bizType 7 (node template) ----
            Object locked = s.selectOne(W + "lockBusinessTarget", m("bizType", 7, "bizId", -1L));
            check("callback.lockBusinessTarget bizType=7 (FOR UPDATE, absent row -> null)", locked == null, locked);

            // ---- negative controls: the wall is really on (forms probed with MySqlWallProvider first) ----
            for (String bad : new String[]{
                    "select id from content_sensitive_word where BINARY word = BINARY word",
                    "select id from content_sensitive_word where id = 1 and 1 = 0",
                    "select 1; select 2"}) {
                try {
                    s.getConnection().createStatement().executeQuery(bad);
                    check("negative control refused by wall: " + bad, false, "executed");
                } catch (java.sql.SQLException e) {
                    check("negative control refused by wall: " + bad, String.valueOf(e.getMessage()).contains("sql injection violation"), e.getMessage().split("\n")[0]);
                }
            }
            try {
                s.getConnection().createStatement().executeQuery("select id from content_sensitive_word where CAST(word AS BINARY) = CAST('x' AS BINARY)");
                check("CAST(x AS BINARY) = CAST(y AS BINARY) passes the wall", true, "");
            } catch (java.sql.SQLException e) {
                check("CAST(x AS BINARY) = CAST(y AS BINARY) passes the wall", false, e.getMessage());
            }
            s.rollback();
        }
        System.out.println("TOTAL pass=" + pass + " fail=" + fail);
        ds.close();
        System.exit(fail == 0 ? 0 : 1);
    }
}
