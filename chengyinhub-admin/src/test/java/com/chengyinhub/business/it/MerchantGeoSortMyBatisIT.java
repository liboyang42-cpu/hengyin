package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.dto.MerchantSearchQuery;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 MyBatis + 本地 MySQL 集成测试:验证 E5 商家搜索 geo 距离排序的真实 SQL
 * (Haversine 直线距离 + 无坐标排最后),mock 单测覆盖不到的 order by/空值语义。
 *
 * 本地库缺失时自动 skip(不破坏 CI/常规 mvn test)。手动跑法同 CoopMapperMyBatisIT:
 *   建库 chengyin_mybatis_it + ituser/itpass;本 IT 自建 mms_merchant 表,无需预置 schema。
 *   ⚠️ 2026-08-19 起改成**一类一库**:库由 MybatisItDatabase 自己 DROP+CREATE(见该类注释),
 *      本地不再需要预先建 chengyin_mybatis_it、也不再用 ituser —— 默认走 root(本机空口令),
 *      连接信息用 CHENGYIN_MYBATIS_IT_HOST/PORT/USER/PASSWORD 覆盖。
 *   mvn -pl chengyinhub-admin -am test -Dtest=MerchantGeoSortMyBatisIT
 */
class MerchantGeoSortMyBatisIT {

    private SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("MmsMerchant", MmsMerchant.class);
        PooledDataSource ds = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                MybatisItDatabase.url("merchant_geo"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
        String r = "mapper/business/MmsMerchantMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(r)) {
            new XMLMapperBuilder(in, cfg, r, cfg.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(cfg);
    }

    private MerchantSearchQuery merchant(String name, Double lat, Double lng) {
        MerchantSearchQuery m = new MerchantSearchQuery();
        m.setMemberId(1L);
        m.setName(name);
        m.setStatus(1L);
        m.setDelFlag(0);
        m.setLocationLat(lat);
        m.setLocationLng(lng);
        return m;
    }

    @Test
    void realMyBatis_merchantSearch_distanceSortAndCoordlessLast() throws Exception {
        SqlSessionFactory f;
        try {
            f = factory();
            try (SqlSession probe = f.openSession()) {
                probe.getConnection().isValid(2);
            }
        } catch (Throwable t) {
            // ★ 2026-08-14:原本是 Assumptions.assumeTrue(false, ...) —— 库连不上就整套 skip,
            // 而 skip 在构建里算**绿**。永远不会红的测试比没有测试更危险:它让人以为验过了。
            // 现在缺库 = 红。跑法见 ci/backend-test.sh(CI 的 backend job 自带 MySQL 服务)。
            throw new IllegalStateException("IT 前置不满足(库不可达 / Mapper XML / schema 配置有误),本套必须真跑不得跳过:" + t.getMessage(), t);
        }
        try (SqlSession s = f.openSession(true)) {
            java.sql.Statement st = s.getConnection().createStatement();
            st.execute("DROP TABLE IF EXISTS mms_merchant");
            st.execute("CREATE TABLE mms_merchant ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT, name VARCHAR(255), wechat VARCHAR(255),"
                    + "category_id INT, logo VARCHAR(500), description TEXT, derivatives TEXT, derivative_benefits VARCHAR(100), website VARCHAR(255),"
                    + "preference VARCHAR(255), phone VARCHAR(50), address VARCHAR(255), business_time VARCHAR(255),"
                    + "business_license VARCHAR(255), status BIGINT, reson VARCHAR(255), disable_reason VARCHAR(500), account_status BIGINT,"
                    + "business_status INT, del_flag INT, capacity INT, available_time VARCHAR(255),"
                    + "suit_activity_types VARCHAR(255), charge_type INT, demand TEXT, coop_open INT,"
                    + "cover_image VARCHAR(500), slogan VARCHAR(255), city_role VARCHAR(255), story_title VARCHAR(255),"
                    + "gallery TEXT, tags VARCHAR(500), featured_type INT, featured_id BIGINT,"
                    + "service_tag VARCHAR(8), service_text VARCHAR(64),"
                    + "location_lat DOUBLE, location_lng DOUBLE, location_verified INT,"
                    + "create_by VARCHAR(64), create_time DATETIME, update_by VARCHAR(64), update_time DATETIME)");

            MmsMerchantMapper mapper = s.getMapper(MmsMerchantMapper.class);

            // 上海人民广场附近(近)、北京(远,~1000km)、无坐标
            MerchantSearchQuery near = merchant("近店", 31.2315, 121.4750);
            MerchantSearchQuery far = merchant("远店", 39.9042, 116.4074);
            MerchantSearchQuery none = merchant("无坐标店", null, null);
            mapper.insertMmsMerchant(near);
            mapper.insertMmsMerchant(far);
            mapper.insertMmsMerchant(none);

            // ① 传定位 → 按距离升序,无坐标排最后
            MerchantSearchQuery q = new MerchantSearchQuery();
            q.setStatus(1L);
            q.setDelFlag(0);
            q.setOriginLat(31.2304);   // 人民广场
            q.setOriginLng(121.4737);
            List<MerchantSearchQuery> sorted = mapper.selectMerchantListByDistance(q);

            assertEquals(3, sorted.size(), "全集返回,不因无坐标丢行");
            assertEquals("近店", sorted.get(0).getName(), "最近的排第一");
            assertEquals("远店", sorted.get(1).getName(), "较远的排第二");
            assertEquals("无坐标店", sorted.get(2).getName(), "无坐标一律排最后");
            assertNotNull(sorted.get(0).getDistance());
            assertNotNull(sorted.get(1).getDistance());
            assertNull(sorted.get(2).getDistance(), "无坐标 distance 为 null");
            assertTrue(sorted.get(0).getDistance() < sorted.get(1).getDistance(), "距离严格升序");
            assertTrue(sorted.get(0).getDistance() < 5000, "近店距人民广场 < 5km(Haversine 数量级正确)");
            assertTrue(sorted.get(1).getDistance() > 900000, "远店(北京)距上海 > 900km");

            // ② 不传定位 → 原列表(无距离逻辑),全集含无坐标商家
            MmsMerchant plainQ = new MmsMerchant();
            plainQ.setStatus(1L);
            plainQ.setDelFlag(0);
            List<MmsMerchant> plain = mapper.selectMmsMerchantList(plainQ);
            assertEquals(3, plain.size(), "回退路径返回全部可见商家(含无坐标),行为零变");

            st.execute("DROP TABLE IF EXISTS mms_merchant");
        }
    }
}
