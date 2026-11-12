package com.chengyinhub.business.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 地点合同的 SQL 层写口守卫（服务测里 mapper 全是 mock，宽口偷偷带上这一列那边照样全绿）。
 *
 * <p>判据：
 * <ul>
 *   <li>{@code updateCmsTopic} / {@code insertCmsTopic} <b>不得</b>出现 {@code content_contract_json}：
 *       那两条是「谁拿到 CmsTopic 谁就能写」的宽口，没有所有者、编辑窗口、父行锁与 bindingRevision 校验。
 *       留在宽口上 = 任何把 CmsTopic 绑进请求的入口都能绕过整套绑定校验。</li>
 *   <li>{@code updateTopicLocationContract} 必须同时在 SQL 里判 {@code member_id}，
 *       不能只依赖调用方先读后判。</li>
 *   <li>读取侧（resultMap + {@code selectCmsTopicVo}）保持，否则发布检查/作者读回拿不到合同。</li>
 * </ul>
 *
 * <p>天花板同其它 *MapperSqlTest：证明的是 SQL 长这样，不是 MySQL 跑起来对。
 */
class CmsTopicLocationContractWritePortTest {

    private static final String XML = "mapper/business/CmsTopicMapper.xml";
    private static final Pattern STATEMENT = Pattern.compile(
            "<(select|insert|update|delete)\\s+id=\"(\\w+)\"[^>]*>(.*?)</\\1>", Pattern.DOTALL);

    private static String xml() throws Exception {
        try (InputStream in = Resources.getResourceAsStream(XML)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String statement(String id) throws Exception {
        Matcher m = STATEMENT.matcher(xml());
        List<String> hits = new ArrayList<String>();
        while (m.find()) {
            if (id.equals(m.group(2))) hits.add(m.group(3));
        }
        assertEquals(1, hits.size(), "statement 应当恰好出现一次: " + id);
        return hits.get(0);
    }

    @Test
    void genericWritersDoNotTouchTheLocationContract() throws Exception {
        assertFalse(statement("insertCmsTopic").contains("content_contract_json"),
                "insertCmsTopic 是宽口：不得写地点合同");
        assertFalse(statement("updateCmsTopic").contains("content_contract_json"),
                "updateCmsTopic 是宽口：不得写地点合同");
    }

    @Test
    void dedicatedWritePortIsOwnerScoped() throws Exception {
        String sql = statement("updateTopicLocationContract");
        assertTrue(sql.contains("content_contract_json = #{contentContractJson}"));
        assertTrue(sql.contains("member_id = #{ownerMemberId}"),
                "owner 必须在 SQL 里判，不能只靠调用方先读后判");
        assertTrue(sql.contains("where id = #{id}"));
        assertFalse(sql.contains("config_version"),
                "bindingRevision 由专用服务在父行锁内做 CAS，不借用路线版本列");
    }

    @Test
    void readPathKeepsTheContractColumn() throws Exception {
        String vo = xml();
        int voStart = vo.indexOf("<sql id=\"selectCmsTopicVo\">");
        assertTrue(voStart > 0, "缺少 selectCmsTopicVo");
        String voBody = vo.substring(voStart, vo.indexOf("</sql>", voStart));
        assertTrue(voBody.contains("content_contract_json"), "读侧必须能读回合同");
        assertTrue(vo.contains("<result property=\"contentContractJson\" column=\"content_contract_json\""),
                "resultMap 必须映射合同列");
    }

    @Test
    void mapperInterfaceDeclaresTheDedicatedPort() throws Exception {
        Configuration cfg = new Configuration();
        // 生产由 mybatis-plus 的 typeAliasesPackage 注册别名;裸 Configuration 不注册,
        // resultMap 里的 type="CmsTopic" 就会 ClassNotFound。补同一包别名,验的才是 XML 本身。
        cfg.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        assertEquals(com.chengyinhub.business.domain.CmsTopic.class,
                cfg.getTypeAliasRegistry().resolveAlias("CmsTopic"),
                "别名装置必须和生产的 typeAliasesPackage 同源，否则解析的是另一份 XML 语义");
        try (InputStream in = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(in, cfg, XML, cfg.getSqlFragments()).parse();
        }
        assertTrue(cfg.hasStatement("com.chengyinhub.business.mapper.CmsTopicMapper.updateTopicLocationContract"),
                "XML 里必须注册该语句");
        Method method = CmsTopicMapper.class.getMethod("updateTopicLocationContract",
                Long.class, Long.class, String.class, String.class, java.util.Date.class);
        assertEquals(int.class, method.getReturnType(), "写口返回受影响行数，调用方必须据此报错");
    }
}
