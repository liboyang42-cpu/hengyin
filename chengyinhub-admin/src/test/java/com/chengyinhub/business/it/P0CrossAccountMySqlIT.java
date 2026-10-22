package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.ContentDraft;
import com.chengyinhub.business.domain.dto.ContentDraftCommand;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.ContentDraftMapper;
import com.chengyinhub.business.mapper.RedemptionMapper;
import com.chengyinhub.business.service.IContentDraftService;
import com.chengyinhub.business.service.impl.ContentDraftServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AC1 · P0 越权(跨账号)故障卡的后端半边:用户 B 拿着用户 A 的订单/票/草稿 id,
 * 在真 MySQL 上走生产 mapper XML 与 {@link ContentDraftServiceImpl} 真事务代理,读不到也写不动,
 * 且 A 的行逐列不变。
 *
 * <p>订单详情 / 活动删改 / 俱乐部写接口的归属校验在 Controller 层({@code OwnershipUtils.notOwner}),
 * 由既有 Mockito 与 {@code ClubRoleHttpMySqlIT} 覆盖;这里只证明"SQL 自身按 owner 收口"的那部分。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class P0CrossAccountMySqlIT {
    private static final String SUITE = "p0_cross_account";
    private static final long A = 4101L;
    private static final long B = 4102L;

    private JdbcTemplate db;
    private IContentDraftService drafts;
    private CmsRegistrationMapper registrations;
    private RedemptionMapper redemptions;

    @BeforeAll
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = P0AcceptanceSchema.dataSource(SUITE);
        db = new JdbcTemplate(dataSource);
        P0AcceptanceSchema.baseTables(db, "cms_registration", "redemption", "cms_activity", "cms_topic");
        P0AcceptanceSchema.migrations(dataSource, "migration_20260930_registration_first_successful_scan.sql");
        P0AcceptanceSchema.createTableFromMigration(db, "migration_content_draft_im_poll_20260828.sql");
        SqlSessionTemplate sql = P0AcceptanceSchema.sql(dataSource,
                "ContentDraftMapper", "CmsRegistrationMapper", "RedemptionMapper");
        registrations = sql.getMapper(CmsRegistrationMapper.class);
        redemptions = sql.getMapper(RedemptionMapper.class);
        ContentDraftServiceImpl target = new ContentDraftServiceImpl();
        ReflectionTestUtils.setField(target, "draftMapper", sql.getMapper(ContentDraftMapper.class));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(IContentDraftService.class);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        drafts = (IContentDraftService) proxy.getProxy();
    }

    @BeforeEach
    void clear() {
        for (String table : new String[] {"content_draft", "cms_registration", "redemption", "cms_activity", "cms_topic"}) {
            db.update("delete from " + table);
        }
    }

    @AfterAll
    void dropSchema() {
        if (db != null) {
            P0AcceptanceSchema.drop(db, SUITE);
        }
    }

    @Test
    void userBCannotRestoreListEditDeleteOrPublishUserADraft() {
        ContentDraft draft = drafts.save(A, command(null, 0L, "{\"title\":\"A 的草稿\"}"));
        String before = row("content_draft", draft.getId());

        assertStatus(404, () -> drafts.restore(B, draft.getId()));
        assertTrue(drafts.list(B, null).isEmpty(), "B 的草稿列表不得出现 A 的草稿");
        assertStatus(409, () -> drafts.save(B, command(draft.getId(), 1L, "{\"title\":\"B 篡改\"}")));
        assertStatus(409, () -> drafts.delete(B, command(draft.getId(), 1L, null)));
        ContentDraftCommand publish = command(draft.getId(), 1L, null);
        publish.setPublishedResourceId(1L);
        assertStatus(404, () -> drafts.publish(B, publish));
        assertEquals(before, row("content_draft", draft.getId()), "A 的草稿一列都不能被 B 改动");

        // A 也不能把草稿发布成 B 名下的活动(资源归属同样在 SQL 内收口)。
        db.update("insert into cms_activity(id,member_id,del_flag) values (9201,?,0)", B);
        ContentDraftCommand foreign = command(draft.getId(), 1L, null);
        foreign.setPublishedResourceId(9201L);
        assertStatus(403, () -> drafts.publish(A, foreign));
        assertEquals(before, row("content_draft", draft.getId()));

        // 正控:A 本人仍可恢复、编辑。
        assertEquals(draft.getId(), drafts.restore(A, draft.getId()).getId());
        assertEquals(2L, drafts.save(A, command(draft.getId(), 1L, "{\"title\":\"A 改\"}")).getVersion());
    }

    @Test
    void userBCannotListPayCancelOrScanUserAOrderAndTicket() {
        order(5001L, A, 0, 1);   // A 的待支付单
        order(5002L, A, 2, 2);   // A 的已付票
        order(5003L, B, 2, 2);   // B 自己的票
        String before = row("cms_registration", 5001L) + row("cms_registration", 5002L);

        CmsRegistration filter = new CmsRegistration();
        filter.setMemberId(B);
        List<CmsRegistration> mine = registrations.selectCmsRegistrationList(filter);
        assertEquals(List.of(5003L), mine.stream().map(CmsRegistration::getId).toList(), "B 的订单列表只含 B 的单");

        assertEquals(0, registrations.claimPaymentProcessing(5001L, B, new Date()), "B 不能替 A 的单发起支付");
        assertEquals(0, registrations.cancelUnpaidIfActive(5001L, B, "member:" + B), "B 不能取消 A 的单");
        assertEquals(0, registrations.markFirstSuccessfulScan(5002L, B, 1L, 501L, new Date()),
                "B 不能以 A 的票记首次扫码");
        assertEquals(before, row("cms_registration", 5001L) + row("cms_registration", 5002L),
                "A 的订单与票逐列不变");

        // 正控:同样的 SQL 由本人执行才生效。
        assertEquals(1, registrations.claimPaymentProcessing(5001L, A, new Date()));
        assertEquals(1, registrations.markFirstSuccessfulScan(5002L, A, 1L, 501L, new Date()));
    }

    @Test
    void merchantBCannotReadMerchantARedemptionRecord() {
        order(5101L, A, 2, 2);
        db.update("insert into redemption(id,registration_id,chapter_id,topic_id,merchant_id,operator_id,status,"
                + "idempotency_key,create_time) values (6101,5101,null,501,8001,8001,'ACTIVE','redeem:5101:ORDER:8001',now())");
        assertNull(redemptions.selectByMerchantAndId(8002L, 6101L), "商家 B 不能按 id 读商家 A 的核销流水");
        assertTrue(redemptions.selectByMerchant(8002L).isEmpty(), "商家 B 的核销列表不得出现 A 的流水");
        assertEquals(6101L, redemptions.selectByMerchantAndId(8001L, 6101L).getId(), "正控:本商家可读");
    }

    private void order(long id, long member, int paymentStatus, int registrationStatus) {
        db.update("insert into cms_registration(id,registration_no,owner_type,owner_id,ticket_id,ticket_price,member_id,"
                        + "original_amount,total_amount,payable_amount,payment_status,registration_status,pay_expire_time,"
                        + "verification_status,create_time) values (?,?,1,501,1,'68.00',?,'68.00','68.00','68.00',?,?,"
                        + "now()+interval 1 day,0,now())",
                id, "AC1X-" + id, member, paymentStatus, registrationStatus);
    }

    private String row(String table, long id) {
        return db.queryForList("select * from " + table + " where id=?", id).toString();
    }

    private static ContentDraftCommand command(Long id, Long expectedVersion, String payload) {
        ContentDraftCommand command = new ContentDraftCommand();
        command.setId(id);
        command.setBusinessType("ACTIVITY");
        command.setClientDraftKey("ac1-draft-key");
        command.setPayloadJson(payload);
        command.setExpectedVersion(expectedVersion);
        command.setDeviceId("ac1-device");
        return command;
    }

    private static void assertStatus(int code, org.junit.jupiter.api.function.Executable call) {
        ServiceException denied = assertThrows(ServiceException.class, call);
        assertEquals(Integer.valueOf(code), denied.getCode(), denied.getMessage());
    }
}
