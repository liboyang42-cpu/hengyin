package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.UmsMemberAddress;
import com.chengyinhub.business.domain.vo.PublicMemberVO;
import com.chengyinhub.business.mapper.MemberInvitationMapper;
import com.chengyinhub.business.mapper.PublicMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberAddressMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.github.pagehelper.PageInterceptor;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-3B 真实 MyBatis + 本地 MySQL 负控。
 *
 * <p>只允许连接 127.0.0.1 的本地测试库；Mapper 在测试配置中被重写到本类专用表，
 * 不会 DROP/覆盖其他 IT 共用的 ums_member 等表。</p>
 */
class MemberPrivacyMyBatisIT {

    private static final String MEMBER_TABLE = "p13b_member_privacy_member";
    private static final String ADDRESS_TABLE = "p13b_member_privacy_address";
    private static final String CATEGORY_TABLE = "p13b_member_privacy_category";
    // RUN-13:PublicMemberMapper 的 level_id 改 join player_growth(coalesce 兜底 1),影子表同建。
    private static final String PLAYER_GROWTH_TABLE = "p13b_member_privacy_player_growth";
    /** 一类一库:见 MybatisItDatabase(首次取用即 DROP+CREATE,不与别的 IT 共用 schema)。 */
    private static final String URL = MybatisItDatabase.url("member_privacy");
    // 2026-08-29 显式新增 identityKind —— 搜索页身份标签(BIZ / CLUB)。
    // 这是从真库读回的投影，扩的是一个在 SQL 里就收敛好的封顶枚举:
    //   CASE WHEN user_type = 2 THEN 'BIZ' ELSE NULL END AS identity_kind
    // 账号级 user_type 没有被放出来，它仍与 password / phone / openId 一起在
    // ApiUserPublicInfoTest 的匿名禁止列表里。
    private static final Set<String> PUBLIC_KEYS = new TreeSet<>(Arrays.asList(
            "activityNum", "avatar", "caseIntroducton", "casePics", "fansNum",
            "followNum", "id", "identityKind", "introduction", "isFollow", "joinUserNum",
            "levelId", "likeNum", "nickname", "sysCategoryList", "topicNum"
    ));

