package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P0-02 产品真源的 mapper 侧守卫:两个新列必须真的读得回来、写得进去。
 *
 * <p>为什么值得单独一个类:加一列要同时改 resultMap / selectVo 列清单 / insert 列 / insert 值,
 * **漏任何一处都不报错**——resultMap 少一行只是字段恒 null,selectVo 少一列只是查不出来,
 * insert 少一处只是写不进去,全都静默。本仓库正好有过这个病(NodeDTO 的 sortID vs sortId 大小写
 * 不匹配 ⇒ 节点 sort_id 全 NULL,而两个 mapper 都 order by sort_id ⇒ 线性顺序真源根本不存在)。
 *
 * <p>用 MyBatis 真解析 XML,不连库。
 *
 * ⚠️ 负控已实测:注释掉 CmsTopicMapper.xml 的 product_type &lt;result&gt; ⇒ topicResultMap 红;
 *    从 selectCmsTopicVo 列清单删 product_type ⇒ topicSelectVo 红;
 *    删 insert 的 &lt;if&gt; ⇒ topicInsert 红。cms_registration 侧同理。
 */
class ProductTypeMapperSqlTest {

    private static final String TOPIC_MAPPER = "mapper/business/CmsTopicMapper.xml";
    private static final String REG_MAPPER = "mapper/business/CmsRegistrationMapper.xml";

    private Configuration parse(String resource, Class<?> alias) throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias(alias.getSimpleName(), alias);
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        }
        return cfg;
    }

    private String sql(Configuration cfg, String statementId, Object param) {
        return cfg.getMappedStatement(statementId)
                .getBoundSql(param)
                .getSql()
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }

    /** resultMap 里 property → column 的映射(找不到该 property 则 fail)。 */
    private String columnFor(Configuration cfg, String resultMapId, String property) {
        ResultMap rm = cfg.getResultMap(resultMapId);
        for (ResultMapping m : rm.getResultMappings()) {
            if (property.equals(m.getProperty())) {
                return m.getColumn();
            }
        }
        fail("resultMap " + resultMapId + " 缺 property=" + property + " 的映射 ⇒ 该字段查出来恒 null");
        return null;
    }

    // ---------------------------------------------------------------- cms_topic.product_type

    /** 读得回来:resultMap 有 productType → product_type 的映射。 */
    @Test
    void topicResultMap_mapsProductType() throws Exception {
        Configuration cfg = parse(TOPIC_MAPPER, CmsTopic.class);
        assertEquals("product_type", columnFor(cfg, "CmsTopicResult", "productType"));
    }

    /** 查得出来:selectVo 的列清单含 product_type(少了列,resultMap 映射对也没用)。 */
    @Test
    void topicSelectVo_selectsProductTypeColumn() throws Exception {
        Configuration cfg = parse(TOPIC_MAPPER, CmsTopic.class);
        String s = sql(cfg, "com.chengyinhub.business.mapper.CmsTopicMapper.selectCmsTopicList", new CmsTopic());
        assertTrue(s.contains("product_type"), "selectVo 列清单必须含 product_type。实际 SQL: " + s);
    }

    /** 写得进去:insert 带 product_type 列与值。 */
    @Test
    void topicInsert_writesProductType() throws Exception {
        Configuration cfg = parse(TOPIC_MAPPER, CmsTopic.class);
        CmsTopic t = new CmsTopic();
        t.setName("x");
        t.setProductType(2);

        String s = sql(cfg, "com.chengyinhub.business.mapper.CmsTopicMapper.insertCmsTopic", t);
        assertTrue(s.contains("product_type"), "insert 必须写 product_type,否则建题时产品真源恒空。实际 SQL: " + s);
    }

    /** 改得动:update 带 product_type(混票主题人工定型后要能改)。 */
    @Test
    void topicUpdate_writesProductType() throws Exception {
        Configuration cfg = parse(TOPIC_MAPPER, CmsTopic.class);
        CmsTopic t = new CmsTopic();
        t.setId(1L);
        t.setProductType(1);

        String s = sql(cfg, "com.chengyinhub.business.mapper.CmsTopicMapper.updateCmsTopic", t);
        assertTrue(s.contains("product_type"), "update 必须能改 product_type。实际 SQL: " + s);
    }

    /** 不传时不写(别把没传当成 NULL 覆盖掉已有值)。 */
    @Test
    void topicUpdate_withoutProductType_doesNotTouchColumn() throws Exception {
        Configuration cfg = parse(TOPIC_MAPPER, CmsTopic.class);
        CmsTopic t = new CmsTopic();
        t.setId(1L);
        t.setName("只改名");

        String s = sql(cfg, "com.chengyinhub.business.mapper.CmsTopicMapper.updateCmsTopic", t);
        assertTrue(!s.contains("product_type"), "未传 productType 时不得写该列。实际 SQL: " + s);
    }

    // ---------------------------------------------------------------- cms_registration.purchase_kind

    @Test
    void registrationResultMap_mapsPurchaseKind() throws Exception {
        Configuration cfg = parse(REG_MAPPER, CmsRegistration.class);
        assertEquals("purchase_kind", columnFor(cfg, "CmsRegistrationResult", "purchaseKind"));
    }

    @Test
    void registrationSelectVo_selectsPurchaseKindColumn() throws Exception {
        Configuration cfg = parse(REG_MAPPER, CmsRegistration.class);
        String s = sql(cfg, "com.chengyinhub.business.mapper.CmsRegistrationMapper.selectCmsRegistrationList",
                new CmsRegistration());
        assertTrue(s.contains("purchase_kind"), "selectVo 列清单必须含 purchase_kind。实际 SQL: " + s);
    }

    @Test
    void registrationInsert_writesPurchaseKind() throws Exception {
        Configuration cfg = parse(REG_MAPPER, CmsRegistration.class);
        CmsRegistration r = new CmsRegistration();
        r.setMemberId(1L);
        r.setPurchaseKind(2);

        String s = sql(cfg, "com.chengyinhub.business.mapper.CmsRegistrationMapper.insertCmsRegistration", r);
        assertTrue(s.contains("purchase_kind"),
                "insert 必须写 purchase_kind,否则下单固化的购买类型恒空。实际 SQL: " + s);
    }
}
