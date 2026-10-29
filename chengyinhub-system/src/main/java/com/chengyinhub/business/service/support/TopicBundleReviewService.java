package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 专业主题 V2 的一次提交审核快照；表示层和 hash 规则只在这一处。 */
@Service
public class TopicBundleReviewService {
    private static final double DEFAULT_LATITUDE = 31.2304d;
    private static final double DEFAULT_LONGITUDE = 121.4737d;
    private static final double COORDINATE_EPSILON = 0.00001d;

    private final CmsTopicMapper topicMapper;
    private final CmsTopicChapterMapper chapterMapper;
    private final CmsTopicNodeMapper nodeMapper;
    private final CmsMemberTemplateMapper templateMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.mapper.MerchantChapterApplicationMapper applicationMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.mapper.MerchantChapterOfferMapper offerMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.mapper.MmsMerchantMapper merchantMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.mapper.ThemeStateKeyMapper themeStateKeyMapper;
    private final ObjectMapper json = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final AdvancedGameConfigValidator advancedConfigValidator = new AdvancedGameConfigValidator();

    public TopicBundleReviewService(CmsTopicMapper topicMapper,
                                    CmsTopicChapterMapper chapterMapper,
                                    CmsTopicNodeMapper nodeMapper,
                                    CmsMemberTemplateMapper templateMapper) {
        this.topicMapper = topicMapper;
        this.chapterMapper = chapterMapper;
        this.nodeMapper = nodeMapper;
        this.templateMapper = templateMapper;
    }

    /**
     * 自由定向(探店日)开放商家承接时，站点玩法由入驻商家提供，而商家只能承接已上架过审的主题。
     * 因此允许「零玩法」提交，但仅当每个未配玩法的站点都落在开放承接(recruitEnabled=1)的章节里；
     * 商家玩法在入驻时走供给审核，不绕过审核。owner 2026-10-20 决定(#299)。
     */
    static boolean awaitsMerchantGameplay(CmsTopic topic, List<CmsTopicChapter> chapters, List<CmsTopicNode> nodes) {
        if (topic == null || !Integer.valueOf(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE).equals(topic.getProductType())) {
            return false;
        }
        Set<Long> recruiting = new LinkedHashSet<Long>();
        for (CmsTopicChapter chapter : chapters) {
            if (chapter != null && chapter.getId() != null && Integer.valueOf(1).equals(chapter.getRecruitEnabled())) {
                recruiting.add(chapter.getId());
            }
        }
        if (recruiting.isEmpty()) return false;
        for (CmsTopicNode node : nodes) {
            boolean hasGameplay = node.getTemplateId() != null && node.getTemplateId() > 0L;
            if (!hasGameplay && (node.getChapterId() == null || !recruiting.contains(node.getChapterId()))) {
                return false;
            }
        }
        return true;
    }

    public Submission freezeForSubmission(Long topicId, Long ownerId) {
        return freezeForSubmission(topicId, ownerId, null);
    }

    public Submission freezeForSubmission(Long topicId, Long ownerId,
            List<com.chengyinhub.business.domain.ThemeStateKey> preparedDeclarations) {
        boolean currentRead = org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()
                && !org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        CmsTopic topic = currentRead ? topicMapper.selectCmsTopicByIdForUpdate(topicId)
                : topicMapper.selectCmsTopicById(topicId);
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) {
            throw new IllegalStateException("主题不存在或已删除");
        }
        if (ownerId == null || !ownerId.equals(topic.getMemberId())) {
            throw new IllegalStateException("无权提交该主题审核");
        }

