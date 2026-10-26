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
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MerchantReviewMerchantRowMyBatisTest {
    private static final String XML = "mapper/business/MerchantReviewMapper.xml";

    @Test
    void sameOwnerStoresRemainIsolatedAndLegacyRedemptionIsNotGuessed() throws Exception {
        try (SqlSession session = factory().openSession(false)) {
            createSchema(session);
            seed(session);
            MerchantReviewMapper mapper = session.getMapper(MerchantReviewMapper.class);

            assertEquals(1L, mapper.countPublicReviews(7L));
            assertEquals(1L, mapper.countManageReviews(7L, "all"));
            List<Long> publicIds = mapper.selectPublicReviews(7L, 0, 20).stream()
                    .map(MerchantReview::getId).collect(Collectors.toList());
            assertEquals(java.util.Collections.singletonList(501L), publicIds,
                    "A 店公开列表不得混入同 owner 的 B 店评价");
            assertEquals(0, mapper.replyCas(502L, 7L, 202L, "越权回复", 202L,
                    "MERCHANT_OWNER", "reply-cross-store", "fingerprint", 0),
                    "A 店回复 CAS 不得命中 B 店评价");
            assertEquals(2, mapper.countActiveMerchantRowsForRow(7L),
                    "必须真实检测到同 owner 的两个活跃门店，让 Service 对旧核销 fail-closed");
            session.rollback();
        }
    }

    @Test
    void exactOrderReadDoesNotBorrowOtherOrdersAndRejectsInvalidRedemptions() throws Exception {
        try (SqlSession session = factory().openSession(false)) {
            createSchema(session);
            try (Statement sql = session.getConnection().createStatement()) {
                sql.execute("insert into mms_merchant values (7,202,1,1,0)");
                sql.execute("insert into cms_registration values (303,101),(304,101),(305,102),(306,101)");
                sql.execute("insert into redemption values (404,303,202,'ACTIVE',current_timestamp),"
                        + "(405,304,202,'ACTIVE',current_timestamp),(406,305,202,'ACTIVE',current_timestamp),"
                        + "(407,306,202,'REVERSED',current_timestamp)");
            }
            MerchantReviewMapper mapper = session.getMapper(MerchantReviewMapper.class);
            assertEquals(303L, mapper.selectEligibilityForRegistration(101L, 7L, 303L).getRegistrationId());
            assertEquals(304L, mapper.selectEligibilityForRegistration(101L, 7L, 304L).getRegistrationId());
            assertNull(mapper.selectEligibilityForRegistration(101L, 7L, 305L), "other holder");
            assertNull(mapper.selectEligibilityForRegistration(101L, 7L, 306L), "reversed redemption");
            try (Statement sql = session.getConnection().createStatement()) {
                sql.execute("insert into merchant_review(id,merchant_row_id,merchant_owner_member_id,"
                        + "registration_id,redemption_id,author_member_id,rating,content,images_json,status,version,"
                        + "create_time,update_time) values (501,7,202,303,404,101,5,'reviewed','[]','HIDDEN',0,current_timestamp,current_timestamp)");
            }
            session.clearCache();
            assertNull(mapper.selectEligibilityForRegistration(101L, 7L, 303L), "reviewed A cannot borrow B");
            assertEquals(304L, mapper.selectEligibilityForRegistration(101L, 7L, 304L).getRegistrationId());
            try (Statement sql = session.getConnection().createStatement()) {
                sql.execute("insert into mms_merchant values (8,202,1,1,0)");
            }
            session.clearCache();
            assertNull(mapper.selectEligibilityForRegistration(101L, 7L, 304L), "ambiguous active merchant rows");
            session.rollback();
        }
    }

    private SqlSessionFactory factory() throws Exception {
        Configuration configuration = new Configuration();
        UnpooledDataSource dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:merchant_review_row;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
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
            statement.execute("create table mms_merchant(id bigint primary key, member_id bigint not null, "
                    + "status int not null, account_status int not null, del_flag int not null)");
            statement.execute("create table cms_registration(id bigint primary key, member_id bigint not null)");
            statement.execute("create table redemption(id bigint primary key, registration_id bigint not null, "
                    + "merchant_id bigint not null, status varchar(16) not null, create_time timestamp not null)");
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
        }
    }

    private void seed(SqlSession session) throws Exception {
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("insert into mms_merchant values (7,202,1,1,0),(8,202,1,1,0)");
            statement.execute("insert into cms_registration values (303,101)");
            statement.execute("insert into redemption values (404,303,202,'ACTIVE',current_timestamp)");
            statement.execute("insert into ums_member values (101,'玩家甲','avatar-a',1,0)");
            statement.execute("insert into merchant_review(id,merchant_row_id,merchant_owner_member_id,"
                    + "registration_id,redemption_id,author_member_id,rating,content,images_json,status,version,"
                    + "create_time,update_time) values "
                    + "(501,7,202,303,404,101,5,'A店体验','[]','VISIBLE',0,current_timestamp,current_timestamp),"
                    + "(502,8,202,304,405,101,4,'B店体验','[]','VISIBLE',0,current_timestamp,current_timestamp)");
        }
    }
}
