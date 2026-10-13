package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.PmsSku;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** MyBatis 真解析 mapped statement，防止 SKU 归属条件只停留在 mock 断言。 */
class PmsSkuMapperStockContractTest {

    @Test
    void deductStockForProduct_bindsSkuProductAndStockConditions() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias(PmsSku.class.getSimpleName(), PmsSku.class);
        String resource = "mapper/business/PmsSkuMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        Map<String, Object> parameter = new HashMap<String, Object>();
        parameter.put("id", 300L);
        parameter.put("productId", 1L);
        parameter.put("qty", 2L);
        String sql = configuration.getMappedStatement("com.chengyinhub.business.mapper.PmsSkuMapper.deductStockForProduct")
                .getBoundSql(parameter).getSql().replaceAll("\\s+", " ").toLowerCase();

        assertTrue(sql.contains("where id = ?") && sql.contains("product_id = ?") && sql.contains("stock >= ?"),
                "原子扣库必须同时绑定 sku、商品归属与库存条件。实际 SQL: " + sql);
    }
}
