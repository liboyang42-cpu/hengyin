package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.PmsProduct;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** G5-10f 商品目录的 mapper 约束：真 XML 生成 BoundSql，不连库、不 mock。 */
class PmsProductMapperSqlTest {

    private static final String XML = "mapper/business/PmsProductMapper.xml";
    private static final String NAMESPACE = "com.chengyinhub.business.mapper.PmsProductMapper.";

    private Configuration parse() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias(PmsProduct.class.getSimpleName(), PmsProduct.class);
        try (InputStream in = Resources.getResourceAsStream(XML))
        {
            new XMLMapperBuilder(in, configuration, XML, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private String sql(String statement, Object parameter) throws Exception {
        return parse().getMappedStatement(NAMESPACE + statement)
                .getBoundSql(parameter)
                .getSql()
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }

    @Test
    void liveCatalogListIncludesLegacyNullDelFlagRows() throws Exception {
        PmsProduct query = new PmsProduct();
        query.setDelFlag(0);

        assertTrue(sql("selectPmsProductList", query).contains("(del_flag = 0 or del_flag is null)"),
                "历史 del_flag 为 NULL 的在售商品必须继续可达");
    }

    @Test
    void deletedCatalogListDoesNotReuseLiveNullCompatibility() throws Exception {
        PmsProduct query = new PmsProduct();
        query.setDelFlag(2);
        String sql = sql("selectPmsProductList", query);

        assertTrue(sql.contains("del_flag = ?"), "查询已删除记录必须精确按删除标记过滤");
        assertFalse(sql.contains("del_flag is null"), "已删除查询不能把历史 NULL 误作已删除");
    }

    @Test
    void statusWriteUsesLiveRowCompareAndSetWithoutInventingCreatorScope() throws Exception {
        Map<String, Object> parameter = new HashMap<String, Object>();
        parameter.put("id", 7L);
        parameter.put("expectedPublishStatus", 2);
        parameter.put("publishStatus", 0);
        parameter.put("updateBy", "catalog-operator");
        parameter.put("updateTime", new Date());
        String sql = sql("updatePmsProductPublishStatus", parameter);

        assertTrue(sql.contains("where id = ? and (del_flag = 0 or del_flag is null) and publish_status = ?"),
                "状态更新必须同时约束 ID、存活标记和读取时状态，防止并发覆盖");
        assertFalse(sql.contains("create_by = ?"),
                "迁移不能凭不完整的历史 create_by 字段擅自收紧既有商品编辑权限");
    }
}