    private SqlSessionFactory factoryOrSkip() throws Exception {
        try (Connection ignored = DriverManager.getConnection(URL, MybatisItDatabase.user(), MybatisItDatabase.password())) {
            // 只把本机 MySQL 网络不可达当作环境缺失；鉴权、schema、XML 与 SQL 错误必须红。
        } catch (SQLException e) {
            // ★ 2026-08-14:网络不可达原本 skip(=绿)。现在一律硬失败 —— 本套已进
            // ci/backend-test.sh 的必跑清单,缺库就该红,不该悄悄放行。
            throw new IllegalStateException("IT 前置不满足(库不可达 / Mapper XML / schema 配置有误),本套必须真跑不得跳过:" + e.getMessage(), e);
        }

        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("UmsMemberAddress", UmsMemberAddress.class);
        PageInterceptor pageInterceptor = new PageInterceptor();
        Properties properties = new Properties();
        properties.setProperty("helperDialect", "mysql");
        pageInterceptor.setProperties(properties);
        configuration.addInterceptor(pageInterceptor);

        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", URL, MybatisItDatabase.user(), MybatisItDatabase.password());
        configuration.setEnvironment(new Environment(
                "member-privacy-it", new JdbcTransactionFactory(), dataSource));
        parse(configuration, "mapper/business/PublicMemberMapper.xml");
        parse(configuration, "mapper/business/UmsMemberAddressMapper.xml");
        parse(configuration, "mapper/business/MemberInvitationMapper.xml");

        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession probe = factory.openSession()) {
            assertTrue(probe.getConnection().isValid(2));
        }
        return factory;
    }

    private void parse(Configuration configuration, String resource) throws Exception {
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            String xml;
            try (Scanner scanner = new Scanner(input, "UTF-8").useDelimiter("\\A")) {
                xml = scanner.hasNext() ? scanner.next() : "";
            }
            xml = xml.replace("ums_member_address", ADDRESS_TABLE)
                    .replace("ums_member", MEMBER_TABLE)
                    .replace("player_growth", PLAYER_GROWTH_TABLE)
                    .replace("sys_category", CATEGORY_TABLE);
            try (InputStream isolated = new ByteArrayInputStream(
                    xml.getBytes(StandardCharsets.UTF_8))) {
                new XMLMapperBuilder(isolated, configuration, resource,
                        configuration.getSqlFragments()).parse();
            }
        }
    }

    private void resetSchema(SqlSessionFactory factory) throws Exception {
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + ADDRESS_TABLE);
            statement.execute("DROP TABLE IF EXISTS " + MEMBER_TABLE);
            statement.execute("DROP TABLE IF EXISTS " + PLAYER_GROWTH_TABLE);
            statement.execute("DROP TABLE IF EXISTS " + CATEGORY_TABLE);
            statement.execute("CREATE TABLE " + PLAYER_GROWTH_TABLE + " ("
                    + "id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                    + "member_id BIGINT NOT NULL,"
                    + "level INT DEFAULT 1"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            statement.execute("CREATE TABLE " + MEMBER_TABLE + " ("
                    + "id BIGINT PRIMARY KEY,"
                    + "user_type BIGINT NOT NULL,"
                    + "status BIGINT NOT NULL,"
                    + "del_flag INT NOT NULL,"
                    + "invite_user_id VARCHAR(50),"
                    + "name VARCHAR(100),"
                    + "nickname VARCHAR(100),"
                    + "avatar VARCHAR(500),"
                    + "level_id BIGINT,"
                    + "introduction VARCHAR(500),"
                    + "case_pics TEXT,"
                    + "case_introducton VARCHAR(500),"
                    + "tag_ids VARCHAR(255),"
                    + "phone VARCHAR(50),"
                    + "password VARCHAR(255),"
                    + "wx_open_id VARCHAR(255),"
                    + "app_open_id VARCHAR(64),"
                    + "union_id VARCHAR(255),"
                    + "work_address VARCHAR(255),"
                    + "mark VARCHAR(255),"
                    + "create_time DATETIME,"
                    + "update_time DATETIME"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            statement.execute("CREATE TABLE " + ADDRESS_TABLE + " ("
                    + "id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                    + "member_id BIGINT NOT NULL,"
                    + "full_name VARCHAR(100),"
                    + "area_code VARCHAR(16),"
                    + "mobile_phone VARCHAR(50),"
                    + "province VARCHAR(255),"
                    + "detail_address VARCHAR(500),"
                    + "is_default INT NOT NULL DEFAULT 0,"
                    + "del_flag INT NOT NULL DEFAULT 0,"
                    + "request_id VARCHAR(64),"
                    + "create_by VARCHAR(64),"
                    + "create_time DATETIME,"
                    + "update_by VARCHAR(64),"
                    + "update_time DATETIME,"
                    + "UNIQUE KEY uk_member_address_request (member_id, request_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            statement.execute("CREATE TABLE " + CATEGORY_TABLE + " ("
                    + "id BIGINT PRIMARY KEY,"
                    + "category_name VARCHAR(100),"
                    + "sort_id BIGINT,"
                    + "status INT NOT NULL,"
                    + "del_flag INT NOT NULL,"
                    + "create_by VARCHAR(64),"
                    + "update_by VARCHAR(64),"
                    + "create_time DATETIME,"
                    + "update_time DATETIME"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

    private void insertMember(SqlSession session, long id, long status, int delFlag,
                              String inviterId, String nickname) throws Exception {
        String sql = "INSERT INTO " + MEMBER_TABLE + " "
                + "(id,user_type,status,del_flag,invite_user_id,name,nickname,avatar,level_id,"
                + "introduction,case_pics,case_introducton,tag_ids,phone,password,wx_open_id,"
                + "union_id,work_address,mark,create_time) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = session.getConnection().prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.setLong(2, 1);
            statement.setLong(3, status);
            statement.setInt(4, delFlag);
            statement.setString(5, inviterId);
            statement.setString(6, nickname + "姓名");
            statement.setString(7, nickname);
            statement.setString(8, "avatar-" + id);
            statement.setLong(9, 2);
            statement.setString(10, "公开简介");
            statement.setString(11, "公开作品图");
            statement.setString(12, "公开作品介绍");
            statement.setString(13, "1,2,3");
            statement.setString(14, "13800138000");
            statement.setString(15, "password-hash");
            statement.setString(16, "openid-secret");
            statement.setString(17, "unionid-secret");
            statement.setString(18, "公开工作地点");
            statement.setString(19, "内部治理备注");
            statement.setString(20, "2026-07-24 12:00:00");
            statement.executeUpdate();
        }
    }

    private long insertAddress(SqlSession session, long owner, int isDefault, int delFlag,
                               String name) throws Exception {
        String sql = "INSERT INTO " + ADDRESS_TABLE + " "
                + "(member_id,full_name,area_code,mobile_phone,province,detail_address,is_default,del_flag)"
                + " VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = session.getConnection()
                .prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, owner);
            statement.setString(2, name);
            statement.setString(3, "+86");
            statement.setString(4, "13800138000");
            statement.setString(5, "上海");
            statement.setString(6, name + "的详细地址");
            statement.setInt(7, isDefault);
            statement.setInt(8, delFlag);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    @Test
    void publicProjectionAndPaginationNeverExposeMemberEntitySecrets() throws Exception {
        SqlSessionFactory factory = factoryOrSkip();
        resetSchema(factory);
        try (SqlSession session = factory.openSession(true)) {
            insertMember(session, 1001L, 1L, 0, null, "第一页");
            insertMember(session, 1002L, 1L, 0, null, "第二页");
            insertMember(session, 1003L, 2L, 0, null, "已禁用");
            insertMember(session, 1004L, 1L, 2, null, "已删除");
            insertMember(session, 1005L, 1L, 0, null, "公开商户");
            session.getConnection().createStatement().executeUpdate(
                    "UPDATE " + MEMBER_TABLE + " SET user_type=2 WHERE id=1005");

            PageHelper.startPage(2, 1);
            List<PublicMemberVO> rows =
                    session.getMapper(PublicMemberMapper.class).selectPublicMemberList(1L, null);
            PageInfo<PublicMemberVO> page = new PageInfo<>(rows);

            assertEquals(2, page.getTotal(), "分页总数只统计启用且未删除的公开会员");
            assertEquals(1, rows.size(), "第二页固定一行");
            assertEquals(1001L, rows.get(0).getId());
            @SuppressWarnings("unchecked")
            Map<String, Object> json = new ObjectMapper().convertValue(rows.get(0), Map.class);
            assertEquals(PUBLIC_KEYS, new TreeSet<>(json.keySet()));
            String serialized = new ObjectMapper().writeValueAsString(rows.get(0));
            assertFalse(serialized.contains("13800138000"));
            assertFalse(serialized.contains("openid-secret"));
            assertFalse(serialized.contains("unionid-secret"));
            assertFalse(serialized.contains("第一页姓名"));
            assertFalse(serialized.contains("公开工作地点"));
            assertFalse(serialized.contains("2026-07-24"));
            assertFalse(serialized.contains("内部治理备注"));

            PageHelper.clearPage();
            List<PublicMemberVO> keywordRows =
                    session.getMapper(PublicMemberMapper.class)
                            .selectPublicMemberList(1L, "第一页");
            assertEquals(1, keywordRows.size(), "keyword 只允许按公开 nickname 搜索");
            assertTrue(session.getMapper(PublicMemberMapper.class)
                    .selectPublicMemberList(1L, "第一页姓名").isEmpty(),
                    "真实 name 不得成为公共搜索侧信道");

            List<PublicMemberVO> allPublicTypes =
                    session.getMapper(PublicMemberMapper.class).selectPublicMemberList(null, null);
            assertEquals(3, allPublicTypes.size(), "user_type=0 在 Service 映射为 null 后必须查询全部");

            Statement statement = session.getConnection().createStatement();
            statement.executeUpdate("INSERT INTO " + CATEGORY_TABLE
                    + " (id,category_name,sort_id,status,del_flag,create_by,update_by)"
                    + " VALUES (1,'公开标签',1,1,0,'internal','internal'),"
                    + "(2,'隐藏标签',2,2,0,'internal','internal'),"
                    + "(3,'已删标签',3,1,2,'internal','internal')");
            List<PublicMemberVO.PublicCategoryVO> categories =
                    session.getMapper(PublicMemberMapper.class)
                            .selectPublicCategoriesByIds(new String[]{"1", "2", "3"});
            assertEquals(1, categories.size(), "隐藏/已删分类不得出现在公开资料");
            @SuppressWarnings("unchecked")
            Map<String, Object> categoryJson =
                    new ObjectMapper().convertValue(categories.get(0), Map.class);
            assertEquals(new TreeSet<>(Arrays.asList("categoryName", "id")),
                    new TreeSet<>(categoryJson.keySet()));
        }
    }

    @Test
    void addressSqlUsesOwnerAndActivePredicatesForReadWriteDeleteAndDefault() throws Exception {
        SqlSessionFactory factory = factoryOrSkip();
        resetSchema(factory);
        try (SqlSession session = factory.openSession(true)) {
            long ownerA = 1001L;
            long ownerB = 2002L;
            long addressA = insertAddress(session, ownerA, 0, 0, "A");
            long defaultB = insertAddress(session, ownerB, 1, 0, "B-default");
            long deletedA = insertAddress(session, ownerA, 0, 2, "A-deleted");
            UmsMemberAddressMapper mapper = session.getMapper(UmsMemberAddressMapper.class);

            assertNull(mapper.selectOwnedAddressById(addressA, ownerB),
                    "B 不能读取 A 的地址，也不能探测其 PII");
            assertNull(mapper.selectOwnedAddressById(deletedA, ownerA),
                    "已删地址即使本人也必须 fail-closed");
            assertEquals(1, mapper.selectOwnedAddressList(ownerA).size(),
                    "列表只含本人未删除地址");

            UmsMemberAddress forgedUpdate = new UmsMemberAddress();
            forgedUpdate.setId(addressA);
            forgedUpdate.setFullName("B 越权覆盖");
            forgedUpdate.setIsDefault(1);
            assertEquals(0, mapper.updateOwnedAddress(forgedUpdate, ownerB),
                    "跨 owner 更新必须 0-row");
            assertEquals(0, mapper.deleteOwnedAddress(addressA, ownerB),
                    "跨 owner 删除必须 0-row");
            assertEquals(0, mapper.setOwnedAddressDefault(addressA, ownerB),
                    "跨 owner default 必须 0-row");
            assertEquals("A", scalarString(session.getConnection(),
                    "SELECT full_name FROM " + ADDRESS_TABLE + " WHERE id=" + addressA));
            assertEquals(0, scalarInt(session.getConnection(),
                    "SELECT del_flag FROM " + ADDRESS_TABLE + " WHERE id=" + addressA));
            assertEquals(1, scalarInt(session.getConnection(),
                    "SELECT is_default FROM " + ADDRESS_TABLE + " WHERE id=" + defaultB),
                    "0-row 失败不能顺手清掉 B 当前默认地址");

            assertEquals(0, mapper.updateOwnedAddress(forgedUpdate, ownerA + 99));
            assertEquals(0, mapper.deleteOwnedAddress(deletedA, ownerA));
            assertEquals(0, mapper.setOwnedAddressDefault(deletedA, ownerA));
        }
    }

    @Test
    void invitationSqlRejectsInvalidRebindAndAllowsOnlyOneConcurrentFirstBind() throws Exception {
        SqlSessionFactory factory = factoryOrSkip();
        resetSchema(factory);
        try (SqlSession session = factory.openSession(true)) {
            insertMember(session, 1001L, 1L, 0, null, "被邀请人");
            insertMember(session, 2002L, 1L, 0, null, "邀请人甲");
            insertMember(session, 2003L, 1L, 0, null, "邀请人乙");
            insertMember(session, 2004L, 2L, 0, null, "已禁用邀请人");
            insertMember(session, 2005L, 1L, 2, null, "已删除邀请人");

            MemberInvitationMapper mapper = session.getMapper(MemberInvitationMapper.class);
            assertEquals(0, mapper.bindInviterIfAbsent(1001L, 1001L), "禁止自邀请");
            assertEquals(0, mapper.bindInviterIfAbsent(1001L, 9999L), "不存在 inviter 必须 0-row");
            assertEquals(0, mapper.bindInviterIfAbsent(1001L, 2004L), "禁用 inviter 必须 0-row");
            assertEquals(0, mapper.bindInviterIfAbsent(1001L, 2005L), "已删 inviter 必须 0-row");
        }

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> concurrentBind(factory, ready, start, 2002L));
            Future<Integer> second = executor.submit(() -> concurrentBind(factory, ready, start, 2003L));
            ready.await();
            start.countDown();
            int firstRows = first.get();
            int secondRows = second.get();

            assertEquals(1, firstRows + secondRows, "两个并发首次绑定只能一个影响 1 行");
            try (SqlSession session = factory.openSession(true)) {
                String inviter = scalarString(session.getConnection(),
                        "SELECT invite_user_id FROM " + MEMBER_TABLE + " WHERE id=1001");
                assertTrue("2002".equals(inviter) || "2003".equals(inviter));
                long otherInviter = "2002".equals(inviter) ? 2003L : 2002L;
                assertEquals(0, session.getMapper(MemberInvitationMapper.class)
                        .bindInviterIfAbsent(1001L, otherInviter), "成功后禁止改绑");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private int concurrentBind(SqlSessionFactory factory, CountDownLatch ready,
                               CountDownLatch start, long inviterId) throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            ready.countDown();
            start.await();
            return session.getMapper(MemberInvitationMapper.class)
                    .bindInviterIfAbsent(1001L, inviterId);
        }
    }

    private int scalarInt(Connection connection, String sql) throws Exception {
        try (ResultSet result = connection.createStatement().executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private String scalarString(Connection connection, String sql) throws Exception {
        try (ResultSet result = connection.createStatement().executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
