-- SOURCE review only. Root owns migration/DAG approval; no DDL has been executed.
-- BE-06:不可变内容修订索引。补的是「跨发布历史」,不是快照引擎本身 ——
--   cms_topic.published_route_graph_json / published_config_version 继续是「最新发布头」的快速读面,
--   TopicRouteRuntimeServiceImpl::freezePublication / freezePublishedJourney 产出的精确冻结 payload
--   在这里按每次正式发布追加一行,永不 UPDATE。
-- 预检:表必须不存在;cms_topic 不加列,存量 resultMap / insert / update / fixture 都不动。
--
-- 为什么 snapshot_json 是 LONGTEXT 而不是 JSON:本表要保存「精确冻结 payload」及其字节哈希,
--   MySQL JSON 类型会规范化键序与空白,重放时字节已不同,hash 也就无法自证。这里只存不查内容。
--
-- 为什么没有 published_content_revision_id 列:当前修订 = max(revision_no),
--   它与 published_config_version 在同一个发布事务里一起推进,天然一致;
--   再加一列只会多一处可写状态和一批 fixture。
--
-- 唯一键:
--   uk_topic_revision_no      → 同一主题 revision_no 只能有一条(并发发布不会产生同号)
--   uk_topic_publish_request  → 同一发布意图号只能对应一条修订(MySQL 唯一索引允许多个 NULL,
--                               旧调用不传 publishRequestId 完全不受影响)
--   idx_topic_content_sha     → 普通索引。内容 hash 不是幂等键:A→B→恢复A 是第三条修订,
--                               第 1、3 条 hash 相同属于正常事实,不能被唯一约束挡住。
CREATE TABLE cms_topic_content_revision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    topic_id BIGINT NOT NULL,
    revision_no BIGINT NOT NULL,
    schema_version INT NOT NULL,
    content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_json LONGTEXT NOT NULL COMMENT '精确冻结私有 payload(含答案),字节原样,仅服务端读',
    public_manifest_json JSON NOT NULL COMMENT '白名单公共投影:无答案/无 nodeConfigs/无运营合同',
    source_content_revision_id BIGINT NULL,
    publish_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    origin VARCHAR(24) NOT NULL COMMENT 'PUBLISH=本次发布写入 / LEGACY_IMPORTED=存量发布头原样导入',
    create_by BIGINT NULL,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_topic_revision_no (topic_id, revision_no),
    UNIQUE KEY uk_topic_publish_request (topic_id, publish_request_id),
    KEY idx_topic_content_sha (topic_id, content_sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主题内容不可变修订索引:每次正式发布一条,永不改写';

-- 无历史回填。存量已发布主题第一次被显式 LEGACY_IMPORTED 导入时,hash 取当时真实存储的
-- published_route_graph_json 字节,绝不用当下 live 模板重建后冒充旧版。
-- 老局没有修订引用时继续读自己 moduleConfigSnapshotJson,本表为空不改变任何旧行为。
-- Readback:UNIQUE(topic_id,revision_no) 与 UNIQUE(topic_id,publish_request_id) 都在;
--   (topic_id,content_sha256) 必须是普通 KEY 而不是 UNIQUE;snapshot_json 为 LONGTEXT。
-- Rollback:只删本表。cms_topic 的发布头与所有会话快照不受影响。
-- Install before the publish writer/reader switch; keep rows across re-publication, never TTL。
