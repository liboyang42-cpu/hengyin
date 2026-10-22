package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantReview;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * W10a #20:商家评价管理列表的服务端筛选与全量计数,跑真 SQL(H2 MySQL 模式)。
 *
 * <p>门店 7 有 6 条评价,同 owner 的门店 8 有 2 条会命中每一个筛选 —— 任何筛选/计数
 * 漏了 merchant_row_id 谓词,都会把 B 店数字混进来。
 */
class MerchantReviewManageFilterMyBatisTest {
    private static final String XML = "mapper/business/MerchantReviewMapper.xml";

    @Test
    void filtersAndCountsAreServerSideFullStoreAndStoreScoped() throws Exception {
        try (SqlSession session = factory().openSession(false)) {
            createSchema(session);
            seed(session);
            MerchantReviewMapper mapper = session.getMapper(MerchantReviewMapper.class);

            assertEquals(6L, mapper.countManageReviews(7L, "all"));
            assertEquals(ids(601, 603), idsOf(mapper.selectManageReviews(7L, "pending", 0, 20)),
                    "待回复 = 公开且未回复;待复核与已下架不算");
            assertEquals(ids(602, 604, 606), idsOf(mapper.selectManageReviews(7L, "low", 0, 20)),
                    "低分 = 评分 ≤ 3,不分状态");
            assertEquals(ids(601, 605), idsOf(mapper.selectManageReviews(7L, "photo", 0, 20)),
                    "有图 = images_json 非空数组");
            assertEquals(ids(602, 603), idsOf(mapper.selectManageReviews(7L, "platform", 0, 20)),
                    "平台复审中 = 待复核,或举报的审核任务仍在 0/3;已结案举报(604)不算");
            assertEquals(2L, mapper.countManageReviews(7L, "platform"));

            List<MerchantReview> firstPage = mapper.selectManageReviews(7L, "low", 0, 2);
            assertEquals(2, firstPage.size(), "筛选后仍按 limit 服务端分页");
            assertEquals(3L, mapper.countManageReviews(7L, "low"), "筛选 total 是全量,不受分页影响");

            Map<Long, Integer> flags = mapper.selectManageReviews(7L, "all", 0, 20).stream()
                    .collect(Collectors.toMap(MerchantReview::getId, MerchantReview::getUnderPlatformReview));
            assertEquals(Integer.valueOf(1), flags.get(602L));
            assertEquals(Integer.valueOf(1), flags.get(603L));
            assertEquals(Integer.valueOf(0), flags.get(604L));

            Map<String, Object> counts = mapper.selectManageFilterCounts(7L);
            assertEquals(6, number(counts, "all_count"));
            assertEquals(2, number(counts, "pending_count"));
            assertEquals(3, number(counts, "low_count"));
            assertEquals(2, number(counts, "photo_count"));
            assertEquals(2, number(counts, "platform_count"));

            Map<String, Object> other = mapper.selectManageFilterCounts(8L);
            assertEquals(2, number(other, "all_count"), "B 店计数只含 B 店");
            session.rollback();
        }
    }

    private static List<Long> ids(long... values) {
        return Arrays.stream(values).boxed().collect(Collectors.toList());
    }

    private static List<Long> idsOf(List<MerchantReview> rows) {
        return rows.stream().map(MerchantReview::getId).sorted().collect(Collectors.toList());
    }

