package com.chengyinhub.business.longrun;

import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN. Production bulk-expiry XML and independent H2 database facts; no service/refund simulation. */
class E04_11Test {
    @Test
    void bulkExpiryPreservesOtherMemberDeletedUndatedFutureAndVoidedRowsAndIsIdempotent() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:e04_11_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        source.setUsername("sa"); source.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("create table sms_coupon_history(id bigint primary key,member_id bigint,use_status int,"
                + "del_flag int,end_time timestamp,note varchar(255),update_time timestamp)");
        Timestamp now = jdbc.queryForObject("select current_timestamp", Timestamp.class);
        Timestamp past = new Timestamp(now.getTime() - 86_400_000L);
        Timestamp future = new Timestamp(now.getTime() + 86_400_000L);
        Timestamp unchanged = Timestamp.valueOf("2020-01-01 00:00:00");
        insert(jdbc, 1, 99, 0, 0, past, "eligible", unchanged);
        insert(jdbc, 2, 100, 0, 0, past, "other member", unchanged);
        insert(jdbc, 3, 99, 0, 1, past, "deleted unused", unchanged);
        insert(jdbc, 4, 99, 0, 0, future, "future unused", unchanged);
        insert(jdbc, 5, 99, 0, 0, null, "undated unused", unchanged);
        insert(jdbc, 6, 99, 2, 0, past, "voided by original reward reversal", unchanged);
        insert(jdbc, 7, 99, 2, 0, past, "already expired", unchanged);
        List<Map<String, Object>> protectedBefore = rows(jdbc, "where id<>1");

        Configuration config = new Configuration(new Environment("e04-11", new SpringManagedTransactionFactory(), source));
        config.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        String resource = "mapper/business/SmsCouponHistoryMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        SmsCouponHistoryMapper mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config))
                .getMapper(SmsCouponHistoryMapper.class);

        assertEquals(1, mapper.updateExpiredCouponsByMemberId(99L));

        assertEquals(Integer.valueOf(2), jdbc.queryForObject("select use_status from sms_coupon_history where id=1", Integer.class));
        assertEquals("eligible", jdbc.queryForObject("select note from sms_coupon_history where id=1", String.class));
        Timestamp expiredAt = jdbc.queryForObject("select update_time from sms_coupon_history where id=1", Timestamp.class);
        assertNotNull(expiredAt); assertNotEquals(unchanged, expiredAt);
        assertEquals(protectedBefore, rows(jdbc, "where id<>1"), "protected statuses, notes and update times must remain exact");
        List<Map<String, Object>> afterFirst = rows(jdbc, "");
        assertEquals(0, mapper.updateExpiredCouponsByMemberId(99L));
        assertEquals(afterFirst, rows(jdbc, ""), "repeat expiry must not rewrite terminal update_time");
        assertEquals(1, mapper.updateExpiredCouponsByMemberId(100L));
        assertEquals(Integer.valueOf(2), jdbc.queryForObject("select use_status from sms_coupon_history where id=2", Integer.class));
        assertEquals("other member", jdbc.queryForObject("select note from sms_coupon_history where id=2", String.class));
    }

    private void insert(JdbcTemplate jdbc, long id, long member, int status, int deleted,
                        Timestamp end, String note, Timestamp updated) {
        jdbc.update("insert into sms_coupon_history values(?,?,?,?,?,?,?)", id, member, status, deleted, end, note, updated);
    }
    private List<Map<String, Object>> rows(JdbcTemplate jdbc, String where) {
        return jdbc.queryForList("select id,member_id,use_status,del_flag,end_time,note,update_time "
                + "from sms_coupon_history " + where + " order by id");
    }
}
