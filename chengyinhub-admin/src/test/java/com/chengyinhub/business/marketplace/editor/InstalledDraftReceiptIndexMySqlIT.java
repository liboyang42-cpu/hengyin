package com.chengyinhub.business.marketplace.editor;

import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Oracle-only index metadata, actual mapper EXPLAIN and consistent-snapshot assertions. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT",matches="1")
class InstalledDraftReceiptIndexMySqlIT {
    private InstalledDraftReceiptMySqlIT fixture;
    private JdbcTemplate jdbc;
    @BeforeEach void open() throws Exception {
        fixture=new InstalledDraftReceiptMySqlIT();fixture.setup();jdbc=fixture.jdbc;
    }
    @AfterEach void close() throws Exception {if(fixture!=null)fixture.closeSchema();}
    private void apply() throws Exception {fixture.mysql.apply("w18_owned_text_receipt_read_index_20261003");}
    private void replaceIndex(String definition) {
        jdbc.execute("ALTER TABLE w18_module_owned_draft DROP INDEX idx_w18_owned_receipt_read");
        jdbc.execute("ALTER TABLE w18_module_owned_draft ADD "+definition);
    }
    private void rejected(){assertThrows(AssertionError.class,this::apply);}
    @Test void exactMigrationRerunPreservesReceiptsAndIndex() throws Exception {
        apply();apply();assertEquals(1,fixture.read().getReceipts().size());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM w18_module_owned_draft",Integer.class));
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='w18_module_owned_draft' AND index_name='idx_w18_owned_receipt_read'",Integer.class));
    }
    @Test void wrongColumnOrderFailsClosed(){replaceIndex("INDEX idx_w18_owned_receipt_read(buyer_id,buyer_kind,target_draft_id,installed_at,id)");rejected();}
    @Test void prefixIndexFailsClosed(){replaceIndex("INDEX idx_w18_owned_receipt_read(buyer_kind(4),buyer_id,target_draft_id,installed_at,id)");rejected();}
    @Test void descendingTimestampIndexFailsClosed(){replaceIndex("INDEX idx_w18_owned_receipt_read(buyer_kind,buyer_id,target_draft_id,installed_at DESC,id)");rejected();}
    @Test void invisibleIndexFailsClosed(){jdbc.execute("ALTER TABLE w18_module_owned_draft ALTER INDEX idx_w18_owned_receipt_read INVISIBLE");rejected();}
    @Test void uniqueIndexFailsClosed(){replaceIndex("UNIQUE INDEX idx_w18_owned_receipt_read(buyer_kind,buyer_id,target_draft_id,installed_at,id)");rejected();}
    @Test void extraIndexColumnFailsClosed(){replaceIndex("INDEX idx_w18_owned_receipt_read(buyer_kind,buyer_id,target_draft_id,installed_at,id,installed_by)");rejected();}
    @Test void actualReceiptMapperUsesScopedOrderingIndexWithoutFilesort() throws Exception {
        for(long id=3;id<=258;id++)fixture.seed(id,42,91,1);
        jdbc.execute("ANALYZE TABLE w18_module_owned_draft,w18_module_install_reservation,w18_module_install_usage,w18_module_draft_component");
        Map<String,Object> parameters=new HashMap<>();parameters.put("actor",42L);parameters.put("draftId",91L);
        BoundSql bound=fixture.session.getConfiguration().getMappedStatement("com.chengyinhub.business.marketplace.editor.store.InstalledDraftReceiptStore.findReceipts").getBoundSql(parameters);
        Object[] args=bound.getParameterMappings().stream().map(p->parameters.get(p.getProperty())).toArray();
        String invalidHintSql=bound.getSql().replace("JOIN_PREFIX(d) INDEX(d idx_w18_owned_receipt_read)","UNKNOWN_RECEIPT_HINT()");
        assertNotEquals(bound.getSql(),invalidHintSql);
        PlanEvidence negative=explainOnOneConnection(invalidHintSql,args);
        assertTrue(negative.warnings.stream().anyMatch(w->w.startsWith("Warning|") && w.contains("UNKNOWN_RECEIPT_HINT")),negative.warnings.toString());
        PlanEvidence evidence=explainOnOneConnection(bound.getSql(),args);
        String plan=evidence.plan;
        System.out.println("INSTALLED_RECEIPT_EXPLAIN_WARNINGS="+evidence.warnings+" NEGATIVE_CONTROL="+negative.warnings);
        assertFalse(evidence.warnings.stream().anyMatch(w->w.startsWith("Warning|")),evidence.warnings.toString());
        JsonNode root=new ObjectMapper().readTree(plan);JsonNode table=findTable(root,"d");
        assertNotNull(table,plan);assertEquals("idx_w18_owned_receipt_read",table.path("key").asText(),plan);
        assertFalse(hasTrue(root,"using_filesort"),plan);
        assertTrue(table.path("used_key_parts").toString().contains("buyer_kind"),plan);
        assertTrue(table.path("used_key_parts").toString().contains("buyer_id"),plan);
        assertTrue(table.path("used_key_parts").toString().contains("target_draft_id"),plan);
        assertEquals(50,fixture.read().getReceipts().size());assertTrue(fixture.read().isHasMore());
        System.out.println("INSTALLED_RECEIPT_EXPLAIN ORIGINAL_MAPPER=true ORACLE_MYSQL=8.0.45 PLAN="+plan);
    }
    @Test void ownerTransferCommittedBetweenSelectsKeepsOneSnapshotWithoutTransferOfReceipts() {
        fixture.commitBetweenQueries(()->jdbc.update("UPDATE content_draft SET owner_member_id=43 WHERE id=91"));
        assertEquals(1,fixture.read().getReceipts().get(0).getInstallationId());
        assertEquals(404,assertThrows(ServiceException.class,fixture::read).getCode());
        assertTrue(fixture.reader.read(43,91,1,InstalledDraftReceiptReaderTest.HASH).getReceipts().isEmpty());
    }
    @Test void newlyCommittedInstallationCannotAppearInEarlierSnapshot() {
        fixture.commitBetweenQueries(()->fixture.seed(3,42,91,1));
        assertEquals(1,fixture.read().getReceipts().size());assertEquals(2,fixture.read().getReceipts().size());
    }
    private static final class PlanEvidence {
        String plan; final List<String> warnings=new ArrayList<>();
    }
    private PlanEvidence explainOnOneConnection(String sql,Object[] args) {
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<PlanEvidence>) connection->{
            PlanEvidence evidence=new PlanEvidence();
            try(java.sql.PreparedStatement explain=connection.prepareStatement("EXPLAIN FORMAT=JSON "+sql)) {
                for(int i=0;i<args.length;i++)explain.setObject(i+1,args[i]);
                try(java.sql.ResultSet rows=explain.executeQuery()) {
                    assertTrue(rows.next());evidence.plan=rows.getString(1);
                }
                // Warning history is session-local: no intervening SQL or connection replacement.
                try(java.sql.Statement warningQuery=connection.createStatement();
                    java.sql.ResultSet warnings=warningQuery.executeQuery("SHOW WARNINGS")) {
                    while(warnings.next())evidence.warnings.add(warnings.getString("Level")+"|"+warnings.getInt("Code")+"|"+warnings.getString("Message"));
                }
            }
            return evidence;
        });
    }
    private static JsonNode findTable(JsonNode node,String name) {
        if(node.isObject()&&name.equals(node.path("table_name").asText()))return node;
        for(JsonNode child:node){JsonNode found=findTable(child,name);if(found!=null)return found;}return null;
    }
    private static boolean hasTrue(JsonNode node,String name) {
        if(node.isObject()&&node.path(name).asBoolean(false))return true;
        for(JsonNode child:node)if(hasTrue(child,name))return true;return false;
    }
}
