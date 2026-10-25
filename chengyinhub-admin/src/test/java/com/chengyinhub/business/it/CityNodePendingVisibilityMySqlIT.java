package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.vo.CityNodeVO;
import com.chengyinhub.business.mapper.MemberCollectibleMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.ApiCityNodeController;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * R9-41 真库验证:待审/驳回的商家据点不得再向普通玩家返回完整展示内容。
 *
 * <p>审查原样(2026-09-13):投放 5 待审/驳回时普通玩家 {@code /api/city/nodes/5} 仍 200,
 * 名称/说明/坐标/模板全量下发。基线 {@code f178b58ae} 已加
 * {@code cityNodeContentApproved} 过滤(application_type=1 必须 audit_status=1),
 * 本类在真实 MySQL 上用真实 Mapper XML 把边界逐档钉住,不再拿"看着像修了"当结论。</p>
 *
 * <p><b>2026-09-15 假绿修复。</b>原版种子里待审①/驳回②都是 {@code status=0},410 由上下线条件
 * 贡献,审核闸(<b>RoamMapper.xml 的 cityNodeContentApproved</b>)在这两行上根本没被执行 ——
 * 把该 include 换成 {@code 1=1} 后本类仍 1/1 绿。现补 ⑦⑧⑨ 三行 {@code status=1} 的
 * 待审/驳回/下线种子:它们只有审核闸能挡,删闸即红(负控见修复报告)。</p>
 *
 * <p>同时登记进 {@code ci/backend-test.sh} 的 *IT 点名清单:不点名的话 Maven 默认 include
 * 收不到 *IT,CI 永远跑不到它({@code assert_it_ran.py} 回读 skipped=0 才收绿)。</p>
 *
 * <p>一类一库:自带唯一后缀的 scratch schema,跑完 DROP 并回读 0。</p>
 */
class CityNodePendingVisibilityMySqlIT {

    private static final long PLAYER = 9001L;

    private SqlSession session;

    @Test
    void pendingSeededAndApprovedNodesRespectPlayerVisibilityBoundary() throws Exception {
        String database = MybatisItDatabase.databaseNameAfterCreate(
                "city_node_pending_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        String url = "jdbc:mysql://" + MybatisItDatabase.host() + ":" + MybatisItDatabase.port()
                + "/" + database + "?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true";
        try {
            createSchemaAndSeed(url);
            ApiCityNodeController controller = controller(url);
            // ① 待审投放(未上线):status=0 与审核闸两道都不放行
            assertEquals(410, code(controller.detail(11L)), "待审投放不能向普通玩家下发展示内容");
            // ② 驳回投放(未上线):同上
            assertEquals(410, code(controller.detail(12L)), "驳回投放不能向普通玩家下发展示内容");
            // ③ 已批准但下线:没有到店记录就不可见(审查里"下线 6 仍可读"的档)
            assertEquals(410, code(controller.detail(13L)), "已下线据点对未到店玩家必须不可见");
            // ④ 正控:已批准且在架 → 正常可见
            assertEquals(200, code(controller.detail(14L)), "已批准在架据点必须正常可见");
            // ⑤ 正控:历史遗留(application_type is null)在架据点不受新闸误伤
            assertEquals(200, code(controller.detail(15L)), "历史无申请类型据点必须保持可见");
            // ⑥ 认领待审(app_type=2)不是内容准入:该据点此前已平台审核上架,认领审核不改内容,
            //    撤下它等于因为某商家认领被驳回而关掉一个已公开店铺。此档保持可见,见报告不确定清单。
            assertEquals(200, code(controller.detail(16L)), "认领审核不应撤下已平台审核上架的据点");
            // ★ ⑦⑧⑨ 是「删掉审核闸也全绿」那版假绿的对症种子:status=1(投放已上线)时,
            //    唯一能挡住待审/驳回/下线的就是 RoamMapper.xml 的 cityNodeContentApproved。
            //    没有这三档,前三条的 410 全部由 status=0 贡献,审核闸测不到。
            assertEquals(410, code(controller.detail(17L)), "已上线但审核待审的投放不能下发内容");
            assertEquals(410, code(controller.detail(18L)), "已上线但审核驳回的投放不能下发内容");
            assertEquals(410, code(controller.detail(19L)), "已上线但审核已下线的投放不能下发内容");
        } finally {
            if (session != null) session.close();
            dropAndVerifyGone(database);
        }
    }

    private int code(AjaxResult result) {
        Object value = result.get(AjaxResult.CODE_TAG);
        return value == null ? -1 : Integer.parseInt(String.valueOf(value));
    }

    private ApiCityNodeController controller(String url) throws Exception {
        RoamMapper roamMapper = mysqlMapper(url);
        ApiCityNodeController controller = new ApiCityNodeController() {
            @Override
            public Long getAppUserId() {
                return PLAYER;
            }
        };
        ReflectionTestUtils.setField(controller, "roamMapper", roamMapper);
        ReflectionTestUtils.setField(controller, "collectibleMapper", mock(MemberCollectibleMapper.class));
        ReflectionTestUtils.setField(controller, "dynSecret", "it-secret");
        return controller;
    }

    private RoamMapper mysqlMapper(String url) throws Exception {
        UnpooledDataSource dataSource =
                new UnpooledDataSource("com.mysql.cj.jdbc.Driver", url, MybatisItDatabase.user(),
                        MybatisItDatabase.password());
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("it", new JdbcTransactionFactory(), dataSource));
        // 生产靠 mybatis.typeAliasesPackage 扫描域别名;这里手注册 XML 里用到的短别名。
        configuration.getTypeAliasRegistry().registerAlias("RoamPoi", com.chengyinhub.business.domain.RoamPoi.class);
        configuration.getTypeAliasRegistry().registerAlias("RoamSession", com.chengyinhub.business.domain.RoamSession.class);
        String resource = Paths.get("").toAbsolutePath().getParent()
                + "/chengyinhub-system/src/main/resources/mapper/business/RoamMapper.xml";
        try (InputStream input = new FileInputStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        return session.getMapper(RoamMapper.class);
    }

    private void createSchemaAndSeed(String url) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, MybatisItDatabase.user(),
                MybatisItDatabase.password()); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE roam_poi (id BIGINT PRIMARY KEY, name VARCHAR(128), type INT, "
                    + "lat DECIMAL(10,6), lng DECIMAL(10,6), radius_m INT, description VARCHAR(512), "
                    + "city_code VARCHAR(32), status INT, audit_status INT, application_type INT, "
                    + "applicant_merchant_id BIGINT, merchant_id BIGINT, template_id BIGINT, node_level INT, "
                    + "tags VARCHAR(255), cover_img VARCHAR(255), sort INT) ENGINE=InnoDB");
            statement.execute("CREATE TABLE mms_merchant (id BIGINT PRIMARY KEY, name VARCHAR(128), "
                    + "logo VARCHAR(255), address VARCHAR(255), phone VARCHAR(32)) ENGINE=InnoDB");
            statement.execute("CREATE TABLE cms_member_template (id BIGINT PRIMARY KEY, title VARCHAR(128), "
                    + "interaction_type INT, validation_method INT, coupon_id BIGINT, question_name VARCHAR(255), "
                    + "question_a VARCHAR(255), question_b VARCHAR(255), question_c VARCHAR(255), "
                    + "question_d VARCHAR(255)) ENGINE=InnoDB");
            statement.execute("CREATE TABLE roam_poi_reward_log (poi_id BIGINT NOT NULL, member_id BIGINT NOT NULL, "
                    + "coupon_id BIGINT, status INT NOT NULL) ENGINE=InnoDB");

