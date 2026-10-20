package com.chengyinhub.business.mapper;

import com.chengyinhub.business.service.support.MerchantAuthoringCopyGuard;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.InputStream;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real production XML, minimal H2 contract schema; no MySQL/writer integration claim. */
class MerchantAuthoringCopyGuardMyBatisTest {
    private JdbcTemplate jdbc;
    private MerchantAuthoringCopyMapper mapper;
    private MerchantAuthoringCopyGuard guard;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:w05_copy_"
                + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table cms_member_template(id bigint primary key)");
        jdbc.execute("create table merchant_authoring_source(actual_template_id bigint not null unique)");
        jdbc.update("insert into cms_member_template(id) values(1),(2)");
        Configuration config = new Configuration();
        config.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), ds));
        String path = "mapper/business/MerchantAuthoringCopyMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(path)) {
            new XMLMapperBuilder(in, config, path, config.getSqlFragments()).parse();
        }
        mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config))
                .getMapper(MerchantAuthoringCopyMapper.class);
        guard = new MerchantAuthoringCopyGuard(mapper);
    }

    @Test void existingUnboundRowAllowsCopy() {
        assertEquals(Integer.valueOf(0), mapper.selectCopyBlockState(1L));
        assertDoesNotThrow(() -> guard.requireUnboundForDeepCopy(1L));
    }
    @Test void anySourceBindingBlocksWithoutStatusColumns() {
        jdbc.update("insert into merchant_authoring_source(actual_template_id) values(1)");
        assertEquals(Integer.valueOf(1), mapper.selectCopyBlockState(1L));
        assertThrows(ServiceException.class, () -> guard.requireUnboundForDeepCopy(1L));
        assertDoesNotThrow(() -> guard.requireUnboundForDeepCopy(2L));
    }
    @Test void missingCanonicalAndOrphanSourceNeverPass() {
        assertNull(mapper.selectCopyBlockState(99L));
        jdbc.update("insert into merchant_authoring_source(actual_template_id) values(99)");
        assertThrows(ServiceException.class, () -> guard.requireUnboundForDeepCopy(99L));
    }
    @Test void invalidIdsReject() {
        for (Long id : new Long[]{null, 0L, -1L})
            assertThrows(ServiceException.class, () -> guard.requireUnboundForDeepCopy(id));
    }
    @Test void missingSourceSchemaFailsClosed() {
        jdbc.execute("drop table merchant_authoring_source");
        assertThrows(DataAccessException.class, () -> guard.requireUnboundForDeepCopy(1L));
    }
    @Test void missingBindingColumnFailsClosed() {
        jdbc.execute("alter table merchant_authoring_source rename column actual_template_id to unavailable");
        assertThrows(DataAccessException.class, () -> guard.requireUnboundForDeepCopy(1L));
    }
    @Test void unexpectedOrNullQueryValuesReject() {
        MerchantAuthoringCopyMapper bad = mock(MerchantAuthoringCopyMapper.class);
        MerchantAuthoringCopyGuard actual = new MerchantAuthoringCopyGuard(bad);
        for (Integer result : new Integer[]{null, -1, 2}) {
            when(bad.selectCopyBlockState(1L)).thenReturn(result);
            assertThrows(ServiceException.class, () -> actual.requireUnboundForDeepCopy(1L));
        }
    }
}
