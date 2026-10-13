package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantChapterOffer;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三档审核台 mapper 的 SQL 守卫(范式同 {@link PerkLedgerMapperSqlTest}:解析真 XML 生成
 * BoundSql,不连库)。
 *
 * <p>为什么必须有:{@code MerchantOfferAdjustTermsTest} 里 offerMapper 是 mock ——
 * service 层只验了「updateTerms 返回 0 行要拒」,而**守卫本身活在 XML 的 WHERE 里**,
 * 把它删掉 service 测试照样全绿。守卫没了 = 「读-判-写」窗口里并发核销/停用直接变脏写。
 *
 * ⚠️ 负控已实测:从 updateTerms WHERE 删掉 quota_used 半句 ⇒ 守卫用例红;
 *    给 selectAdminQueue 的 where 硬加 o.status = 1 ⇒ 全量口径用例红。
 */
class MerchantOfferAdminSqlTest {

    private static final String OFFER = "mapper/business/MerchantChapterOfferMapper.xml";
    private static final String NS = "com.chengyinhub.business.mapper.MerchantChapterOfferMapper.";

    private String offerSql(String stmt, Object param) throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("MerchantChapterOffer", MerchantChapterOffer.class);
        try (InputStream in = Resources.getResourceAsStream(OFFER)) {
            new XMLMapperBuilder(in, cfg, OFFER, cfg.getSqlFragments()).parse();
        }
        return cfg.getMappedStatement(NS + stmt).getBoundSql(param).getSql()
                .replaceAll("\\s+", " ").toLowerCase();
    }

    /** 定档定参必须是条件覆写:status=1 挡窗口内停用,quota_used<=新上限 挡窗口内核销顶穿。 */
    @Test
    void updateTerms_isConditionalWrite() throws Exception {
        String s = offerSql("updateTerms", new MerchantChapterOffer());
        assertTrue(s.contains("status = 1"), "updateTerms 缺 status 守卫 —— 停用后条款照写。实际: " + s);
        assertTrue(s.contains("quota_used <="), "updateTerms 缺 quota_used 守卫 —— 并发核销顶穿新上限=负容量。实际: " + s);
    }

    /** 审核台队列必须连出资质(mms_merchant)与履约(merchant_payable / PERK 聚合)。 */
    @Test
    void adminQueue_joinsQualificationAndFulfillment() throws Exception {
        String s = offerSql("selectAdminQueue", new HashMap<String, Object>());
        assertTrue(s.contains("mms_merchant"), "队列缺资质连表。实际: " + s);
        assertTrue(s.contains("merchant_payable"), "队列缺计酬核销笔数聚合。实际: " + s);
        assertTrue(s.contains("'perk'"), "队列缺权益档履约聚合(口径=仅 PERK)。实际: " + s);
        // 履约率分母:排除「从未消耗过的失效行」(被拒/退出且零核销的死行会白白拉低完成率),
        // 但保留有历史消耗的停用行 —— 历史就是历史
        assertTrue(s.contains("o2.quota_used > 0"), "履约聚合缺死行排除口径。实际: " + s);
        // 计酬净笔数必须显式列举 REVERSAL,新增 entry_type 不能被静默当冲正
        assertTrue(s.contains("when 'reversal' then -1"), "计酬笔数缺显式 REVERSAL 分支。实际: " + s);
    }

    /** 不筛 status 时必须含失效行 —— 停用历史是调证的一部分,别学 liveOffer 硬编码 status=1。 */
    @Test
    void adminQueue_keepsDeadRowsByDefault() throws Exception {
        String s = offerSql("selectAdminQueue", new HashMap<String, Object>());
        assertFalse(s.contains("o.status ="), "空参队列不该按 status 过滤(失效行要可见)。实际: " + s);
    }
}
