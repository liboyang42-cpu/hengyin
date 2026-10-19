package com.chengyinhub.business.service.ai;

import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.business.domain.vo.ai.*;
import com.chengyinhub.common.exception.ServiceException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Additional real service/mapper checks on H2; these do not replace the MySQL RR race IT. */
class MerchantAuthoringCurrentReadTest {
    MerchantAuthoringTransactionTest f;
    @BeforeEach void setup() throws Exception { f=new MerchantAuthoringTransactionTest(); f.setup(); }
    @AfterEach void cleanup() throws Exception { f.cleanup(); }

    @Test void unchangedLockedOwnerSnapshotStillRecordsCandidate() {
        Map<String,Object> verified=f.facts.facts(7L,101L,102L);
        inRepeatableRead(()->{f.authority.require(7L,MerchantPermission.PROJECT_MANAGE);
            assertNotNull(f.records.record(7L,verified,Collections.singletonMap("fixture",true),generated()));});
        assertEquals(1,f.count("merchant_authoring_candidate"));
    }
    @Test void separatelyEligibleManagerUsesCurrentLockedRoleAndExistingPermissionPolicy() {
        manager(); Map<String,Object> verified=f.facts.facts(17L,101L,102L);
        inRepeatableRead(()->assertEquals(7L,f.records.record(17L,verified,Collections.singletonMap("fixture",true),generated()).getOwnerMemberId()));
        assertEquals(1,f.count("merchant_authoring_candidate"));
    }
    @Test void committedOperatorDowngradeCannotUsePrimedAuthoritySnapshot() {
        manager(); Map<String,Object> verified=f.facts.facts(17L,101L,102L);
        assertThrows(ServiceException.class,()->inRepeatableRead(()->{
            f.authority.require(17L,MerchantPermission.PROJECT_MANAGE);
            committed("UPDATE merchant_operator SET role_code='MERCHANT_CHECKIN' WHERE id=301");
            f.records.record(17L,verified,Collections.singletonMap("fixture",true),generated());
        }));
        assertEquals("MERCHANT_CHECKIN",f.jdbc.queryForObject("SELECT role_code FROM merchant_operator WHERE id=301",String.class));
        assertEquals(0,f.count("merchant_authoring_candidate"));
    }
    @Test void committedShopDisableCannotUsePrimedAuthoritySnapshot() {
        Map<String,Object> verified=f.facts.facts(7L,101L,102L);
        assertThrows(ServiceException.class,()->inRepeatableRead(()->{
            f.authority.require(7L,MerchantPermission.PROJECT_MANAGE);
            committed("UPDATE mms_merchant SET account_status=0 WHERE id=19");
            f.records.record(7L,verified,Collections.singletonMap("fixture",true),generated());
        }));
        assertEquals(0L,f.jdbc.queryForObject("SELECT account_status FROM mms_merchant WHERE id=19",Long.class));
        assertEquals(0,f.count("merchant_authoring_candidate"));
    }
    @Test void committedProductChangeCannotUsePrimedFactsSnapshot() {
        rejectCandidateAfterFactsChange("UPDATE pms_product SET product_name='Current replacement' WHERE id=101");
    }
    @Test void committedSKUChangeCannotUsePrimedFactsSnapshot() {
        rejectCandidateAfterFactsChange("UPDATE pms_sku SET sku_name='Current replacement' WHERE id=102");
    }
    @Test void committedShopNameChangeCannotUsePrimedFactsSnapshot() {
        rejectCandidateAfterFactsChange("UPDATE mms_merchant SET name='Current replacement' WHERE id=19");
    }
    @Test void confirmationAfterCommittedFactChangeCreatesNeitherTemplateNorSource() {
        long id=f.candidate();
        assertThrows(ServiceException.class,()->inRepeatableRead(()->{
            f.facts.facts(7L,101L,102L);
            committed("UPDATE pms_product SET product_name='Current replacement' WHERE id=101");
            f.records.confirm(7L,f.confirmation(id));
        }));
        assertEquals(1,f.count("merchant_authoring_candidate"));
        assertEquals(0,f.count("cms_member_template")); assertEquals(0,f.count("merchant_authoring_source"));
    }
    @Test void confirmationAfterCommittedManagerRevocationCreatesNeitherTemplateNorSource() {
        manager(); Map<String,Object> verified=f.facts.facts(17L,101L,102L);
        long id=f.records.record(17L,verified,Collections.singletonMap("fixture",true),generated()).getId();
        assertThrows(ServiceException.class,()->inRepeatableRead(()->{
            f.authority.require(17L,MerchantPermission.PROJECT_MANAGE);
            committed("UPDATE merchant_operator SET role_code='MERCHANT_CHECKIN' WHERE id=301");
            f.records.confirm(17L,f.confirmation(id));
        }));
        assertEquals(1,f.count("merchant_authoring_candidate"));
        assertEquals(0,f.count("cms_member_template")); assertEquals(0,f.count("merchant_authoring_source"));
    }
    @Test void candidatePersistenceConsumesLockedRowsWithoutReopeningSnapshotQueries() {
        manager(); Map<String,Object> verified=f.facts.facts(17L,101L,102L);
        CurrentReadTrace trace=new CurrentReadTrace();
        f.sql.getConfiguration().addInterceptor(trace);
        f.records.record(17L,verified,Collections.singletonMap("fixture",true),generated());
        assertTrue(trace.locked);
        for (String suffix:Arrays.asList("lockMerchantSnapshot","lockAuthorityMerchants",
                "selectActiveByMemberIdForUpdate","lockProductSnapshot","lockSkuSnapshot")) {
            assertTrue(trace.currentReads.stream().anyMatch(id->id.endsWith("."+suffix)),suffix);
        }
        assertEquals(1,f.count("merchant_authoring_candidate"));
    }
    @Intercepts(@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    public static final class CurrentReadTrace implements Interceptor {
        boolean locked; final List<String> currentReads=new ArrayList<>();
        @Override public Object intercept(Invocation call) throws Throwable {
            String id=((MappedStatement)call.getArgs()[0]).getId();
            if (id.endsWith("MerchantAuthoringRecordMapper.lockMerchant")) locked=true;
            if (locked) {
                assertFalse(id.endsWith(".selectMmsMerchantList") || id.endsWith(".selectMmsMerchantById")
                        || id.endsWith(".selectActiveByMemberId") || id.endsWith(".selectUmsMemberById")
                        || id.endsWith(".selectPmsProductById") || id.endsWith(".selectPmsSkuById"),
                        "Mutation must consume current locked rows, not a repeatable-read snapshot: "+id);
                currentReads.add(id);
            }
            return call.proceed();
        }
        @Override public Object plugin(Object target) { return Plugin.wrap(target,this); }
        @Override public void setProperties(Properties properties) {}
    }
    private void rejectCandidateAfterFactsChange(String statement) {
        Map<String,Object> verified=f.facts.facts(7L,101L,102L);
        assertThrows(ServiceException.class,()->inRepeatableRead(()->{
            assertEquals(verified,f.facts.facts(7L,101L,102L)); committed(statement);
            f.records.record(7L,verified,Collections.singletonMap("fixture",true),generated());
        }));
        assertEquals(0,f.count("merchant_authoring_candidate"));
    }
    private void manager() {
        f.jdbc.update("INSERT INTO merchant_operator(id,merchant_id,operator_member_id,active_operator_member_id,role_code,status,version) VALUES(301,19,17,17,'MERCHANT_MANAGER','ACTIVE',1)");
    }
    private void inRepeatableRead(Runnable action) {
        TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(f.db));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.execute(status->{action.run();return null;});
    }
    private void committed(String statement) {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try { assertEquals(1,worker.submit(()->f.jdbc.update(statement)).get(10,TimeUnit.SECONDS)); }
        catch(Exception error) { throw new AssertionError("Independent commit failed",error); }
        finally { worker.shutdownNow(); }
    }
    private AiTemplateFillResp generated() {
        AiTemplateContent text=new AiTemplateContent(); text.setTitle("Synthetic candidate"); text.setValidationMethod(1);
        AiTemplateFillResp result=new AiTemplateFillResp(); result.setTraceId("synthetic-current-read"); result.setTemplate(text); return result;
    }
}