        CmsTopicChapter chapterQuery = new CmsTopicChapter();
        chapterQuery.setTopicId(topicId);
        List<CmsTopicChapter> chapters = new ArrayList<CmsTopicChapter>(
                safe(currentRead ? chapterMapper.selectByTopicForUpdate(topicId)
                        : chapterMapper.selectCmsTopicChapterList(chapterQuery)));
        sortChapters(chapters);
        CmsTopicNode nodeQuery = new CmsTopicNode();
        nodeQuery.setTopicId(topicId);
        List<CmsTopicNode> nodes = new ArrayList<CmsTopicNode>(safe(nodeMapper.selectCmsTopicNodeList(nodeQuery)));
        sortNodes(nodes);
        Map<Long, Long> merchantOwners = Collections.emptyMap();
        if (currentRead || nodes.stream().anyMatch(node -> node.getMerchantMemberId() != null && !ownerId.equals(node.getMerchantMemberId()))) {
            CmsTopic locked = topicMapper.selectCmsTopicByIdForUpdate(topicId);
            if (locked == null || !ownerId.equals(locked.getMemberId()) || Integer.valueOf(2).equals(locked.getDelFlag())) throw stale();
            topic = locked;
            chapters = new ArrayList<CmsTopicChapter>(safe(chapterMapper.selectByTopicForUpdate(topicId)));
            sortChapters(chapters);
            // Re-read the proof in the locked topic/chapters before locking nodes/templates.
            merchantOwners = merchantTemplateOwners(topic, chapters, nodes);
            nodes = new ArrayList<CmsTopicNode>(safe(nodeMapper.selectByTopicForUpdate(topicId)));
            sortNodes(nodes);
            validateMerchantReferences(topic, nodes, merchantOwners);
        }
        if (chapters.isEmpty() || nodes.isEmpty()) {
            throw new IllegalArgumentException("专业主题至少需要一个章节和一个节点");
        }

        Set<Long> referencedTemplateIds = new LinkedHashSet<Long>();
        for (CmsTopicNode node : nodes) {
            validateCoordinate(node, chapters);
            if (node.getTemplateId() != null && node.getTemplateId() > 0L) {
                referencedTemplateIds.add(node.getTemplateId());
            }
        }
        if (referencedTemplateIds.isEmpty() && !awaitsMerchantGameplay(topic, chapters, nodes)) {
            throw new IllegalArgumentException("专业主题至少需要一个节点玩法");
        }

        List<Long> templateIds = new ArrayList<Long>(referencedTemplateIds);
        Collections.sort(templateIds);
        Map<Long, CmsMemberTemplate> currentTemplates = new LinkedHashMap<Long, CmsMemberTemplate>();
        if (currentRead && !templateIds.isEmpty()) {
            List<CmsMemberTemplate> locked = safe(templateMapper.selectCmsMemberTemplateByIdsForUpdate(templateIds));
            if (locked.size() != templateIds.size()) throw stale();
            for (CmsMemberTemplate template : locked) {
                if (template == null || !referencedTemplateIds.contains(template.getId())
                        || currentTemplates.put(template.getId(), template) != null) throw stale();
            }
        }
        List<CmsMemberTemplate> templates = new ArrayList<CmsMemberTemplate>();
        for (Long templateId : templateIds) {
            CmsMemberTemplate template = currentRead ? currentTemplates.get(templateId)
                    : templateMapper.selectCmsMemberTemplateById(templateId);
            validateTemplate(template, templateId, ownerId, merchantOwners.get(templateId));
            validateTemplateReferences(nodes, template, ownerId);
            templates.add(template);
        }

        ObjectNode topicContent = topicContent(topic, chapters, nodes, preparedDeclarations);
        ObjectNode topicSnapshot = json.createObjectNode();
        topicSnapshot.put("id", topic.getId());
        topicSnapshot.put("memberId", topic.getMemberId());
        put(topicSnapshot, "productType", topic.getProductType());
        put(topicSnapshot, "publishMode", topic.getPublishMode());
        put(topicSnapshot, "configVersion", topic.getConfigVersion());
        topicSnapshot.put("contentHash", sha256(topicContent));
        put(topicSnapshot, "name", topic.getName());
        topicSnapshot.set("content", topicContent.get("topic"));
        topicSnapshot.set("chapters", topicContent.get("chapters"));

