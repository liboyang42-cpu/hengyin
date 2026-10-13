package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantCrmExportTask;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Statement;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MerchantCrmExportOwnerGuardMyBatisTest {
    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:merchant_crm_export_owner_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/MerchantCrmExportTaskMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true);
             Statement sql = session.getConnection().createStatement()) {
            sql.execute("create table mms_merchant (id bigint primary key, member_id bigint not null, "
                    + "status int not null, account_status int not null, del_flag int not null)");
            sql.execute("create table merchant_crm_export_task (id bigint primary key, merchant_id bigint not null, "
                    + "merchant_owner_member_id bigint not null, requester_member_id bigint not null, "
                    + "requester_role_code varchar(32) not null, filter_json varchar(500) not null, "
                    + "include_paid_amount int not null, status varchar(16) not null, version int not null, "
                    + "row_limit int not null, row_count int not null, storage_key varchar(64), "
                    + "token_hash varchar(64) not null, request_id varchar(64) not null, error_message varchar(200), "
                    + "expires_at timestamp not null, started_at timestamp, finished_at timestamp, "
                    + "downloaded_at timestamp, download_count int not null, create_time timestamp not null, "
                    + "update_time timestamp not null)");
            sql.execute("insert into mms_merchant values (77, 900, 1, 1, 0)");
            sql.execute("insert into merchant_crm_export_task values "
                    + "(88,77,900,900,'MERCHANT_OWNER','{}',0,'SUCCESS',0,5000,1,"
                    + "'12345678-1234-1234-1234-123456789abc.xlsx',"
                    + "'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',"
                    + "'request-1',null,dateadd('minute',10,current_timestamp),null,current_timestamp,null,0,"
                    + "current_timestamp,current_timestamp)");
        }
    }

    @Test
    void ownerRebindMakesReadAndDownloadFailThenInvalidatesTask() {
        try (SqlSession session = factory.openSession(true)) {
            MerchantCrmExportTaskMapper mapper = session.getMapper(MerchantCrmExportTaskMapper.class);
            Date now = new Date();
            assertNotNull(mapper.selectOwnedTask(77L, 900L, 88L));
            assertEquals(1, mapper.markDownloaded(77L, 900L, 88L, now));

            session.getConnection().createStatement()
                    .executeUpdate("update mms_merchant set member_id=902 where id=77");

            assertNull(mapper.selectOwnedTask(77L, 900L, 88L));
            assertNull(mapper.selectOwnedTask(77L, 902L, 88L));
            assertEquals(0, mapper.markDownloaded(77L, 900L, 88L, new Date()));
            assertEquals(1, mapper.invalidateOwnerMismatch(88L, 0, new Date()));
            MerchantCrmExportTask invalid = mapper.selectMerchantTaskInternal(77L, 88L);
            assertEquals(MerchantCrmExportTask.FAILED, invalid.getStatus());
            assertNull(invalid.getStorageKey());
            assertEquals("", invalid.getTokenHash());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void disabledOrDeletedMerchantCannotReadOrDownloadExport() {
        try (SqlSession session = factory.openSession(true)) {
            MerchantCrmExportTaskMapper mapper = session.getMapper(MerchantCrmExportTaskMapper.class);
            Statement sql = session.getConnection().createStatement();

            sql.executeUpdate("update mms_merchant set account_status=0 where id=77");
            assertNull(mapper.selectOwnedTask(77L, 900L, 88L));
            assertEquals(0, mapper.markDownloaded(77L, 900L, 88L, new Date()));

            sql.executeUpdate("update mms_merchant set account_status=1,status=0 where id=77");
            assertNull(mapper.selectOwnedTask(77L, 900L, 88L));

            sql.executeUpdate("update mms_merchant set status=1,del_flag=1 where id=77");
            assertNull(mapper.selectOwnedTask(77L, 900L, 88L));
            assertEquals(1, mapper.invalidateOwnerMismatch(88L, 0, new Date()));
            assertEquals(MerchantCrmExportTask.FAILED,
                    mapper.selectMerchantTaskInternal(77L, 88L).getStatus());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void workerClaimAndFinishRecheckCurrentActiveOwner() {
        try (SqlSession session = factory.openSession(true)) {
            MerchantCrmExportTaskMapper mapper = session.getMapper(MerchantCrmExportTaskMapper.class);
            Statement sql = session.getConnection().createStatement();
            sql.executeUpdate("update merchant_crm_export_task set status='PENDING' where id=88");
            Date now = new Date();

            assertEquals(1, mapper.claimRunnableTask(88L, 0, now,
                    new Date(now.getTime() - 600_000L),
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb.xlsx"));
            assertNotNull(mapper.selectTaskInternal(88L));

            sql.executeUpdate("update mms_merchant set member_id=902 where id=77");
            session.clearCache();

            assertNull(mapper.selectTaskInternal(88L));
            assertEquals(0, mapper.finishSuccess(88L, 1, 1,
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb.xlsx", new Date()));
            assertEquals(1, mapper.invalidateOwnerMismatch(88L, 1, new Date()));
            MerchantCrmExportTask invalid = mapper.selectMerchantTaskInternal(77L, 88L);
            assertEquals(MerchantCrmExportTask.FAILED, invalid.getStatus());
            assertNull(invalid.getStorageKey());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