            statement.execute("INSERT INTO mms_merchant VALUES (801,'城瘾咖啡馆','logo.png','某路 1 号','13800000000')");
            statement.execute("INSERT INTO cms_member_template VALUES (901,'拍下钟面',1,5,0,'问题','A','B','C','D')");
            statement.execute("INSERT INTO roam_poi VALUES "
                    // ① 待审投放(未上线,application_type=1)
                    + "(11,'待审投放',2,31.1,121.1,80,'待审内容','310000',0,0,1,801,NULL,901,1,'','',0),"
                    // ② 驳回投放(未上线)
                    + "(12,'驳回投放',2,31.2,121.2,80,'驳回内容','310000',0,2,1,801,NULL,901,1,'','',0),"
                    // ③ 已批准但下线 + 无到店记录
                    + "(13,'已批准下线',2,31.3,121.3,80,'下线内容','310000',0,1,1,801,801,901,1,'','',0),"
                    // ④ 已批准在架
                    + "(14,'在架据点',2,31.4,121.4,80,'在架内容','310000',1,1,1,801,801,901,1,'','',0),"
                    // ⑤ 历史遗留(application_type is null)在架
                    + "(15,'历史据点',2,31.5,121.5,80,'历史内容','310000',1,NULL,NULL,NULL,801,901,1,'','',0),"
                    // ⑥ 商家认领待审:平台早已审核上架,认领审核不改内容
                    + "(16,'认领待审',2,31.6,121.6,80,'认领内容','310000',1,0,2,801,NULL,901,1,'','',0),"
                    // ⑦⑧⑨ 已上线(status=1)但内容审核未通过的三档 —— 只有审核闸能挡,见上文 ★
                    + "(17,'在线待审投放',2,31.7,121.7,80,'在线待审内容','310000',1,0,1,801,801,901,1,'','',0),"
                    + "(18,'在线驳回投放',2,31.8,121.8,80,'在线驳回内容','310000',1,2,1,801,801,901,1,'','',0),"
                    + "(19,'在线已下线投放',2,31.9,121.9,80,'在线下线内容','310000',1,3,1,801,801,901,1,'','',0)");
            statement.execute("ALTER TABLE roam_poi ADD address varchar(255), ADD gallery_json text, ADD quotes_json text, ADD photo_credit text, ADD photo_required int, ADD badge_code varchar(80)");
            statement.execute("ALTER TABLE cms_member_template ADD photo_require_desc varchar(255), ADD description varchar(255)");
            statement.execute("CREATE TABLE ums_comment (owner_type bigint, owner_id bigint, rating int, reply_id bigint, status int, del_flag int)");
        }
    }

    private void dropAndVerifyGone(String database) throws Exception {
        String adminUrl = "jdbc:mysql://" + MybatisItDatabase.host() + ":" + MybatisItDatabase.port()
                + "/?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection connection = DriverManager.getConnection(adminUrl, MybatisItDatabase.user(),
                MybatisItDatabase.password()); Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='" + database + "'")) {
                rows.next();
                assertEquals(0, rows.getInt(1), "scratch 库必须已 DROP,回读必须为 0");
            }
        }
        System.out.println("CITY_NODE_SCRATCH_DROPPED=true db=" + database);
    }
}