        ArrayNode templateSnapshots = json.createArrayNode();
        for (CmsMemberTemplate template : templates) {
            ObjectNode content = templateContent(template);
            ObjectNode value = json.createObjectNode();
            value.put("id", template.getId());
            value.put("memberId", template.getMemberId());
            value.put("bundledDraft", content.path("bundledDraft").asBoolean());
            value.put("contentHash", sha256(content));
            put(value, "title", template.getTitle());
            put(value, "validationMethod", template.getValidationMethod());
            put(value, "advancedConfigJson", template.getAdvancedConfigJson());
            value.set("content", content);
            templateSnapshots.add(value);
        }

        ObjectNode snapshot = json.createObjectNode();
        snapshot.put("schemaVersion", 2);
        snapshot.put("bizType", AuditTask.BIZ_TOPIC);
        snapshot.set("topic", topicSnapshot);
        snapshot.set("bundledTemplates", templateSnapshots);
        return new Submission(writeStable(snapshot), templateIds);
    }

    /** 调用方事务先锁审核任务；这里按 topic → chapter → node → template 的固定顺序锁并裁决。 */
    public void applyDecision(Long topicId, Long submitterId, String snapshotJson, boolean approve) {
        if (topicId == null || submitterId == null) throw stale();
        JsonNode snapshot = snapshot(snapshotJson);
        JsonNode submittedTopic = snapshot.path("topic");
        JsonNode submittedTopicBody = submittedTopic.path("content");
        if (submittedTopic.path("id").asLong() != (topicId == null ? 0L : topicId.longValue())
                || submittedTopic.path("memberId").asLong() != (submitterId == null ? 0L : submitterId.longValue())
                || !sameField(submittedTopic, submittedTopicBody, "id")
                || !sameField(submittedTopic, submittedTopicBody, "memberId")
                || !sameField(submittedTopic, submittedTopicBody, "productType")
                || !sameField(submittedTopic, submittedTopicBody, "publishMode")
                || !sameField(submittedTopic, submittedTopicBody, "configVersion")
                || !sameField(submittedTopic, submittedTopicBody, "name")) {
            throw stale();
        }
        if (!submittedTopicBody.isObject() || !submittedTopic.path("chapters").isArray()) throw stale();
        ObjectNode submittedTopicContent = json.createObjectNode();
        submittedTopicContent.set("topic", submittedTopicBody);
        submittedTopicContent.set("chapters", submittedTopic.path("chapters"));
        if (!sha256(submittedTopicContent).equals(submittedTopic.path("contentHash").asText())) throw stale();

        List<Long> submittedIds = new ArrayList<Long>();
        Map<Long, JsonNode> submittedTemplates = new LinkedHashMap<Long, JsonNode>();
        for (JsonNode value : snapshot.path("bundledTemplates")) {
            long id = value.path("id").asLong(0L);
            JsonNode content = value.path("content");
            if (id <= 0L || !content.isObject()
                    || !sameField(value, content, "id") || !sameField(value, content, "memberId")
                    || !sameField(value, content, "bundledDraft")
                    || !sameField(value, content, "title") || !sameField(value, content, "validationMethod")
                    || !sameField(value, content, "advancedConfigJson")
                    || !sha256(content).equals(value.path("contentHash").asText())
                    || submittedTemplates.put(id, value) != null) throw stale();
            submittedIds.add(id);
        }
        Collections.sort(submittedIds);

        CmsTopic topic = topicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())
                || !submitterId.equals(topic.getMemberId())
                || submittedTopic.path("configVersion").asLong(Long.MIN_VALUE)
                        != (topic.getConfigVersion() == null ? 0L : topic.getConfigVersion())) {
            throw stale();
        }
        List<CmsTopicChapter> chapters = new ArrayList<CmsTopicChapter>(
                safe(chapterMapper.selectByTopicForUpdate(topicId)));
        sortChapters(chapters);
        CmsTopicNode locator = new CmsTopicNode(); locator.setTopicId(topicId);
        List<CmsTopicNode> locatedNodes = safe(nodeMapper.selectCmsTopicNodeList(locator));
        Map<Long, Long> merchantOwners = merchantTemplateOwners(topic, chapters, locatedNodes);
        List<CmsTopicNode> nodes = new ArrayList<CmsTopicNode>(safe(nodeMapper.selectByTopicForUpdate(topicId)));
        sortNodes(nodes);
        validateMerchantReferences(topic, nodes, merchantOwners);

        Set<Long> referencedIds = new LinkedHashSet<Long>();
        for (CmsTopicNode node : nodes) {
            validateCoordinate(node, chapters);
            if (node.getTemplateId() != null && node.getTemplateId() > 0L) referencedIds.add(node.getTemplateId());
        }
        List<Long> currentIds = new ArrayList<Long>(referencedIds);
        Collections.sort(currentIds);
        if (!submittedIds.equals(currentIds)) throw stale();

        List<CmsMemberTemplate> templates = submittedIds.isEmpty()
                ? Collections.<CmsMemberTemplate>emptyList()
                : safe(templateMapper.selectCmsMemberTemplateByIdsForUpdate(submittedIds));
        if (templates.size() != submittedIds.size()) throw stale();
        String currentTopicHash = sha256(topicContent(topic, chapters, nodes));
        if (!currentTopicHash.equals(submittedTopic.path("contentHash").asText())) throw stale();
        for (int i = 0; i < templates.size(); i++) {
            CmsMemberTemplate template = templates.get(i);
            Long expectedId = submittedIds.get(i);
            JsonNode submitted = submittedTemplates.get(expectedId);
            if (template == null || template.getMemberId() == null || !expectedId.equals(template.getId())
                    || submitted.path("memberId").asLong() != template.getMemberId().longValue()) {
                throw stale();
            }
            try {
                validateTemplate(template, expectedId, submitterId, merchantOwners.get(expectedId));
                validateTemplateReferences(nodes, template, submitterId);
            } catch (RuntimeException e) {
                throw stale();
            }
            if (!sha256(templateContent(template)).equals(submitted.path("contentHash").asText())) throw stale();
            boolean wasBundledDraft = submitted.path("bundledDraft").asBoolean(false);
            boolean isDraft = Integer.valueOf(0).equals(template.getDraftStatus());
            if (!wasBundledDraft && isDraft) throw stale();
            if (approve && wasBundledDraft && isDraft
                    && templateMapper.approveBundledDraft(expectedId, submitterId) != 1) {
                throw new IllegalStateException("随主题审核玩法状态保存失败:" + expectedId);
            }
        }
    }

    private JsonNode snapshot(String snapshotJson) {
        try {
            JsonNode root = json.readTree(snapshotJson);
            if (root == null || root.path("schemaVersion").asInt() != 2
                    || !AuditTask.BIZ_TOPIC.equals(root.path("bizType").asText())
                    || !root.path("topic").isObject() || !root.path("bundledTemplates").isArray()) {
                throw stale();
            }
            return root;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw stale();
        }
    }

    private IllegalStateException stale() {
        return new IllegalStateException("提交内容已变化，请创作者重新提交审核");
    }

    private ObjectNode topicContent(CmsTopic topic, List<CmsTopicChapter> chapters, List<CmsTopicNode> nodes) {
        return topicContent(topic, chapters, nodes, null);
    }

    private ObjectNode topicContent(CmsTopic topic, List<CmsTopicChapter> chapters, List<CmsTopicNode> nodes,
            List<com.chengyinhub.business.domain.ThemeStateKey> preparedDeclarations) {
        ObjectNode content = json.createObjectNode();
        ObjectNode topicJson = json.valueToTree(topic);
        topicJson.remove("createBy"); topicJson.remove("createTime");
        topicJson.remove("updateBy"); topicJson.remove("updateTime");
        topicJson.remove("remark"); topicJson.remove("params");
        topicJson.remove("status"); topicJson.remove("checkStatus");
        topicJson.remove("userStatus"); topicJson.remove("reason"); topicJson.remove("betaFlag");
        topicJson.remove("lifecycle");
        topicJson.remove("publishedRouteGraphJson"); topicJson.remove("publishedConfigVersion");
        topicJson.remove("routePublishedAt");
        // This authored field is @JsonIgnore on the API entity. Bind it explicitly to review,
        // while leaving the derived publishedThemeRuntimeConfigJson outside the content hash.
        if (StringUtils.isNotBlank(topic.getThemeRuntimeConfigJson())) {
            topicJson.set("themeRuntimeConfig", parseJson(topic.getThemeRuntimeConfigJson(), "主题运行配置 JSON 非法"));
        }
        ArrayNode currentDeclarations = declarationSnapshot(topic.getId());
        ArrayNode frozenDeclarations = preparedDeclarations == null ? currentDeclarations
                : ThemeStateKeyDeclarationSnapshot.capture(topic.getId(), preparedDeclarations);
        if (!frozenDeclarations.equals(currentDeclarations)) throw stale();
        // Existing empty-declaration reviews retain their old content identity; a subsequently added
        // declaration makes the current hash differ, rather than being silently backfilled.
        if (!frozenDeclarations.isEmpty()) topicJson.set("stateKeyDeclarations", frozenDeclarations);
        content.set("topic", topicJson);

        Map<Long, List<CmsTopicNode>> nodesByChapter = new LinkedHashMap<Long, List<CmsTopicNode>>();
        for (CmsTopicNode node : nodes) {
            nodesByChapter.computeIfAbsent(node.getChapterId(), ignored -> new ArrayList<CmsTopicNode>()).add(node);
        }
        ArrayNode chapterValues = json.createArrayNode();
        for (CmsTopicChapter chapter : chapters) {
            ObjectNode chapterJson = json.valueToTree(chapter);
            chapterJson.remove("createBy"); chapterJson.remove("createTime");
            chapterJson.remove("updateBy"); chapterJson.remove("updateTime");
            chapterJson.remove("remark"); chapterJson.remove("params"); chapterJson.remove("cmsTopicNodeList");
            if (StringUtils.isBlank(chapter.getBlocksJson())) chapterJson.putNull("blocks");
            else chapterJson.set("blocks", parseJson(chapter.getBlocksJson(), "章节 blocks JSON 非法"));
            ArrayNode nodeValues = json.createArrayNode();
            for (CmsTopicNode node : safe(nodesByChapter.get(chapter.getId()))) {
                ObjectNode nodeJson = json.valueToTree(node);
                nodeJson.remove("createBy"); nodeJson.remove("createTime");
                nodeJson.remove("updateBy"); nodeJson.remove("updateTime");
                nodeJson.remove("remark"); nodeJson.remove("params"); nodeJson.remove("checkinCode");
                nodeValues.add(nodeJson);
            }
            chapterJson.set("nodes", nodeValues);
            chapterValues.add(chapterJson);
        }
        content.set("chapters", chapterValues);
        return content;
    }

    private ArrayNode declarationSnapshot(Long topicId) {
        if (themeStateKeyMapper == null) throw new IllegalStateException("主题属性声明读取依赖缺失");
        boolean currentRead = org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                && !org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        java.util.List<com.chengyinhub.business.domain.ThemeStateKey> declarations;
        if (currentRead) {
            if (!topicId.equals(themeStateKeyMapper.lockTopicForStateKeyWrite(topicId))) throw stale();
            declarations = themeStateKeyMapper.selectByTopicIdForUpdate(topicId);
        } else declarations = themeStateKeyMapper.selectByTopicId(topicId);
        return ThemeStateKeyDeclarationSnapshot.capture(topicId, declarations);
    }

    /** Submission and its publication share one declaration identity, including auto-registered keys. */
    public void assertDeclarationsUnchanged(Long topicId, String snapshotJson) {
        if (topicId == null || topicId <= 0) throw stale();
        JsonNode submitted = snapshot(snapshotJson).path("topic");
        if (submitted.path("id").asLong(-1L) != topicId) throw stale();
        JsonNode expected = submitted.path("content").path("stateKeyDeclarations");
        if (expected.isMissingNode()) expected = json.createArrayNode();
        if (!expected.equals(declarationSnapshot(topicId))) throw stale();
    }

    private ObjectNode templateContent(CmsMemberTemplate template) {
        ObjectNode value = json.valueToTree(template);
        value.remove("createBy"); value.remove("createTime");
        value.remove("updateBy"); value.remove("updateTime");
        value.remove("remark"); value.remove("params");
        value.remove("status"); value.remove("publishStatus"); value.remove("draftStatus");
        value.remove("checkStatus"); value.remove("reason"); value.remove("delFlag");
        value.put("bundledDraft", Integer.valueOf(0).equals(template.getDraftStatus()));
        return value;
    }

    private Map<Long, Long> merchantTemplateOwners(CmsTopic topic, List<CmsTopicChapter> chapters, List<CmsTopicNode> nodes) {
        Map<Long, Long> owners = new LinkedHashMap<Long, Long>();
        List<CmsTopicNode> supplied = new ArrayList<CmsTopicNode>();
        for (CmsTopicNode node : nodes) if (node.getMerchantMemberId() != null
                && !topic.getMemberId().equals(node.getMerchantMemberId())) supplied.add(node);
        if (supplied.isEmpty()) return owners;
        if (!Integer.valueOf(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE).equals(topic.getProductType())) throw stale();
        if (supplied.stream().anyMatch(node -> node.getChapterId() == null || node.getId() == null)) throw stale();
        supplied.sort(Comparator.comparing(CmsTopicNode::getChapterId).thenComparing(CmsTopicNode::getMerchantMemberId)
                .thenComparing(CmsTopicNode::getId));
        for (CmsTopicNode node : supplied) {
            if (node.getTemplateId() == null || node.getTemplateId() <= 0L
                    || !Long.valueOf(1L).equals(node.getStatus()) || !Integer.valueOf(1).equals(node.getNodeAuditStatus())
                    || !topic.getId().equals(node.getTopicId())
                    || chapters.stream().noneMatch(ch -> node.getChapterId().equals(ch.getId()) && topic.getId().equals(ch.getTopicId()))) throw stale();
            com.chengyinhub.business.domain.MerchantChapterApplication application =
                    applicationMapper.selectByChapterAndMerchantForUpdate(node.getChapterId(), node.getMerchantMemberId());
            if (application == null || !Integer.valueOf(1).equals(application.getStatus()) || !Integer.valueOf(0).equals(application.getDelFlag())
                    || !topic.getId().equals(application.getTopicId()) || !node.getChapterId().equals(application.getChapterId())
                    || !node.getMerchantMemberId().equals(application.getMerchantId())) throw stale();
        }
        List<com.chengyinhub.business.domain.MerchantChapterOffer> offers = safe(offerMapper.selectByTopicIncludingInactiveForUpdate(topic.getId()));
        java.util.Date now = new java.util.Date();
        for (CmsTopicNode node : supplied) {
            boolean live = offers.stream().anyMatch(offer -> topic.getId().equals(offer.getTopicId())
                    && node.getChapterId().equals(offer.getChapterId()) && node.getMerchantMemberId().equals(offer.getMerchantId())
                    && Integer.valueOf(1).equals(offer.getStatus())
                    && (offer.getValidEnd() == null || offer.getValidEnd().after(now))
                    && StringUtils.isBlank(offer.getWithdrawReason()));
            com.chengyinhub.business.domain.MmsMerchant merchant = merchantMapper.selectMmsMerchantByMemberIdForUpdate(node.getMerchantMemberId());
            if (!live || merchant == null || !node.getMerchantMemberId().equals(merchant.getMemberId())
                    || !Long.valueOf(1L).equals(merchant.getStatus()) || !Long.valueOf(1L).equals(merchant.getAccountStatus())
                    || Integer.valueOf(2).equals(merchant.getDelFlag())) throw stale();
            Long previous = owners.put(node.getTemplateId(), node.getMerchantMemberId());
            if (previous != null && !previous.equals(node.getMerchantMemberId())) throw stale();
        }
        return owners;
    }

    private void validateMerchantReferences(CmsTopic topic, List<CmsTopicNode> nodes, Map<Long, Long> owners) {
        for (CmsTopicNode node : nodes) if (node.getMerchantMemberId() != null && !topic.getMemberId().equals(node.getMerchantMemberId())) {
            if (!java.util.Objects.equals(node.getMerchantMemberId(), owners.get(node.getTemplateId()))
                    || !Long.valueOf(1L).equals(node.getStatus()) || !Integer.valueOf(1).equals(node.getNodeAuditStatus())) throw stale();
        }
    }

    private void validateTemplateReferences(List<CmsTopicNode> nodes, CmsMemberTemplate template, Long publisherId) {
        for (CmsTopicNode node : nodes) if (template.getId().equals(node.getTemplateId())) {
            Long nodeOwner = node.getMerchantMemberId() == null ? publisherId : node.getMerchantMemberId();
            if (!nodeOwner.equals(template.getMemberId())) throw stale();
        }
    }

    private void validateTemplate(CmsMemberTemplate template, Long templateId, Long ownerId, Long suppliedOwnerId) {
        if (template == null || Integer.valueOf(2).equals(template.getDelFlag())) {
            throw new IllegalStateException("玩法不存在或已删除:" + templateId);
        }
        if (!ownerId.equals(template.getMemberId()) && !java.util.Objects.equals(suppliedOwnerId, template.getMemberId())) {
            throw new IllegalStateException("不能引用他人的玩法:" + templateId);
        }
        boolean draft = Integer.valueOf(0).equals(template.getDraftStatus())
                && Long.valueOf(0L).equals(template.getStatus())
                && Integer.valueOf(0).equals(template.getCheckStatus())
                && Long.valueOf(0L).equals(template.getPublishStatus());
        boolean approved = Integer.valueOf(1).equals(template.getDraftStatus())
                && Long.valueOf(1L).equals(template.getStatus())
                && Integer.valueOf(1).equals(template.getCheckStatus());
        if ((!ownerId.equals(template.getMemberId()) && !approved) || (!draft && !approved)) {
            throw new IllegalStateException("玩法不是可用版本或私有草稿:" + templateId);
        }
        if (StringUtils.isNotBlank(template.getAdvancedConfigJson())) {
            advancedConfigValidator.validateAndNormalize(template.getAdvancedConfigJson());
        }
        validateJson(template.getPreferenceJson(), "偏好玩法 JSON 非法");
        validateJson(template.getSensorConfig(), "传感玩法 JSON 非法");
        validateJson(template.getRewardRulesJson(), "奖励规则 JSON 非法");
        validateJson(template.getStoryJson(), "剧情 JSON 非法");
        validateJson(template.getQuestionOptionMediaJson(), "选项媒体 JSON 非法");
    }

    private void validateJson(String raw, String message) {
        if (StringUtils.isNotBlank(raw)) parseJson(raw, message);
    }

    private JsonNode parseJson(String raw, String message) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException(message, e);
        }
    }

    private void validateCoordinate(CmsTopicNode node, List<CmsTopicChapter> chapters) {
        if (node != null && node.getChapterId() != null && node.getId() != null) {
            for (CmsTopicChapter chapter : chapters) {
                if (!node.getChapterId().equals(chapter.getId()) || StringUtils.isBlank(chapter.getBlocksJson())) continue;
                JsonNode blocks = parseJson(chapter.getBlocksJson(), "章节 blocks JSON 非法");
                for (JsonNode block : blocks) {
                    if ("node".equals(block.path("type").asText())
                            && block.path("nodeId").isIntegralNumber()
                            && node.getId().longValue() == block.path("nodeId").asLong()
                            && block.path("locationRequired").isBoolean()
                            && !block.path("locationRequired").asBoolean()) {
                        if (node.getTemplateId() == null || node.getTemplateId() <= 0L
                                || StringUtils.isNotBlank(node.getLatitude())
                                || StringUtils.isNotBlank(node.getLongitude())) {
                            throw new IllegalArgumentException("故事玩法必须绑定模板且不能携带地点坐标");
                        }
                        return;
                    }
                }
            }
        }
        if (node == null || StringUtils.isBlank(node.getName()) || StringUtils.isBlank(node.getAddress())) {
            throw new IllegalArgumentException("节点缺少真实地点名称或地址");
        }
        double latitude = coordinate(node.getLatitude(), "纬度");
        double longitude = coordinate(node.getLongitude(), "经度");
        if (latitude < 0.8293d || latitude > 55.8271d || longitude < 72.004d || longitude > 137.8347d) {
            throw new IllegalArgumentException("节点坐标不在 GCJ-02 中国范围内:" + node.getId());
        }
        if (Math.abs(latitude - DEFAULT_LATITUDE) <= COORDINATE_EPSILON
                && Math.abs(longitude - DEFAULT_LONGITUDE) <= COORDINATE_EPSILON) {
            throw new IllegalArgumentException("节点仍是默认上海坐标，请重新在地图确认:" + node.getId());
        }
    }

    private double coordinate(String raw, String label) {
        try {
            double value = Double.parseDouble(raw == null ? "" : raw.trim());
            if (!Double.isFinite(value) || value == 0d) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("节点" + label + "无效");
        }
    }

    private String sha256(JsonNode value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(writeStable(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("审核快照 hash 生成失败", e);
        }
    }

    private String writeStable(JsonNode value) {
        try {
            return json.writeValueAsString(canonical(value));
        } catch (Exception e) {
            throw new IllegalStateException("审核快照序列化失败", e);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value == null || value.isValueNode()) return value;
        if (value.isArray()) {
            ArrayNode result = json.createArrayNode();
            for (JsonNode item : value) result.add(canonical(item));
            return result;
        }
        ObjectNode result = json.createObjectNode();
        Map<String, JsonNode> fields = new TreeMap<String, JsonNode>();
        value.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
        for (Map.Entry<String, JsonNode> entry : fields.entrySet()) {
            result.set(entry.getKey(), canonical(entry.getValue()));
        }
        return result;
    }

    private void sortNodes(List<CmsTopicNode> nodes) {
        nodes.sort(Comparator.comparing(CmsTopicNode::getChapterId, Comparator.nullsLast(Long::compareTo))
                .thenComparing(CmsTopicNode::getSortId, Comparator.nullsLast(Long::compareTo))
                .thenComparing(CmsTopicNode::getId, Comparator.nullsLast(Long::compareTo)));
    }

    private void sortChapters(List<CmsTopicChapter> chapters) {
        chapters.sort(Comparator.comparing(CmsTopicChapter::getSortId, Comparator.nullsLast(Long::compareTo))
                .thenComparing(CmsTopicChapter::getId, Comparator.nullsLast(Long::compareTo)));
    }

    private static <T> List<T> safe(List<T> rows) {
        return rows == null ? Collections.<T>emptyList() : rows;
    }

    private void put(ObjectNode node, String field, Object value) {
        node.set(field, json.valueToTree(value));
    }

    private boolean sameField(JsonNode envelope, JsonNode content, String field) {
        return envelope.path(field).equals(content.path(field));
    }

    public static final class Submission {
        private final String snapshotJson;
        private final List<Long> bundledTemplateIds;

        public Submission(String snapshotJson, List<Long> bundledTemplateIds) {
            this.snapshotJson = snapshotJson;
            this.bundledTemplateIds = Collections.unmodifiableList(new ArrayList<Long>(bundledTemplateIds));
        }

        public String getSnapshotJson() { return snapshotJson; }
        public List<Long> getBundledTemplateIds() { return bundledTemplateIds; }
    }
}
