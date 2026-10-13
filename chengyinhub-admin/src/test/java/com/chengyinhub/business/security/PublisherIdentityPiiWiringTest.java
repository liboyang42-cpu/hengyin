package com.chengyinhub.business.security;

import com.chengyinhub.business.domain.UmsMemberIdentity;
import com.chengyinhub.web.controller.api.ApiMemberIdentityController;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实名登记这条 PII 通路上三道「一改就会悄悄漏」的接线。
 *
 * <p>① mapper XML 必须给 real_name / id_card 挂 PiiCryptoTypeHandler,并且 id_card_hash
 * 由同一份明文走 PiiBlindIndexTypeHandler。这三处任何一处被"顺手简化"掉,写进库的就是明文,
 * 而线上不会报错 —— 它只会安静地存一堆明文身份证号。typeHandler 是挂在 XML 上的,
 * 注解、实体、单测都察觉不到,所以只能钉源码文本。
 *
 * <p>② 登记接口不许挂 {@code @Log}。RuoYi 的操作日志把入参整份 JSON 落进 sys_oper_log,
 * 那张表既不加密也不脱敏,挂上等于把明文身份证号抄一份进后台人人能看的日志页。
 *
 * <p>③ 承载明文的那几个对象不许有可读 toString():一行 log.info("{}", identity) 就够泄露。
 */
class PublisherIdentityPiiWiringTest {

    private static final String MAPPER_XML_RELATIVE =
            "chengyinhub-system/src/main/resources/mapper/business/UmsMemberIdentityMapper.xml";

    @Test
    void 读写两侧都挂着_PII_加密_typeHandler() throws IOException {
        String xml = flatten(mapperXml());
        assertTrue(xml.contains("property=\"realName\"column=\"real_name\"typeHandler=\"com.chengyinhub.business.security.PiiCryptoTypeHandler\""),
                "real_name 读侧必须经 PiiCryptoTypeHandler,否则查出来的是密文");
        assertTrue(xml.contains("property=\"idCard\"column=\"id_card\"typeHandler=\"com.chengyinhub.business.security.PiiCryptoTypeHandler\""),
                "id_card 读侧同上");
        assertTrue(xml.contains("#{realName,typeHandler=com.chengyinhub.business.security.PiiCryptoTypeHandler}"),
                "insert 的姓名必须带加密 typeHandler");
        assertTrue(xml.contains("#{idCard,typeHandler=com.chengyinhub.business.security.PiiCryptoTypeHandler}"),
                "insert 的身份证号必须带加密 typeHandler");
        // 盲索引与密文同源:两列都从 #{idCard} 这一个明文参数算出来,客户端塞不进伪造的 hash
        assertTrue(xml.contains("#{idCard,typeHandler=com.chengyinhub.business.security.PiiBlindIndexTypeHandler}"),
                "id_card_hash 必须由明文经盲索引生成");
        assertTrue(xml.contains("deletefromums_member_identity"),
                "注销必须把这张表整行删掉,而不是留着");
    }

    /** 明文身份证号不许作为字面量出现在这条通路的 SQL 里(示例值、兜底值、日志模板)。 */
    @Test
    void SQL_里没有写死过任何身份证号() throws IOException {
        assertFalse(mapperXml().contains("110105"), "mapper 里不该出现真实形态的身份证号");
    }

    /**
     * 注销必须真的把这张表算进清除清单。服务层调用是 mock 出来的(见 AccountDeregistrationServiceImplTest),
     * 只有这条钉得住「SQL 里那一行确实存在」——少了它,注销会静默留下明文登记。
     */
    @Test
    void 注销清名单里点名了实名登记表() throws IOException {
        Path path = moduleRelative("chengyinhub-system/src/main/resources/mapper/business/AccountDeregistrationMapper.xml");
        String xml = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        assertTrue(xml.contains("id=\"deleteMemberIdentity\"") && xml.contains("ums_member_identity"),
                "注销 SQL 必须整行删除 ums_member_identity");
    }

