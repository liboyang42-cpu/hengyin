package com.chengyinhub.business.marketplace;

import com.chengyinhub.business.mapper.MarketplaceMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parses MarketplaceMapper.xml against the interface, checks the privacy and safety shape of the rendered SQL,
 * and runs the portable claim statements on H2 (MySQL mode). MySQL-only functions (date_format, greatest,
 * find_in_set) need the MySQL integration environment.
 */
class MarketplaceMapperMyBatisTest {

    private Configuration configuration(DriverManagerDataSource ds) throws Exception {
        Configuration configuration = new Configuration(new Environment("mkt", new JdbcTransactionFactory(), ds));
        configuration.addMapper(MarketplaceMapper.class);
        String resource = "mapper/business/MarketplaceMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("missing " + resource);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private DriverManagerDataSource h2() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:mkt_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("create table marketplace_deal_claim (id bigint auto_increment primary key, coupon_id bigint not null,"
                    + " member_id bigint not null, history_id bigint, create_time timestamp not null,"
                    + " constraint uk_deal_member unique (coupon_id, member_id))");
        }
        return ds;
    }

    private static String sql(Configuration configuration, String id, Map<String, Object> params) {
        BoundSql bound = configuration.getMappedStatement(MarketplaceMapper.class.getName() + "." + id).getBoundSql(params);
        return bound.getSql().replaceAll("\\s+", " ").toLowerCase();
    }

    @Test
    void everyStatementParsesAndRenders() throws Exception {
        Configuration configuration = configuration(h2());
        Map<String, Object> p = new HashMap<>();
        p.put("minLat", 31.2); p.put("maxLat", 31.3); p.put("minLng", 121.4); p.put("maxLng", 121.5);
        p.put("memberId", 7L); p.put("limit", 100); p.put("couponId", 5L); p.put("historyId", 9L);
        p.put("enabled", 1); p.put("operator", "op"); p.put("since", new Date()); p.put("minVisitors", 2);
        p.put("merchantId", 3L);
        for (String id : List.of("selectDealsInBox", "selectEnabledDealCouponForUpdate", "insertClaimIgnore",
                "selectClaimHistoryId", "markClaimDelivered", "selectDealAdminList", "upsertDeal", "deleteDeal",
                "countListableCoupon", "selectPromotionSlotPurchases", "selectSalesLeads",
                "selectPublishedProducts", "selectPublishedSkus")) {
            assertFalse(sql(configuration, id, p).isBlank(), id);
        }
    }

    @Test
    void dealsNeverTouchFogAndOnlyShowOptedInIssuableCouponsAtPublicShops() throws Exception {
        String s = sql(configuration(h2()), "selectDealsInBox", Map.of("minLat", 31.2, "maxLat", 31.3,
                "minLng", 121.4, "maxLng", 121.5, "memberId", 7L, "limit", 100));
        assertFalse(s.contains("fog"));
        assertTrue(s.contains("from marketplace_deal d"));
        assertTrue(s.contains("d.enabled = 1"));
        assertTrue(s.contains("c.scope_type = 1"));
        assertTrue(s.contains("ifnull(c.receive_count, 0) < c.publish_count"));
        assertTrue(s.contains("m.status = 1 and m.account_status = 1 and m.del_flag = 0"));
        assertTrue(s.contains("p2.type = 2 and p2.status = 1"));
    }

    @Test
    void leadsCarryNoPersonalDataAndNeedTwoVisitors() throws Exception {
        String s = sql(configuration(h2()), "selectSalesLeads", Map.of("since", new Date(), "minVisitors", 2, "limit", 200));
        String projection = s.substring(0, s.indexOf(" from ("));
        assertFalse(projection.contains("member_id"));
        assertFalse(s.contains("nickname"));
        assertFalse(s.contains("phone"));
        assertTrue(projection.contains("round(g.lat, 2)"));
        assertTrue(s.contains("having count(*) >= ?"));
        assertTrue(s.contains("not exists ( select 1 from roam_poi p"));
    }

    @Test
    void promotionSlotPurchasesListOnlyPaidPromotionSlotOrders() throws Exception {
        String s = sql(configuration(h2()), "selectPromotionSlotPurchases", Map.of());
        assertTrue(s.contains("o.biz_type = 'promotion_slot' and o.status = 1"));
        assertTrue(s.contains("merchant_commerce_refund"));
    }

    @Test
    void oneClaimPerMemberPerOfferOnH2() throws Exception {
        DriverManagerDataSource ds = h2();
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration(ds));
        try (SqlSession session = factory.openSession(true)) {
            MarketplaceMapper mapper = session.getMapper(MarketplaceMapper.class);
            assertEquals(1, mapper.insertClaimIgnore(5L, 7L));
            assertEquals(0, mapper.insertClaimIgnore(5L, 7L));
            assertEquals(1, mapper.insertClaimIgnore(5L, 8L));
            assertNull(mapper.selectClaimHistoryId(5L, 7L));
            assertEquals(1, mapper.markClaimDelivered(5L, 7L, 99L));
            assertEquals(0, mapper.markClaimDelivered(5L, 7L, 100L));
            assertEquals(99L, mapper.selectClaimHistoryId(5L, 7L));
        }
    }
}
