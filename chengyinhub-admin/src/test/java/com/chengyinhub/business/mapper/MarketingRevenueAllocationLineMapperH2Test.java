package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocationLine;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 Mapper 的字段读回回归；H2 夹具不替代 MySQL 的约束、事务或支付事实验收。 */
class MarketingRevenueAllocationLineMapperH2Test {

    @Test
    void allSelectorsPreserveMoneyAndEventIdentityWithoutCamelCaseMapping() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:revenue_lines_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        try (var connection = source.getConnection()) {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE marketing_revenue_allocation_line (id BIGINT PRIMARY KEY,"
                    + " allocation_id BIGINT, entry_type INT, line_role VARCHAR(16), recipient_member_id BIGINT,"
                    + " funding_side INT, basis_fen BIGINT, rate DECIMAL(12,6), amount_fen BIGINT,"
                    + " currency VARCHAR(3), entry_key VARCHAR(96), event_ref VARCHAR(96), event_amount_fen BIGINT,"
                    + " event_currency VARCHAR(3), event_order_type INT, event_order_id BIGINT,"
                    + " reversal_of_line_id BIGINT, source_fact VARCHAR(96), create_time TIMESTAMP)");
            jdbc.update("INSERT INTO marketing_revenue_allocation_line VALUES"
                    + " (11,88,1,'author',77,0,9340,0.5,4670,'CNY','accrue:author',"
                    + " NULL,NULL,NULL,NULL,NULL,NULL,'payment:555',?),"
                    + " (12,88,2,'author',77,0,9340,0.5,-2335,'CNY','refund:9001:author',"
                    + " 'refund:9001',5000,'CNY',1,555,11,'refund:9001',?)",
                    Timestamp.valueOf("2026-11-08 12:00:00"), Timestamp.valueOf("2026-11-08 12:00:00"));

            Configuration configuration = new Configuration(new Environment("revenue-lines",
                    new JdbcTransactionFactory(), source));
            // 与生产 mybatis-config 一致；不得靠共享测试配置掩盖缺失的字段映射。
            configuration.setMapUnderscoreToCamelCase(false);
            String resource = "mapper/business/MarketingRevenueAllocationLineMapper.xml";
            try (InputStream xml = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(xml);
                new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
            }
            try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession()) {
                var mapper = session.getMapper(MarketingRevenueAllocationLineMapper.class);
                var lines = mapper.selectByAllocationId(88L);
                assertEquals(2, lines.size());
                assertEquals(1, lines.get(0).getEntryType());
                assertEquals(4670L, lines.get(0).getAmountFen());
                assertNull(lines.get(0).getEventRef());
                assertNull(lines.get(0).getReversalOfLineId());
                assertEquals(2335L, mapper.sumReversedFenByLineId(11L));
                var event = mapper.selectByEvent(88L, "refund:9001");
                assertEquals(1, event.size());
                var recipient = mapper.selectByRecipient(77L);
                assertEquals(2, recipient.size());
                for (var line : List.of(lines.get(1), event.get(0), recipient.get(0))) {
                    assertReversalFields(line);
                }
                assertTrue(mapper.selectByAllocationId(89L).isEmpty());
                assertTrue(mapper.selectByEvent(89L, "refund:9001").isEmpty());
                assertTrue(mapper.selectByEvent(88L, "refund:9002").isEmpty());
                assertTrue(mapper.selectByRecipient(78L).isEmpty());
            }
        }
    }

    private void assertReversalFields(MarketingRevenueAllocationLine line) {
        assertEquals(12L, line.getId());
        assertEquals(88L, line.getAllocationId());
        assertEquals(2, line.getEntryType());
        assertEquals("author", line.getLineRole());
        assertEquals(77L, line.getRecipientMemberId());
        assertEquals(0, line.getFundingSide());
        assertEquals(9340L, line.getBasisFen());
        assertEquals(new BigDecimal("0.500000"), line.getRate());
        assertEquals(-2335L, line.getAmountFen());
        assertEquals("CNY", line.getCurrency());
        assertEquals("refund:9001:author", line.getEntryKey());
        assertEquals("refund:9001", line.getEventRef());
        assertEquals(5000L, line.getEventAmountFen());
        assertEquals("CNY", line.getEventCurrency());
        assertEquals(1, line.getEventOrderType());
        assertEquals(555L, line.getEventOrderId());
        assertEquals(11L, line.getReversalOfLineId());
        assertEquals("refund:9001", line.getSourceFact());
        assertEquals(Timestamp.valueOf("2026-11-08 12:00:00").getTime(), line.getCreateTime().getTime());
    }
}
