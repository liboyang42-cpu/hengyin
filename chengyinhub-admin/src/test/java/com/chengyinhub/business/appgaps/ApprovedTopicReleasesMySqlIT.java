package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.ApprovedTopicReleaseMapper;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 已批准主题发行真库 IT（cy_gaps_b）：迁移幂等；送审走真 AuditTaskMapper（开放单 refresh 版本 +1 / 关闭后新单版本 0）；
 * 审核通过后 prepare → publish → status；同 requestId 重放同回执；同一已批准快照不重复分配；内容审后变化即 409；
 * 回执不变量不成立时整个送审回滚、不留捕获行。
 */
@EnabledIfEnvironmentVariable(named = "APPGAPS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApprovedTopicReleasesMySqlIT {
    static final long TOPIC = 990601L, MEMBER = 990602L, CHAPTER = 990611L, NODE = 990621L, TEMPLATE = 990631L;
    AppGapsMySql my; ApprovedTopicReleaseMapper mapper; AuditTaskMapper tasks; TransactionTemplate tx;

    @BeforeAll void setup() throws Exception {
        my = new AppGapsMySql("migration_20261113_appgaps_approved_topic_release.sql");
        mapper = my.mapper(ApprovedTopicReleaseMapper.class, "mapper/business/appgaps/ApprovedTopicReleaseMapper.xml");
        tasks = my.mapper(AuditTaskMapper.class, "mapper/business/AuditTaskMapper.xml");
        tx = new TransactionTemplate(my.tx);
    }

    @BeforeEach @AfterAll void clean() {
        my.db.update("delete from approved_topic_release where topic_id=?", TOPIC);
        my.db.update("delete from approved_topic_review_submission where topic_id=?", TOPIC);
        my.db.update("delete from audit_record where task_id in (select id from audit_task where biz_type='TOPIC' and biz_id=?)", TOPIC);
        my.db.update("delete from audit_task where biz_type='TOPIC' and biz_id=?", TOPIC);
        my.db.update("delete from cms_topic_node where topic_id=?", TOPIC);
        my.db.update("delete from cms_topic_chapter where topic_id=?", TOPIC);
        my.db.update("delete from cms_member_template where id=?", TEMPLATE);
        my.db.update("delete from cms_topic where id=?", TOPIC);
    }

    void seed() {
        my.db.update("insert into cms_topic(id,member_id,product_type,publish_mode,del_flag,name,description,category_ids,img_url,config_version)"
                + " values(?,?,1,'pro',0,'IT 城南夜游','夜里走一圈','1,2','https://x/c.jpg',3)", TOPIC, MEMBER);
        my.db.update("insert into cms_topic_chapter(id,topic_id,name,sort_id,blocks) values(?,?,'第一章',1,?)", CHAPTER, TOPIC,
                "[{\"type\":\"text\",\"key\":\"t1\",\"content\":\"开场\"},{\"type\":\"node\",\"key\":\"n1\",\"nodeId\":" + NODE + "}]");
        my.db.update("insert into cms_member_template(id,member_id,title,category_id,question_name,question_answer,rule_instructions,merchant_guide)"
                + " values(?,?,'钟声谜题',7,'几点?','十二点','听钟','商家秘密')", TEMPLATE, MEMBER);
        my.db.update("insert into cms_topic_node(id,topic_id,chapter_id,name,longitude,latitude,img_url,node_time,template_id,sort_id)"
                + " values(?,?,?,'钟楼','121.1','31.2','https://x/n.jpg',15,?,1)", NODE, TOPIC, CHAPTER, TEMPLATE);
    }

    long openTask(int status, int version) {
        my.db.update("insert into audit_task(biz_type,biz_id,submitter_id,source_type,status,submitted_at,snapshot_json,version,create_time)"
                + " values('TOPIC',?,?,'topic_bundle_v2',?,now(),'{\"v\":0}',?,now())", TOPIC, MEMBER, status, version);
        return my.db.queryForObject("select max(id) from audit_task where biz_type='TOPIC' and biz_id=?", Long.class, TOPIC);
    }

    /** 与 AuditTaskServiceImpl#submitOrRefresh 同一组 SQL：有开放单 refresh（version+1），否则新单 version 0。 */
    ApprovedTopicReleases service(String bundle) {
        return new ApprovedTopicReleases(mapper, (topicId, member) -> {
            AuditTask open = tasks.selectOpenAuditTaskForUpdate("TOPIC", topicId);
            if (open != null) {
                assertEquals(1, tasks.refreshOpenAuditSnapshot(open.getId(), "TOPIC", "topic_bundle_v2", bundle, member, new Date()));
                return open.getId();
            }
            AuditTask t = new AuditTask();
            t.setBizType("TOPIC"); t.setBizId(topicId); t.setSubmitterId(member); t.setSourceType("topic_bundle_v2");
            t.setRiskLevel("NORMAL"); t.setStatus(0); t.setSubmittedAt(new Date()); t.setSnapshotJson(bundle); t.setVersion(0);
            t.setCreateTime(new Date());
            tasks.insertAuditTask(t);
            return t.getId();
        });
    }

    <T> T inTx(Supplier<T> s) { return tx.execute(st -> s.get()); }

    void approve(long taskId) {
        my.db.update("update audit_task set status=1, version=version+1 where id=?", taskId);
        my.db.update("insert into audit_record(task_id,action,operator_id,create_time) values(?,'APPROVE',1,now())", taskId);
    }

    static String selector(long task, long version, String hash, String req) {
        return "{\"topicId\":" + TOPIC + ",\"observedAuditTaskId\":" + task + ",\"observedAuditTaskVersion\":" + version
                + ",\"sourceConfigVersion\":3,\"snapshotHash\":\"" + hash + "\",\"requestId\":\"" + req + "\"}";
    }

    static String publish(Map<String, Object> p, String req) {
        return "{\"topicId\":" + TOPIC + ",\"auditTaskId\":" + p.get("auditTaskId") + ",\"auditTaskVersion\":" + p.get("auditTaskVersion")
                + ",\"auditSnapshotHash\":\"" + p.get("auditSnapshotHash") + "\",\"expectedHeadRevision\":" + p.get("headRevision")
                + ",\"expectedManifestHash\":\"" + p.get("manifestHash") + "\",\"requestId\":\"" + req + "\"}";
    }

    @Test void openTaskReviewThenApprovedReleaseEndToEnd() {
        seed();
        long task = openTask(0, 2);
        ApprovedTopicReleases s = service("{\"bundle\":\"v1\"}");
        Map<String, Object> capture = s.reviewPrepare(MEMBER, "{\"topicId\":" + TOPIC + ",\"observedAuditTaskId\":" + task + "}");
        String hash = (String) capture.get("snapshotHash");
        assertFalse(ApprovedTopicReleases.canonical(capture).contains("十二点"), "答案不出线");
        assertFalse(ApprovedTopicReleases.canonical(capture).contains("商家秘密"), "商家指引不出线");

        String req = "6F1C2D0E-1111-4111-8111-000000000001";
        Map<String, Object> receipt = inTx(() -> s.reviewSubmit(MEMBER, selector(task, 2, hash, req)));
        assertEquals(task, receipt.get("auditTaskId"));
        assertEquals(3L, receipt.get("submittedTaskVersion"));
        assertEquals(0L, receipt.get("submittedTaskStatus"));
        assertEquals(receipt, inTx(() -> s.reviewSubmit(MEMBER, selector(task, 2, hash, req))), "同 requestId 重放同一回执");
        assertEquals(receipt, s.reviewStatus(MEMBER, selector(task, 2, hash, req)));
        assertEquals(409, code(() -> s.reviewStatus(MEMBER, selector(task, 1, hash, req))));
        assertEquals(404, code(() -> s.reviewStatus(MEMBER, selector(task, 2, hash, "6F1C2D0E-1111-4111-8111-00000000dead"))));
        Map<String, Object> obs = s.reviewCurrent(MEMBER, selector(task, 2, hash, req));
        assertEquals(true, obs.get("matchesSubmittedCapture"));
        assertEquals(0L, obs.get("observedTaskStatus"));

        assertEquals(409, code(() -> s.prepare(MEMBER, "{\"topicId\":" + TOPIC + ",\"auditTaskId\":" + task + "}")), "未通过不能准备发行");
        approve(task);
        Map<String, Object> p = s.prepare(MEMBER, "{\"topicId\":" + TOPIC + ",\"auditTaskId\":" + task + "}");
        assertEquals(4L, p.get("auditTaskVersion"));
        assertEquals(0L, p.get("headRevision"));
        assertEquals(hash, p.get("auditSnapshotHash"));
        assertFalse(ApprovedTopicReleases.canonical(p).contains("https://x/n.jpg"), "节点图片不进发行");
        assertEquals(403, code(() -> s.prepare(990699L, "{\"topicId\":" + TOPIC + ",\"auditTaskId\":" + task + "}")));

        String pub = "6F1C2D0E-2222-4222-8222-000000000001";
        Map<String, Object> r = inTx(() -> s.publish(MEMBER, publish(p, pub)));
        assertEquals(true, r.get("currentlyApproved"));
        assertEquals(1L, r.get("schemaVersion"));
        assertEquals(r, inTx(() -> s.publish(MEMBER, publish(p, pub))), "同 requestId 重放同一发行");
        assertEquals(r, s.status(MEMBER, publish(p, pub)));
        assertEquals(1, my.db.queryForObject("select count(*) from approved_topic_release where topic_id=?", Integer.class, TOPIC));
        assertEquals(409, code(() -> inTx(() -> s.publish(MEMBER, publish(p, "6F1C2D0E-2222-4222-8222-000000000002")))),
                "发行头已推进，旧准备不能再分配");
        assertEquals(404, code(() -> s.status(990699L, publish(p, pub))));

        my.db.update("update cms_topic_node set name='钟楼(改)' where id=?", NODE);
        assertEquals(false, s.reviewCurrent(MEMBER, selector(task, 2, hash, req)).get("matchesSubmittedCapture"));
        assertEquals(409, code(() -> s.prepare(MEMBER, "{\"topicId\":" + TOPIC + ",\"auditTaskId\":" + task + "}")), "审后改内容即 409");
        my.db.update("update audit_task set status=2, version=version+1 where id=?", task);
        assertEquals(false, s.status(MEMBER, publish(p, pub)).get("currentlyApproved"), "发行回执不代表当前仍通过");
    }

    @Test void closedObservedTaskCreatesNewTaskAtVersionZero() {
        seed();
        long rejected = openTask(2, 5);
        ApprovedTopicReleases s = service("{\"bundle\":\"v2\"}");
        String hash = (String) s.reviewPrepare(MEMBER, "{\"topicId\":" + TOPIC + ",\"observedAuditTaskId\":" + rejected + "}").get("snapshotHash");
        Map<String, Object> receipt = inTx(() -> s.reviewSubmit(MEMBER, selector(rejected, 5, hash, "6F1C2D0E-3333-4333-8333-000000000001")));
        assertNotEquals(rejected, receipt.get("auditTaskId"));
        assertEquals(0L, receipt.get("submittedTaskVersion"));
        assertEquals(0L, receipt.get("submittedTaskStatus"));
        assertEquals(409, code(() -> inTx(() -> s.reviewSubmit(MEMBER, selector(rejected, 5, hash, "6F1C2D0E-3333-4333-8333-000000000002")))),
                "另有开放单时不能再按已关闭任务送审");
    }

    @Test void staleCaptureOrBrokenInvariantRollsBackEverything() {
        seed();
        long task = openTask(0, 0);
        ApprovedTopicReleases s = service("{\"bundle\":\"v3\"}");
        String hash = (String) s.reviewPrepare(MEMBER, "{\"topicId\":" + TOPIC + ",\"observedAuditTaskId\":" + task + "}").get("snapshotHash");
        my.db.update("update cms_topic set name='IT 城南夜游 改' where id=?", TOPIC);
        assertEquals(409, code(() -> inTx(() -> s.reviewSubmit(MEMBER, selector(task, 0, hash, "6F1C2D0E-4444-4444-8444-000000000001")))));
        my.db.update("update cms_topic set name='IT 城南夜游' where id=?", TOPIC);

        // 送审口把版本推进了两次：回执不变量不成立，整个事务（含任务 refresh）回滚。
        ApprovedTopicReleases twice = new ApprovedTopicReleases(mapper, (topicId, member) -> {
            my.db.update("update audit_task set version=version+2 where id=?", task);
            return task;
        });
        assertEquals(409, code(() -> inTx(() -> twice.reviewSubmit(MEMBER, selector(task, 0, hash, "6F1C2D0E-4444-4444-8444-000000000002")))));
        assertEquals(0, my.db.queryForObject("select version from audit_task where id=?", Integer.class, task));
        assertEquals(0, my.db.queryForObject("select count(*) from approved_topic_review_submission where topic_id=?", Integer.class, TOPIC));
    }

    static int code(Runnable r) {
        try { r.run(); } catch (ServiceException e) { return e.getCode(); }
        return 0;
    }
}
