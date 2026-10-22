package com.chengyinhub.business.mapper;

import com.chengyinhub.system.domain.ViewCouponHistory;
import com.chengyinhub.system.mapper.ViewCouponHistoryMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Date;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real View mapper predicates executed on a synthetic projection-column TABLE.
 * The pinned LEFT JOIN view is tested separately below. This is not MySQL view-updatability proof. */
public class ViewCouponHistoryValidityGuardTest {
    private JdbcDataSource db;
    private SqlSessionFactory factory;
    private static final String HISTORY_COLUMNS="id BIGINT PRIMARY KEY,coupon_id BIGINT,member_id BIGINT,coupon_code VARCHAR(100),qrcode_url VARCHAR(400),get_type INT,get_time TIMESTAMP(3),use_status BIGINT,use_time TIMESTAMP(3),order_id BIGINT,order_sn VARCHAR(100),start_time TIMESTAMP(3),end_time TIMESTAMP(3),note VARCHAR(200),del_flag BIGINT";
    @BeforeEach void setup() throws Exception {
        db=new JdbcDataSource();db.setURL("jdbc:h2:mem:coupon_view_guard_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        sql("CREATE TABLE view_coupon_history ("+HISTORY_COLUMNS+",coupon_name VARCHAR(100),coupon_type BIGINT,coupon_description VARCHAR(200),coupon_del_flag BIGINT)");
        sql("INSERT INTO view_coupon_history(id,coupon_id,member_id,use_status,del_flag,start_time,end_time,coupon_name,coupon_description,coupon_del_flag) VALUES(1,8,7,0,0,NULL,NULL,'Original name','Original conditions',0)");
        seedDates("view_coupon_history");
        Configuration configuration=new Configuration(new Environment("isolated-projection-table",new JdbcTransactionFactory(),db));
        configuration.getTypeAliasRegistry().registerAlias("ViewCouponHistory",ViewCouponHistory.class);
        try(InputStream xml=getClass().getResourceAsStream("/mapper/business/ViewCouponHistoryMapper.xml")) {
            assertNotNull(xml);new XMLMapperBuilder(xml,configuration,"mapper/business/ViewCouponHistoryMapper.xml",configuration.getSqlFragments()).parse();
        }
        factory=new SqlSessionFactoryBuilder().build(configuration);
    }
    @AfterEach void close() throws Exception { sql("SHUTDOWN"); }
    private void sql(String text) throws Exception { try(Connection c=db.getConnection();Statement s=c.createStatement()){s.execute(text);} }
    private void seedDates(String table) throws Exception {
        // Bind exact instants, avoiding machine-local SQL timestamp-literal assumptions.
        try(Connection c=db.getConnection();java.sql.PreparedStatement p=c.prepareStatement("UPDATE "+table+" SET start_time=?,end_time=? WHERE id=1")) {
            p.setTimestamp(1,new java.sql.Timestamp(1000));p.setTimestamp(2,new java.sql.Timestamp(9000));assertEquals(1,p.executeUpdate());
        }
    }
    private ViewCouponHistory change(){ViewCouponHistory row=new ViewCouponHistory();row.setId(1L);return row;}
    private int update(ViewCouponHistory row){try(SqlSession s=factory.openSession(true)){return s.getMapper(ViewCouponHistoryMapper.class).updateViewCouponHistory(row);}}
    private ViewCouponHistory read(){try(SqlSession s=factory.openSession(true)){return s.getMapper(ViewCouponHistoryMapper.class).selectViewCouponHistoryById(1L);}}
    @Test void projectionDateEditCannotShortenHistoryOrMutateOtherFields() {
        ViewCouponHistory row=change();row.setEndTime(new Date(8000));row.setNote("must not apply");
        assertEquals(0,update(row));assertEquals(new Date(9000),read().getEndTime());assertNull(read().getNote());
    }
    @Test void projectionStartChangeCannotReduceWindow() {
        ViewCouponHistory row=change();row.setStartTime(new Date(2000));assertEquals(0,update(row));assertEquals(new Date(1000),read().getStartTime());
    }
    @Test void projectionEndExtensionCannotRewriteFrozenWindowEither() {
        ViewCouponHistory row=change();row.setEndTime(new Date(10000));assertEquals(0,update(row));assertEquals(new Date(9000),read().getEndTime());
    }
    @Test void projectionNullableOmissionAllowsDateUnrelatedUpdate() {
        ViewCouponHistory row=change();row.setNote("reviewed metadata");assertEquals(1,update(row));
        assertEquals("reviewed metadata",read().getNote());assertEquals(new Date(1000),read().getStartTime());assertEquals(new Date(9000),read().getEndTime());
    }
    @Test void projectionUnknownLegacyDateCannotBeInvented() throws Exception {
        sql("UPDATE view_coupon_history SET end_time=NULL WHERE id=1");
        ViewCouponHistory row=change();row.setEndTime(new Date(9000));assertEquals(0,update(row));assertNull(read().getEndTime());
    }
    @Test void projectionUnchangedDatesOnlyRenderValidNoOpSql() {
        ViewCouponHistory row=change();row.setStartTime(new Date(1000));row.setEndTime(new Date(9000));
        assertEquals(1,update(row));assertEquals(new Date(9000),read().getEndTime());
    }
    @Test void mapperSqlCannotBindNewDatesIntoSetClause() {
        ViewCouponHistory row=change();row.setStartTime(new Date(2000));row.setEndTime(new Date(8000));
        String sql=factory.getConfiguration().getMappedStatement("com.chengyinhub.system.mapper.ViewCouponHistoryMapper.updateViewCouponHistory").getBoundSql(row).getSql().replaceAll("\\s+"," ").toLowerCase();
        String set=sql.substring(sql.indexOf(" set "),sql.indexOf(" where "));
        assertFalse(set.contains("start_time = ?"));assertFalse(set.contains("end_time = ?"));
        assertTrue(sql.contains("and start_time = ?"));assertTrue(sql.contains("and end_time = ?"));
    }
    @Test void pinnedJoinProjectionKeepsHistoryDatesButDoesNotFreezeDescription() throws Exception {
        sql("DROP TABLE view_coupon_history");
        sql("CREATE TABLE sms_coupon_history ("+HISTORY_COLUMNS+")");
        sql("CREATE TABLE sms_coupon(id BIGINT PRIMARY KEY,name VARCHAR(100),coupon_type BIGINT,description VARCHAR(200),del_flag BIGINT)");
        sql("INSERT INTO sms_coupon_history(id,coupon_id,member_id,use_status,del_flag,start_time,end_time) VALUES(1,8,7,0,0,NULL,NULL)");
        seedDates("sms_coupon_history");
        sql("INSERT INTO sms_coupon VALUES(8,'Original name',0,'Original conditions',0)");
        try(InputStream source=getClass().getResourceAsStream("/reward/coupon-history-join-view.sql")) {
            assertNotNull(source);java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[1024];int count;
            while((count=source.read(buffer))>=0)bytes.write(buffer,0,count);
            sql(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
        }
        assertEquals(new Date(9000),read().getEndTime());assertEquals("Original conditions",read().getCouponDescription());
        sql("UPDATE sms_coupon SET description='Current changed conditions' WHERE id=8");
        assertEquals(new Date(9000),read().getEndTime());assertEquals("Current changed conditions",read().getCouponDescription());
        ViewCouponHistory row=change();row.setEndTime(new Date(8000));
        // A mismatching immutable-date predicate can return zero before H2 asks the view
        // to perform an update. Otherwise the engine refuses the actual join-view write.
        try { assertEquals(0,update(row)); }
        catch(PersistenceException viewWriteRejected) { assertTrue(viewWriteRejected.getMessage().contains("not supported")); }
        assertEquals(new Date(9000),read().getEndTime());
        ViewCouponHistory matching=change();matching.setEndTime(new Date(9000));matching.setNote("actual view write probe");
        assertThrows(PersistenceException.class,()->update(matching));
        assertEquals(new Date(9000),read().getEndTime());
    }
}