    @Test
    void 登记接口的每一条方法都没有操作日志标注() {
        for (Method method : ApiMemberIdentityController.class.getDeclaredMethods()) {
            for (Annotation annotation : method.getAnnotations()) {
                assertFalse(annotation.annotationType().getName().endsWith(".Log"),
                        "方法 " + method.getName() + " 挂了 @Log 会把明文身份证号写进 sys_oper_log");
            }
        }
    }

    /** 域对象与提交 DTO 刻意不写 toString();谁哪天补上,这两条就把他挡回去。 */
    @Test
    void 承载明文的对象不打印自己() {
        UmsMemberIdentity identity = new UmsMemberIdentity();
        identity.setRealName("张三");
        identity.setIdCard(ID);
        assertFalse(identity.toString().contains(ID),
                "toString 里出现身份证号 = 任何一次日志打印都在漏 PII");

        com.chengyinhub.business.domain.dto.MemberIdentityRequest request =
                new com.chengyinhub.business.domain.dto.MemberIdentityRequest();
        assertTrue(Serializable.class.isAssignableFrom(request.getClass()));
        request.setIdCard(ID);
        assertFalse(request.toString().contains(ID));
    }

    @Test
    void 状态SQL只按本人登记行返回布尔值不读取敏感字段() throws Exception {
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("test",
                new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),
                new org.apache.ibatis.datasource.pooled.PooledDataSource("org.h2.Driver",
                        "jdbc:h2:mem:identity_status;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
        try (java.io.InputStream in = org.apache.ibatis.io.Resources.getResourceAsStream(
                "mapper/business/UmsMemberIdentityMapper.xml")) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(in, configuration,
                    "mapper/business/UmsMemberIdentityMapper.xml", configuration.getSqlFragments()).parse();
        }
        String sql = configuration.getMappedStatement(
                "com.chengyinhub.business.mapper.UmsMemberIdentityMapper.existsByMemberId")
                .getBoundSql(8801L).getSql().toLowerCase();
        assertFalse(sql.contains("real_name") || sql.contains("id_card") || sql.contains("select *"));
        try (org.apache.ibatis.session.SqlSession session =
                     new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration).openSession(true);
             java.sql.Statement statement = session.getConnection().createStatement()) {
            statement.execute("create table ums_member_identity (member_id bigint primary key, "
                    + "real_name varchar(80), id_card varchar(80))");
            statement.execute("insert into ums_member_identity values (8801, 'v1:unreadable', 'v1:unreadable')");
            com.chengyinhub.business.mapper.UmsMemberIdentityMapper mapper =
                    session.getMapper(com.chengyinhub.business.mapper.UmsMemberIdentityMapper.class);
            assertTrue(mapper.existsByMemberId(8801L));
            assertFalse(mapper.existsByMemberId(8802L));
        }
    }

    private static final String ID = "110105199003070017";

    private static String mapperXml() throws IOException {
        return new String(Files.readAllBytes(moduleRelative(MAPPER_XML_RELATIVE)), StandardCharsets.UTF_8);
    }

    /**
     * surefire 的工作目录是 chengyinhub-admin/,而这两张 XML 在 chengyinhub-system/。
     * 找不到时必须判红并报出绝对路径 —— 路径变了就悄悄 skip 的测试等于没有测试。
     */
    private static Path moduleRelative(String repoRelativePath) {
        Path fromModule = Paths.get("..", repoRelativePath).normalize();
        Path fromRoot = Paths.get(repoRelativePath);
        Path path = Files.exists(fromModule) ? fromModule : fromRoot;
        assertTrue(Files.exists(path), "源文件路径变了就得改这条断言:" + path.toAbsolutePath());
        return path;
    }

    /** XML 里 typeHandler 属性是折行写的,压掉空白再比,免得格式化一下就把门禁弄假红。 */
    private static String flatten(String xml) {
        return xml.replaceAll("\\s+", "");
    }
}
