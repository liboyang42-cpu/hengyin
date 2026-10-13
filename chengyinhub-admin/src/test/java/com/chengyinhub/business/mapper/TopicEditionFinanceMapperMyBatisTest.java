package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.TopicEditionCost;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 成本/工时公共 MyBatis seam；H2 仅证明 SQL 绑定，不冒充真实 MySQL。 */
class TopicEditionFinanceMapperMyBatisTest {
    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:edition_finance_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        configuration.addMapper(TopicEditionFundsMapper.class);
        String resource = "mapper/business/TopicEditionFundsMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true); Statement s = session.getConnection().createStatement()) {
            s.execute("create table topic_edition_cost(id bigint auto_increment primary key,topic_id bigint,"
                    + "cost_version bigint,is_current int,tax_cost decimal(14,2),payment_cost decimal(14,2),"
                    + "material_cost decimal(14,2),kit_cost decimal(14,2),insurance_risk_cost decimal(14,2),"
                    + "acquisition_cost decimal(14,2),platform_labor_cost decimal(14,2),other_cost decimal(14,2),"
                    + "platform_onsite_hours decimal(10,2),fixed_compensation decimal(14,2),bonuses decimal(14,2),"
                    + "break_even_fixed_cost decimal(14,2),variable_cost_per_ticket decimal(14,2),"
                    + "headcount_rate decimal(14,2) not null default 0.00,headcount_cap decimal(14,2),"
                    + "extra_approver_user_id bigint,note varchar(1000),"
                    + "created_at timestamp default current_timestamp)");
            s.execute("create table club_edition_hours(id bigint auto_increment primary key,topic_id bigint,club_id bigint,"
                    + "hour_kind varchar(16),planned_hours decimal(10,2),reported_actual_hours decimal(10,2),"
                    + "confirmed_actual_hours decimal(10,2),confirm_status varchar(16),reported_by bigint,reported_at timestamp,"
                    + "confirmed_by bigint,confirmed_at timestamp,evidence_note varchar(1000),update_time timestamp,"
                    + "unique(topic_id,club_id,hour_kind))");
        }
    }

    @Test
    void costAppendIsReadBackFromMapper() {
        TopicEditionCost cost = cost();
        try (SqlSession session = factory.openSession(true)) {
            TopicEditionFundsMapper mapper = session.getMapper(TopicEditionFundsMapper.class);
            assertEquals(1, mapper.insertCost(cost));
            TopicEditionCost readback = mapper.selectCurrentCost(23L);
            assertEquals(new BigDecimal("100.00"), readback.platformCostTotal());
            assertEquals(new BigDecimal("9.00"), readback.getVariableCostPerTicket());
        }
    }

    /**
     * 工时的写入口已整条搬到 ClubEditionCompensationMapper（窄语句 + 旧状态 CAS），
     * 旧 mapper 只剩只读 selectHours。这里直接用 SQL 造事实再回读，证明只读 seam 仍然对得上，
     * 且不会顺手把写后门重新引进来。
     */
    @Test
    void hoursReadOnlySeamStillProjectsTheConfirmedFact() throws Exception {
        try (SqlSession session = factory.openSession(true); Statement s = session.getConnection().createStatement()) {
            s.execute("insert into club_edition_hours(topic_id,club_id,hour_kind,planned_hours,"
                    + "reported_actual_hours,confirmed_actual_hours,confirm_status,confirmed_by)"
                    + " values (23,8,'ONSITE',4,5,4.5,'CONFIRMED',9)");
            TopicEditionFundsMapper mapper = session.getMapper(TopicEditionFundsMapper.class);
            assertEquals(new BigDecimal("4.50"), mapper.selectHours(23L).get(0).getConfirmedActualHours());
        }
    }

    private TopicEditionCost cost() {
        TopicEditionCost cost = new TopicEditionCost();
        cost.setTopicId(23L); cost.setVersion(1L); cost.setCurrent(1);
        cost.setTaxCost(new BigDecimal("10")); cost.setPaymentCost(new BigDecimal("10"));
        cost.setMaterialCost(new BigDecimal("10")); cost.setKitCost(new BigDecimal("10"));
        cost.setInsuranceRiskCost(new BigDecimal("10")); cost.setAcquisitionCost(new BigDecimal("10"));
        cost.setPlatformLaborCost(new BigDecimal("20")); cost.setOtherCost(new BigDecimal("20"));
        cost.setFixedCompensation(new BigDecimal("200")); cost.setBonuses(new BigDecimal("50"));
        cost.setBreakEvenFixedCost(new BigDecimal("500")); cost.setVariableCostPerTicket(new BigDecimal("9"));
        cost.setNote("readback");
        return cost;
    }
}