    private static int number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? -1 : ((Number) value).intValue();
    }

    private SqlSessionFactory factory() throws Exception {
        Configuration configuration = new Configuration();
        UnpooledDataSource dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:merchant_review_filter;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        try (InputStream input = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(input, configuration, XML, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void createSchema(SqlSession session) throws Exception {
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("drop all objects");
            statement.execute("create table ums_member(id bigint primary key, nickname varchar(100), avatar varchar(255), "
                    + "status int, del_flag int)");
            statement.execute("create table merchant_review("
                    + "id bigint primary key, merchant_row_id bigint not null, merchant_owner_member_id bigint not null, "
                    + "registration_id bigint not null, redemption_id bigint not null, author_member_id bigint not null, "
                    + "rating int not null, content varchar(1000) not null, images_json varchar(2000) not null, "
                    + "status varchar(16) not null, reply_content varchar(500), reply_actor_member_id bigint, "
                    + "reply_actor_role_code varchar(32), reply_request_id varchar(64), reply_fingerprint varchar(64), "
                    + "replied_at timestamp, create_request_id varchar(64), create_fingerprint varchar(64), "
                    + "moderation_reason varchar(500), moderated_at timestamp, version int not null, "
                    + "create_time timestamp not null, update_time timestamp not null)");
            statement.execute("create table merchant_review_report(id bigint auto_increment primary key, "
                    + "review_id bigint, merchant_row_id bigint, merchant_owner_member_id bigint, "
                    + "reporter_member_id bigint, reporter_role_code varchar(32), reason_text varchar(500), "
                    + "request_id varchar(64), request_fingerprint varchar(64), audit_task_id bigint, create_time timestamp)");
            statement.execute("create table audit_task(id bigint primary key, status int not null)");
        }
    }

    private void seed(SqlSession session) throws Exception {
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("insert into ums_member values (101,'玩家甲','avatar-a',1,0)");
            String photo = "'[\"https://img/a.jpg\"]'";
            statement.execute("insert into merchant_review(id,merchant_row_id,merchant_owner_member_id,"
                    + "registration_id,redemption_id,author_member_id,rating,content,images_json,status,reply_content,"
                    + "version,create_time,update_time) values "
                    // 601 公开 5 星 有图 未回复 → pending, photo
                    + "(601,7,202,301,401,101,5,'好'," + photo + ",'VISIBLE',null,0,current_timestamp,current_timestamp),"
                    // 602 待复核 2 星 → low, platform(不算 pending)
                    + "(602,7,202,302,402,101,2,'差','[]','PENDING_REVIEW',null,0,current_timestamp,current_timestamp),"
                    // 603 公开 4 星 未回复 + 未结案举报(task 0) → pending, platform
                    + "(603,7,202,303,403,101,4,'还行','[]','VISIBLE',null,0,current_timestamp,current_timestamp),"
                    // 604 公开 3 星 已回复 + 已结案举报(task 1) → low only
                    + "(604,7,202,304,404,101,3,'一般','[]','VISIBLE','谢谢',0,current_timestamp,current_timestamp),"
                    // 605 已下架 5 星 有图 → photo only
                    + "(605,7,202,305,405,101,5,'不错'," + photo + ",'HIDDEN',null,0,current_timestamp,current_timestamp),"
                    // 606 公开 1 星 已回复 → low only
                    + "(606,7,202,306,406,101,1,'很差','[]','VISIBLE','抱歉',0,current_timestamp,current_timestamp),"
                    // 同 owner 的 B 店:每个筛选都会命中,用来抓漏掉门店谓词
                    + "(801,8,202,307,407,101,1,'B店'," + photo + ",'PENDING_REVIEW',null,0,current_timestamp,current_timestamp),"
                    + "(802,8,202,308,408,101,2,'B店','[]','VISIBLE',null,0,current_timestamp,current_timestamp)");
            statement.execute("insert into audit_task values (90,0),(91,1),(92,3)");
            statement.execute("insert into merchant_review_report(review_id,merchant_row_id,merchant_owner_member_id,"
                    + "reporter_member_id,reporter_role_code,reason_text,request_id,request_fingerprint,audit_task_id,create_time) values "
                    + "(603,7,202,101,'MEMBER','不实','r1','f1',90,current_timestamp),"
                    + "(604,7,202,101,'MEMBER','不实','r2','f2',91,current_timestamp),"
                    + "(802,8,202,101,'MEMBER','不实','r3','f3',92,current_timestamp)");
        }
    }
}
