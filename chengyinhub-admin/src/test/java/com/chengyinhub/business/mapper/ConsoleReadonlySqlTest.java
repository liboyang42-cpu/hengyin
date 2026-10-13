package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubLeadCheckin;
import com.chengyinhub.business.domain.ClubLeadState;
import com.chengyinhub.business.domain.CoopGroupVerify;
import com.chengyinhub.business.domain.RoamPoi;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制台补全批次只读查询的 SQL 守卫(BoundSql,不连库;范式同 {@link PerkLedgerMapperSqlTest})。
 *
 * <p>这些查询没有 service 层逻辑可测,mock 测不到「SQL 少一个条件」——而这里少条件就是错口径:
 * <ul>
 *   <li>名册的报名必须 {@code owner_type=2} 配对 {@code owner_id} —— 共享ID空间,不配对必混读
 *       (2026-07 转述误判的教训);到场必须按 (activity, member) 双键配对。</li>
 *   <li>「流程经过的商家」必须生效两轴(status + valid_end)—— 裸 status=1 会把过期供给当在场。</li>
 *   <li>POI 治理列表**不得**硬编码 status —— 那是玩家口径(selectPoisNear),治理要看全量。</li>
 * </ul>
 *
 * ⚠️ 负控已实测:删 manifest 的 owner_type 半句 ⇒ 名册用例红;删 topicMerchants 的 valid_end
 *    半句 ⇒ 两轴用例红;给 selectAdminPois 硬加 status=1 ⇒ 全量口径用例红。
 */
class ConsoleReadonlySqlTest {

    private static final String CLUB_LEAD = "mapper/business/ClubLeadMapper.xml";
    private static final String ROAM = "mapper/business/RoamMapper.xml";
    private static final String GROUP_VERIFY = "mapper/business/CoopGroupVerifyMapper.xml";

    private String boundSql(String resource, Class<?>[] aliases, String statementId, Object param) throws Exception {
        Configuration cfg = new Configuration();
        for (Class<?> alias : aliases) {
            cfg.getTypeAliasRegistry().registerAlias(alias.getSimpleName(), alias);
        }
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        }
        return cfg.getMappedStatement(statementId).getBoundSql(param).getSql()
                .replaceAll("\\s+", " ").toLowerCase();
    }

    private String clubLeadSql(String stmt, Object param) throws Exception {
        return boundSql(CLUB_LEAD, new Class<?>[]{ClubLeadState.class, ClubLeadCheckin.class},
                "com.chengyinhub.business.mapper.ClubLeadMapper." + stmt, param);
    }

    /** 名册:owner_type=2 配对 + 到场按 (activity, member) 双键连。 */
    @Test
    void manifest_pairsOwnerTypeAndCheckinKeys() throws Exception {
        String s = clubLeadSql("selectManifest", 1L);
        assertTrue(s.contains("owner_type = 2"), "名册缺 owner_type 配对 —— 共享ID空间必混读。实际: " + s);
        assertTrue(s.contains("ck.member_id = r.member_id"), "到场打卡缺 member 配对键。实际: " + s);
    }

    /** 流程商家:生效两轴缺一即错口径。 */
    @Test
    void topicMerchants_filtersBothLiveAxes() throws Exception {
        String s = clubLeadSql("selectTopicMerchants", 1L);
        assertTrue(s.contains("status = 1"), "缺 status 轴。实际: " + s);
        assertTrue(s.contains("valid_end is null") && s.contains("valid_end >"),
                "缺 valid_end 轴 —— 过期供给会被当成「这团会经过的商家」。实际: " + s);
    }

    /** 场次列表:报名/到场两个计数都得在,且报名计数带 owner_type 配对。 */
    @Test
    void runList_countsRegAndCheckinWithPairing() throws Exception {
        String s = clubLeadSql("selectRunList", new HashMap<String, Object>());
        assertTrue(s.contains("club_lead_checkin"), "缺到场计数。实际: " + s);
        assertTrue(s.contains("owner_type = 2"), "报名计数缺 owner_type 配对。实际: " + s);
        assertTrue(s.contains("payment_status = 2"), "报名计数须只算已支付 —— 未付/退款单和到场数对比口径不齐。实际: " + s);
    }

    /** POI 治理:空参必须全量(不得硬编码玩家口径的 status=1)。 */
    @Test
    void adminPois_noHardcodedStatus() throws Exception {
        String s = boundSql(ROAM, new Class<?>[]{RoamPoi.class,
                        com.chengyinhub.business.domain.RoamSession.class},
                "com.chengyinhub.business.mapper.RoamMapper.selectAdminPois", new HashMap<String, Object>());
        assertFalse(s.contains("p.status ="), "空参治理列表不该按 status 过滤(下架的也要能看见)。实际: " + s);
        // ⚠️ roam_poi.merchant_id 是 mms_merchant.id 不是 memberId —— 连 ums_member 就是错表(初版真踩了)
        assertTrue(s.contains("mms_merchant"), "商家名必须连 mms_merchant(merchant_id 是 mms id)。实际: " + s);
        assertFalse(s.contains("ums_member"), "禁连 ums_member —— id 空间不同,昵称会张冠李戴。实际: " + s);
    }

    /** 团核销证据:三连表齐(主题名 + 商家/领队昵称)。 */
    @Test
    void groupVerifyAdmin_joinsAllNames() throws Exception {
        String s = boundSql(GROUP_VERIFY, new Class<?>[]{CoopGroupVerify.class},
                "com.chengyinhub.business.mapper.CoopGroupVerifyMapper.selectAdminList",
                new HashMap<String, Object>());
        assertTrue(s.contains("cms_topic"), "缺主题名连表。实际: " + s);
        assertTrue(s.contains("mm.nickname") && s.contains("lm.nickname"), "缺商家/领队昵称。实际: " + s);
    }
}
