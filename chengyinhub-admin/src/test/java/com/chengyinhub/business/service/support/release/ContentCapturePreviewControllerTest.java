package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.vo.ContentCapturePreviewVO;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.ContentCapturePreviewPermissionMapper;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.business.service.impl.TopicOperationsScopeAuthorizer;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.web.controller.business.ContentCapturePreviewController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.io.InputStream;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual controller + transaction proxy + mapper SQL + current scope/identity checks, using synthetic H2 data only. */
class ContentCapturePreviewControllerTest {
    private final ContentCaptureReviewServicePathTest fixture=new ContentCaptureReviewServicePathTest();
    private javax.sql.DataSource source;
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private org.mybatis.spring.SqlSessionTemplate session;
    private ContentCaptureReviewService service;
    private com.chengyinhub.business.domain.ContentCaptureSnapshot snapshot;
    private org.springframework.beans.factory.ObjectProvider<com.chengyinhub.business.service.ICmsTopicService> publisher;
    private <T>T run(java.util.function.Supplier<T> work) { return fixture.run(work); }
    private long submit(String key) { return fixture.submit(key); }
    private MockMvc mvc;
    private ContentCapturePreviewService previews;
    private final ObjectMapper json=new ObjectMapper();
    private long task;

    @BeforeEach void wirePreview() throws Exception {
        fixture.setup(); fixture.wireAudit();
        source=fixture.source; jdbc=fixture.jdbc; session=fixture.session; service=fixture.service;
        snapshot=fixture.snapshot; publisher=fixture.publisher;
        jdbc.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,status VARCHAR(1),del_flag VARCHAR(1))");
        jdbc.execute("CREATE TABLE sys_role(role_id BIGINT PRIMARY KEY,status VARCHAR(1),del_flag VARCHAR(1))");
        jdbc.execute("CREATE TABLE sys_user_role(user_id BIGINT,role_id BIGINT,PRIMARY KEY(user_id,role_id))");
        jdbc.execute("CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT,PRIMARY KEY(role_id,menu_id))");
        jdbc.execute("CREATE TABLE sys_menu(menu_id BIGINT PRIMARY KEY,status VARCHAR(1),perms VARCHAR(100))");
        jdbc.update("INSERT INTO sys_user VALUES(1,'0','0'),(90,'0','0'),(7,'0','0')");
        jdbc.update("INSERT INTO sys_role VALUES(2,'0','0')");
        jdbc.update("INSERT INTO sys_user_role VALUES(90,2)");
        jdbc.update("INSERT INTO sys_role_menu VALUES(2,3)");
        jdbc.update("INSERT INTO sys_menu VALUES(3,'0','business:audit:review')");
        String resource="mapper/business/ContentCapturePreviewPermissionMapper.xml";
        try(InputStream in=getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(in,session.getConfiguration(),resource,session.getConfiguration().getSqlFragments()).parse();
        }
        replaceCandidate(candidate());
        task=submit("preview");
        previews=new ContentCapturePreviewService(service,session.getMapper(AuditTaskMapper.class),
                session.getMapper(ContentCapturePreviewPermissionMapper.class),new TopicOperationsScopeAuthorizer());
        ProxyFactory proxy=new ProxyFactory(previews); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        mvc=MockMvcBuilders.standaloneSetup(new ContentCapturePreviewController((ContentCapturePreviewService)proxy.getProxy())).build();
        reviewer(1,"*:*:*");
    }
    @AfterEach void clearPrincipal() { SecurityContextHolder.clearContext(); }
    private void reviewer(long id,String permission) {
        SysUser user=new SysUser(); user.setUserId(id);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(id,1L,user,Collections.singleton(permission)),null,Collections.emptyList()));
    }
    private ObjectNode candidate() {
        ObjectNode root=json.createObjectNode(); root.put("contract","questify.content-capture-candidate.v1");
        root.put("schemaVersion",1);root.put("completeRelease",false);root.put("topicId",10);root.put("draftConfigVersion",4);
        ObjectNode components=root.putObject("components");
        ObjectNode ordered=json.createObjectNode();
        ordered.putArray("nodes").addObject().put("id",11).put("name","Frozen node").put("description","Captured narrative <b>only</b>")
                .put("address","PRIVATE ADDRESS").put("imgUrl","https://assets.invalid/image?token=PRIVATE").put("merchantMemberId",901);
        part(components,"orderedNodes",ordered);
        com.fasterxml.jackson.databind.node.ArrayNode chapters=json.createArrayNode();
        chapters.addObject().put("id",12).put("name","Frozen chapter").put("description","Before draft changes")
                .put("blocksJson","[{\"type\":\"text\",\"content\":\"Frozen prose\"},{\"type\":\"image\",\"content\":\"https://private.invalid/asset\"}]")
                .put("audioUrl","https://audio.invalid/private");
        part(components,"chapterSources",chapters);
        ObjectNode route=json.createObjectNode();route.put("journeyStory","{\"call\":{\"opener\":\"Frozen story\"}}");
        route.putObject("nodeAnswers").putObject("11").put("correctAnswer","server-only-answer").put("sensorConfig","PRIVATE CREDENTIAL");
        ObjectNode journey=route.putObject("nodeJourney").putObject("11");
        journey.putObject("brief").put("question","Frozen question");
        journey.putObject("sort").put("prompt","Order these cards").put("answer","PRIVATE ANSWER");
        journey.putObject("secretConfig").put("apiKey","PRIVATE API KEY");
        part(components,"legacyRouteSnapshot",route);
        components.putObject("commercialRightsEvidence").put("privateFinancialTerms","PRIVATE TERMS");
        components.putObject("npcScopeEvidence").put("credential","PRIVATE NPC");
        root.put("secretAnswer","server-only-answer");return root;
    }
    private void part(ObjectNode components,String key,JsonNode content) {
        ObjectNode component=components.putObject(key);component.put("status","FROZEN");component.set("content",content);
        component.put("capturedContentHash",CanonicalContentJson.sha256(CanonicalContentJson.write(content)));
    }
    private void replaceCandidate(ObjectNode value) {
        String canonical=CanonicalContentJson.write(value); snapshot.setCanonicalJson(canonical);
        snapshot.setContentHash(CanonicalContentJson.sha256(canonical));
        jdbc.update("UPDATE content_capture_snapshot SET canonical_json=?,content_hash=? WHERE id=?",canonical,snapshot.getContentHash(),snapshot.getId());
    }
    private void replaceBoundCandidate(ObjectNode value) {
        replaceCandidate(value);
        jdbc.update("UPDATE content_capture_review_binding SET content_hash=?",snapshot.getContentHash());
        String envelope=ContentCaptureReviewService.envelope(ContentCaptureReceipt.verify(run(()->session.getMapper(com.chengyinhub.business.mapper.ContentCaptureReviewMapper.class).lockSnapshot(snapshot.getId())),10,7,"capture",4,snapshot.getContentHash()),7);
        jdbc.update("UPDATE audit_task SET snapshot_json=? WHERE id=?",envelope,task);
    }
    private MockHttpServletRequestBuilder requestPreview() {
        return get("/business/audit/"+task+"/candidate-preview").param("expectedTaskVersion","0")
                .param("snapshotId",snapshot.getId().toString()).param("sourceConfigVersion","4").param("contentHash",snapshot.getContentHash());
    }
    private JsonNode call(MockHttpServletRequestBuilder request,int statusCode) throws Exception {
        String response=mvc.perform(request).andExpect(status().is(statusCode)).andExpect(jsonPath("$.code").value(statusCode))
                .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("server-only-answer"));assertFalse(response.contains("PRIVATE"));
        assertFalse(response.contains("https:"));assertFalse(response.contains("snapshotJson"));
        verifyNoInteractions(publisher);
        return json.readTree(response);
    }
    private void delegatedTestScope() {
        ITopicOperationsScopeAuthorizer allowed=mock(ITopicOperationsScopeAuthorizer.class);
        when(allowed.canViewTopic(10L)).thenReturn(true);
        ReflectionTestUtils.setField(previews,"scope",allowed);
    }

    @Test void returnsFrozenWhitelistOnlyWithExactIdentityAndNoWrites() throws Exception {
        JsonNode result=call(requestPreview(),200).path("data");
        assertEquals(Long.toString(task),result.path("auditTaskId").asText());assertTrue(result.path("auditTaskId").isTextual());
        assertEquals(snapshot.getContentHash(),result.path("contentHash").asText());
        assertFalse(result.path("previewComplete").asBoolean()); assertFalse(result.path("publicationEligible").asBoolean());
        assertFalse(result.path("completeRelease").asBoolean());
        assertTrue(result.toString().contains("Frozen prose"));assertTrue(result.toString().contains("Frozen question"));
        assertTrue(result.toString().contains("&lt;b&gt;only&lt;/b&gt;"));
        assertFalse(result.toString().contains("nodeAnswers"));assertFalse(result.toString().contains("rights"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_record",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT status FROM audit_task WHERE id=?",Integer.class,task));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_task WHERE biz_type='TOPIC'",Integer.class));
    }
    @Test void mutableSourceChangesNeverReplaceCapturedText() throws Exception {
        String before=call(requestPreview(),200).toString();
        jdbc.execute("ALTER TABLE cms_topic ADD name VARCHAR(100)");jdbc.update("UPDATE cms_topic SET name='NEW LIVE TEXT'");
        jdbc.execute("CREATE TABLE cms_topic_node(id BIGINT PRIMARY KEY,name VARCHAR(100))");jdbc.update("INSERT INTO cms_topic_node VALUES(11,'NEW LIVE NODE')");
        assertEquals(before,call(requestPreview(),200).toString());
    }
    @Test void wrongNamespaceUnauthenticatedInconsistentAndListOnlyPrincipalsDenied() throws Exception {
        SecurityContextHolder.clearContext();call(requestPreview(),403);
        AppUser app=new AppUser();app.setUserId(1L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginAppUser(1L,app),null,Collections.emptyList()));
        call(requestPreview(),403);
        reviewer(1,"business:audit:list");call(requestPreview(),403);
        reviewer(1,"*:*:*");((LoginUser)SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getUser().setUserId(90L);
        call(requestPreview(),403);
    }
    @Test void assignmentOrSameNumericOwnerIdNeverGrantsTopicScope() throws Exception {
        reviewer(90,"business:audit:review");jdbc.update("UPDATE audit_task SET current_assignee=90");call(requestPreview(),403);
        call(get("/business/audit/999999/candidate-preview").params(query()),403);
        reviewer(7,"*:*:*");call(requestPreview(),403);
    }
    @Test void currentUserRevocationBeatsCachedSuperadminSession() throws Exception {
        call(requestPreview(),200);jdbc.update("UPDATE sys_user SET status='1' WHERE user_id=1");call(requestPreview(),403);
        jdbc.update("UPDATE sys_user SET status='0',del_flag='2' WHERE user_id=1");call(requestPreview(),403);
        jdbc.update("DELETE FROM sys_user WHERE user_id=1");call(requestPreview(),403);
    }
    @Test void currentRoleMenuAndGrantRevocationsBeatCachedPermission() throws Exception {
        delegatedTestScope();reviewer(90,"business:audit:review");call(requestPreview(),200);
        jdbc.update("UPDATE sys_role SET status='1'");call(requestPreview(),403);jdbc.update("UPDATE sys_role SET status='0',del_flag='2'");call(requestPreview(),403);
        jdbc.update("UPDATE sys_role SET del_flag='0'");jdbc.update("UPDATE sys_menu SET status='1'");call(requestPreview(),403);
        jdbc.update("UPDATE sys_menu SET status='0',perms='business:audit:list'");call(requestPreview(),403);
        jdbc.update("UPDATE sys_menu SET perms='business:audit:review'");jdbc.update("DELETE FROM sys_role_menu");call(requestPreview(),403);
        jdbc.update("INSERT INTO sys_role_menu VALUES(2,3)");jdbc.update("DELETE FROM sys_user_role");call(requestPreview(),403);
    }
    @Test void currentAssignmentAndScopeAreRecheckedEveryRequest() throws Exception {
        delegatedTestScope();reviewer(90,"business:audit:review");call(requestPreview(),200);
        jdbc.update("UPDATE audit_task SET current_assignee=91");call(requestPreview(),403);
        jdbc.update("UPDATE audit_task SET current_assignee=90");call(requestPreview(),200);
        ReflectionTestUtils.setField(previews,"scope",new TopicOperationsScopeAuthorizer());call(requestPreview(),403);
    }
    @Test void revokedOwnerDeletedTopicAndCrossRealmBindingFailClosed() throws Exception {
        jdbc.update("UPDATE product_role SET entity_id=8");call(requestPreview(),409);
        jdbc.update("UPDATE product_role SET entity_id=7,entity_type='CLUB'");call(requestPreview(),409);
        jdbc.update("UPDATE product_role SET entity_type='MEMBER',status=0");call(requestPreview(),409);
        jdbc.update("UPDATE product_role SET status=1");jdbc.update("UPDATE cms_topic SET del_flag=2");call(requestPreview(),409);
    }
    @Test void currentVersionSnapshotHashStateAndBindingMustMatch() throws Exception {
        jdbc.update("UPDATE audit_task SET version=1");call(requestPreview(),409);jdbc.update("UPDATE audit_task SET version=0,status=9");call(requestPreview(),409);
        jdbc.update("UPDATE audit_task SET status=0");jdbc.update("UPDATE content_capture_review_binding SET source_config_version=5");call(requestPreview(),409);
        jdbc.update("UPDATE content_capture_review_binding SET source_config_version=4,snapshot_id=999");call(requestPreview(),409);
        jdbc.update("UPDATE content_capture_review_binding SET snapshot_id=?,member_id=8",snapshot.getId());call(requestPreview(),409);
    }
    @Test void tamperedFrozenBytesOrEnvelopeCannotBeDisplayed() throws Exception {
        jdbc.update("UPDATE content_capture_snapshot SET canonical_json=REPLACE(canonical_json,'Frozen node','Tampered node')");call(requestPreview(),409);
        jdbc.update("UPDATE content_capture_snapshot SET canonical_json=?",snapshot.getCanonicalJson());
        jdbc.update("UPDATE audit_task SET snapshot_json='{}'");call(requestPreview(),409);
        jdbc.update("UPDATE audit_task SET biz_type='TOPIC'");call(requestPreview(),409);
    }
    @Test void missingComponentsDisabledAndSchemaFailureAreSanitizedUnavailable() throws Exception {
        ReflectionTestUtils.setField(service,"enabled",false);call(requestPreview(),503);ReflectionTestUtils.setField(service,"enabled",true);
        ObjectNode missing=candidate();missing.remove("components");replaceBoundCandidate(missing);call(requestPreview(),503);
        ContentCaptureReviewSchemaPreflight guard=mock(ContentCaptureReviewSchemaPreflight.class);
        doThrow(new IllegalStateException("PRIVATE SCHEMA")).when(guard).verifyCurrentTransaction();ReflectionTestUtils.setField(service,"schemaPreflight",guard);
        call(requestPreview(),503);
    }
    @Test void componentHashMismatchMalformedNestedJsonAndPayloadBoundsFailClosed() throws Exception {
        ObjectNode value=candidate();((ObjectNode)value.path("components").path("orderedNodes")).put("capturedContentHash","bad");replaceBoundCandidate(value);call(requestPreview(),409);
        value=candidate();JsonNode content=value.path("components").path("chapterSources").path("content");((ObjectNode)content.get(0)).put("blocksJson","[[]");
        part((ObjectNode)value.path("components"),"chapterSources",content);replaceBoundCandidate(value);call(requestPreview(),503);
        value=candidate();JsonNode nodes=value.path("components").path("orderedNodes").path("content");
        ((ObjectNode)nodes.path("nodes").get(0)).put("description",String.join("",Collections.nCopies(4097,"x")));
        part((ObjectNode)value.path("components"),"orderedNodes",nodes);replaceBoundCandidate(value);call(requestPreview(),503);
    }
    @Test void markupLinksAndCredentialLookingFieldsAreNeverActiveOrDisclosed() throws Exception {
        ObjectNode value=candidate();ObjectNode nodes=(ObjectNode)value.path("components").path("orderedNodes").path("content");
        ((ObjectNode)nodes.path("nodes").get(0)).put("name","<script>alert('x')</script>").put("description","https://private.invalid/secret");
        part((ObjectNode)value.path("components"),"orderedNodes",nodes);replaceBoundCandidate(value);
        String result=call(requestPreview(),200).toString();assertFalse(result.contains("<script>"));assertTrue(result.contains("&lt;script&gt;"));assertTrue(result.contains("[REDACTED_LINK_OR_CREDENTIAL]"));
    }
    @Test void duplicateUnknownCoercedAndOverflowQueryFieldsAreRejected() throws Exception {
        call(requestPreview().param("snapshotId","2"),400);call(requestPreview().param("reviewerId","1"),400);
        for(String value:new String[]{"-1","1.0","01","9223372036854775808"," 1"})
            call(get("/business/audit/"+value+"/candidate-preview").params(query()),400);
    }
    private org.springframework.util.MultiValueMap<String,String> query() {
        org.springframework.util.MultiValueMap<String,String> query=new org.springframework.util.LinkedMultiValueMap<>();
        query.add("expectedTaskVersion","0");query.add("snapshotId",snapshot.getId().toString());query.add("sourceConfigVersion","4");query.add("contentHash",snapshot.getContentHash());return query;
    }
    @Test void realCaptureBuilderOutputIsPreviewableWithoutLegacyReads() throws Exception {
        ContentCaptureRows rows=ContentCaptureBuilderTest.rows();rows.topic.setConfigVersion(4L);
        rows.nodes.get(0).setName("Real builder frozen node");
        ContentCaptureCandidate built=new ContentCaptureBuilder().build(rows);
        replaceBoundCandidate((ObjectNode)json.readTree(built.serverOnlyJson()));
        String result=call(requestPreview(),200).toString();
        assertTrue(result.contains("Real builder frozen node"));
        assertFalse(result.contains("questionAnswer"));assertFalse(result.contains("correctAnswer"));
    }
    @Test void aggregateEntryAndEscapedTextLimitsNeverReturnSilentPartialPreview() throws Exception {
        ObjectNode value=candidate(),ordered=json.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode nodes=ordered.putArray("nodes");
        for(int i=0;i<257;i++)nodes.addObject().put("name","node");
        part((ObjectNode)value.path("components"),"orderedNodes",ordered);replaceBoundCandidate(value);call(requestPreview(),503);
        value=candidate();ordered=json.createObjectNode();nodes=ordered.putArray("nodes");
        for(int i=0;i<20;i++)nodes.addObject().put("description",String.join("",Collections.nCopies(2000,"x")));
        part((ObjectNode)value.path("components"),"orderedNodes",ordered);replaceBoundCandidate(value);call(requestPreview(),503);
    }
    @Test void utf8AggregateSizeIsBoundedEvenWhenCharacterCountFits() throws Exception {
        ObjectNode value=candidate();value.put("unselected",String.join("",Collections.nCopies(350000,"界")));
        replaceBoundCandidate(value);call(requestPreview(),503);
    }
    @Test void wrongRequestedIdentityAndInvalidStoredStateAreConflicts() throws Exception {
        org.springframework.util.MultiValueMap<String,String> q=query();q.set("expectedTaskVersion","1");
        call(get("/business/audit/"+task+"/candidate-preview").params(q),409);
        q=query();q.set("snapshotId","999");call(get("/business/audit/"+task+"/candidate-preview").params(q),409);
        q=query();q.set("sourceConfigVersion","5");call(get("/business/audit/"+task+"/candidate-preview").params(q),409);
        q=query();q.set("contentHash",String.format("%064d",0));call(get("/business/audit/"+task+"/candidate-preview").params(q),409);
        jdbc.update("UPDATE content_capture_snapshot SET state='INVALID'");call(requestPreview(),409);
        jdbc.update("UPDATE content_capture_snapshot SET state='FROZEN_INCOMPLETE',canonical_json='{' ");call(requestPreview(),409);
    }
    @Test void infrastructureFailureDoesNotBecomeAStaleConflictOrExposeDiagnostics() throws Exception {
        jdbc.execute("DROP TABLE sys_user");call(requestPreview(),503);
    }
    @Test void mapperIllegalStateFailureIsUnavailableNotBindingConflict() throws Exception {
        com.chengyinhub.business.mapper.ContentCaptureReviewMapper broken=mock(com.chengyinhub.business.mapper.ContentCaptureReviewMapper.class);
        when(broken.lockTopic(10L)).thenThrow(new IllegalStateException("PRIVATE DB FAILURE"));
        ReflectionTestUtils.setField(service,"mapper",broken);call(requestPreview(),503);
    }
    @Test void serviceCannotBeUsedWithoutTransactionOrAuthentication() {
        assertThrows(ContentCaptureReviewService.UnavailableException.class,()->previews.preview(task,0,snapshot.getId(),4,snapshot.getContentHash()));
        SecurityContextHolder.clearContext();assertThrows(AccessDeniedException.class,()->run(()->previews.preview(task,0,snapshot.getId(),4,snapshot.getContentHash())));
    }
    @Test void transientDatabaseFailureAndUnexpectedMapperArgumentAreUnavailable() throws Exception {
        ContentCapturePreviewPermissionMapper broken=mock(ContentCapturePreviewPermissionMapper.class);
        ReflectionTestUtils.setField(previews,"permissions",broken);
        when(broken.lockActiveUser(1L)).thenThrow(new org.springframework.dao.TransientDataAccessResourceException("PRIVATE DB FAILURE"));
        assertEquals("CONTENT_PREVIEW_UNAVAILABLE",call(requestPreview(),503).path("errorCode").asText());
        doThrow(new IllegalArgumentException("PRIVATE MAPPER FAILURE")).when(broken).lockActiveUser(1L);
        assertEquals("CONTENT_PREVIEW_UNAVAILABLE",call(requestPreview(),503).path("errorCode").asText());
    }
    @Test void malformedFrozenNarrativeContainersCannotSilentlyDisappear() throws Exception {
        ObjectNode value=candidate(), route=(ObjectNode)value.path("components").path("legacyRouteSnapshot").path("content");
        route.set("journeyStory",json.createObjectNode().put("call","bad"));
        part((ObjectNode)value.path("components"),"legacyRouteSnapshot",route);replaceBoundCandidate(value);call(requestPreview(),503);
        value=candidate();JsonNode chapters=value.path("components").path("chapterSources").path("content");
        ((ObjectNode)chapters.get(0)).set("blocksJson",json.createArrayNode());
        part((ObjectNode)value.path("components"),"chapterSources",chapters);replaceBoundCandidate(value);call(requestPreview(),503);
        value=candidate();route=(ObjectNode)value.path("components").path("legacyRouteSnapshot").path("content");
        route.put("journeyStory","{\"call\":{\"opener\":{\"secret\":\"not text\"}}}");
        part((ObjectNode)value.path("components"),"legacyRouteSnapshot",route);replaceBoundCandidate(value);call(requestPreview(),503);
    }
    @Test void rawNarrativeFieldLimitAppliesBeforeAnyWhitespaceNormalization() throws Exception {
        ObjectNode value=candidate(),route=(ObjectNode)value.path("components").path("legacyRouteSnapshot").path("content");
        route.put("journeyStory",json.createObjectNode().set("call",json.createObjectNode().put("opener",String.join("",Collections.nCopies(4096," "))+"x")).toString());
        part((ObjectNode)value.path("components"),"legacyRouteSnapshot",route);replaceBoundCandidate(value);call(requestPreview(),503);
    }

    @Test void malformedSelectedArraysAndNonObjectRowsAreUnavailable() throws Exception {
        ObjectNode value=candidate(),ordered=json.createObjectNode();ordered.putArray("nodes").add(1);
        part((ObjectNode)value.path("components"),"orderedNodes",ordered);replaceBoundCandidate(value);call(requestPreview(),503);
        value=candidate();part((ObjectNode)value.path("components"),"chapterSources",json.createArrayNode().add("not chapter"));
        replaceBoundCandidate(value);call(requestPreview(),503);
        value=candidate();JsonNode chapters=value.path("components").path("chapterSources").path("content");
        ((ObjectNode)chapters.get(0)).put("blocksJson","[42]");part((ObjectNode)value.path("components"),"chapterSources",chapters);
        replaceBoundCandidate(value);call(requestPreview(),503);
        value=candidate();ObjectNode route=(ObjectNode)value.path("components").path("legacyRouteSnapshot").path("content");
        route.put("journeyStory","{\"call\":{\"options\":[7]}}");part((ObjectNode)value.path("components"),"legacyRouteSnapshot",route);
        replaceBoundCandidate(value);call(requestPreview(),503);
    }
    @Test void escapedExpansionCountsTowardAggregateLimit() throws Exception {
        ObjectNode value=candidate(),ordered=json.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode nodes=ordered.putArray("nodes");
        for(int i=0;i<3;i++)nodes.addObject().put("name",String.join("",Collections.nCopies(4096,"<")));
        part((ObjectNode)value.path("components"),"orderedNodes",ordered);replaceBoundCandidate(value);call(requestPreview(),503);
    }

}
