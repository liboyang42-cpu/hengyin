package com.chengyinhub.business.city;

import com.chengyinhub.business.it.support.IsolatedMySqlSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

/** Actual city migration (including physical readback), MyBatis XML and InnoDB transactions.
 * Existing member tables are explicit schema fixtures, point is the production signed INT width.
 * Authority/proof/consent/event allocation stay synthetic. Legacy add/deduct tests in the shared
 * parent prove mapper interoperability only, not complete registration/redemption/refund workflows. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
class CityRuntimeMySqlIT extends CityRuntimePersistenceH2Test {
    private final IsolatedMySqlSchema mysql = new IsolatedMySqlSchema();
    private JdbcTemplate mysqlJdbc;
    @Override protected DataSource createDataSource() throws Exception { return mysql.open("city"); }
    @Override protected void applySchema(JdbcTemplate jdbc) throws Exception {
        mysqlJdbc=jdbc;
        jdbc.execute("CREATE TABLE ums_member (id BIGINT PRIMARY KEY, point INT, status INT, del_flag INT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE ums_member_points_detail (id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT, current_points DECIMAL(10,0), after_points DECIMAL(10,0), change_points DECIMAL(10,0), change_reason VARCHAR(500), event_type INT, event_id BIGINT, reward_business_key VARCHAR(128), change_type INT, del_flag INT, create_time TIMESTAMP, UNIQUE KEY uk_member_event_once(member_id,event_type,event_id), UNIQUE KEY uk_member_reward_business(member_id,reward_business_key)) ENGINE=InnoDB");
        mysql.apply("20261002_city_visit_persistence");
    }
    @Override protected void dropFailureCheck(String table, String name) { mysqlJdbc.execute("ALTER TABLE " + table + " DROP CHECK " + name); }
    @AfterEach void removeMysqlScratch() throws Exception { mysql.close(); }
}
