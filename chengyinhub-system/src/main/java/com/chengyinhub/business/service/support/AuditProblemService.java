package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.AuditProblem;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditProblemMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * O-05 #4:主题/玩法驳回的逐项问题(audit_problem)。
 *
 * <p>prepare 只校验不落库,调用方在任何状态写入之前调用,非法输入整单 400;条目必须属于本次审核对象
 * (章节/节点按库里的 topic_id,玩法按本主题节点实际引用)。record 在驳回记录写入后逐条落库。
 */
@Service
public class AuditProblemService {
    public static final int MAX_ITEMS = 50;
    public static final int MAX_TEXT_LENGTH = 200;

    private final AuditProblemMapper problemMapper;
    private final CmsTopicChapterMapper chapterMapper;
    private final CmsTopicNodeMapper nodeMapper;

    public AuditProblemService(AuditProblemMapper problemMapper, CmsTopicChapterMapper chapterMapper,
                               CmsTopicNodeMapper nodeMapper) {
        this.problemMapper = problemMapper;
        this.chapterMapper = chapterMapper;
        this.nodeMapper = nodeMapper;
    }

    public static boolean supports(AuditTask task) {
        return task != null && (AuditTask.BIZ_TOPIC.equals(task.getBizType())
                || AuditTask.BIZ_TEMPLATE.equals(task.getBizType())
                || AuditTask.BIZ_TEMPLATE_LIBRARY.equals(task.getBizType()));
    }

    /** Syntax-only immutable intent: replay must not depend on subsequently edited content. */
    public static List<java.util.Map<String, Object>> intent(List<AuditReviewDTO.ProblemItem> items) {
        List<java.util.Map<String, Object>> result = new ArrayList<>();
        if (items == null) return result;
        if (items.size() > MAX_ITEMS) throw new IllegalArgumentException("逐项问题最多 " + MAX_ITEMS + " 条");
        for (AuditReviewDTO.ProblemItem item : items) {
            if (item == null) throw new IllegalArgumentException("逐项问题不能为空");
            String text = item.getReasonText() == null ? "" : item.getReasonText().trim();
            if (text.isEmpty() || text.length() > MAX_TEXT_LENGTH) throw new IllegalArgumentException("逐项问题说明须为1至200字");
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("itemType", item.getItemType() == null ? "" : item.getItemType().trim().toUpperCase(Locale.ROOT));
            row.put("itemId", item.getItemId() == null ? null : item.getItemId().toString());
            row.put("reasonCode", item.getReasonCode() == null || item.getReasonCode().trim().isEmpty() ? null
                    : AuditReasonCodes.normalize(Collections.singletonList(item.getReasonCode())).get(0));
            row.put("reasonText", text);
            result.add(row);
        }
        return result;
    }

    public List<AuditProblem> prepare(AuditTask task, List<AuditReviewDTO.ProblemItem> items) {
        if (items == null || items.isEmpty()) return Collections.emptyList();
        if (!supports(task)) throw new IllegalArgumentException("该审核类型不支持逐项问题");
        if (items.size() > MAX_ITEMS) throw new IllegalArgumentException("逐项问题最多 " + MAX_ITEMS + " 条");
        List<Long> topicTemplateIds = null;
        List<AuditProblem> rows = new ArrayList<>();
        for (AuditReviewDTO.ProblemItem item : items) {
            if (item == null) throw new IllegalArgumentException("逐项问题不能为空");
            String itemType = item.getItemType() == null ? "" : item.getItemType().trim().toUpperCase(Locale.ROOT);
            Long itemId = item.getItemId();
            if (AuditTask.BIZ_TOPIC.equals(task.getBizType())) {
                if (AuditProblem.ITEM_TEMPLATE.equals(itemType) && itemId != null && topicTemplateIds == null) {
                    topicTemplateIds = topicTemplateIds(task.getBizId());
                }
                requireTopicItem(task.getBizId(), itemType, itemId, topicTemplateIds);
            } else {
                // 玩法 / 模板广场审核对象就是这一个玩法:只允许针对它本身。
                if (!AuditProblem.ITEM_TEMPLATE.equals(itemType)
                        || (itemId != null && !itemId.equals(task.getBizId()))) {
                    throw notInScope();
                }
            }
            String text = item.getReasonText() == null ? "" : item.getReasonText().trim();
            if (text.isEmpty()) throw new IllegalArgumentException("逐项问题说明不能为空");
            if (text.length() > MAX_TEXT_LENGTH) {
                throw new IllegalArgumentException("逐项问题说明不能超过 " + MAX_TEXT_LENGTH + " 字");
            }
            String code = item.getReasonCode() == null || item.getReasonCode().trim().isEmpty() ? null
                    : AuditReasonCodes.normalize(Collections.singletonList(item.getReasonCode())).get(0);
            AuditProblem row = new AuditProblem();
            row.setTaskId(task.getId());
            row.setBizType(task.getBizType());
            row.setBizId(task.getBizId());
            row.setItemType(itemType);
            row.setItemId(itemId);
            row.setReasonCode(code);
            row.setReasonText(text);
            rows.add(row);
        }
        return rows;
    }

    public void record(List<AuditProblem> rows, Long recordId, Long ownerMemberId, Date now) {
        for (AuditProblem row : rows) {
            row.setRecordId(recordId);
            row.setOwnerMemberId(ownerMemberId);
            row.setCreateTime(now);
            if (problemMapper.insertAuditProblem(row) != 1) {
                throw new IllegalStateException("逐项驳回原因保存失败");
            }
        }
    }

    public List<AuditProblem> listForOwner(Long taskId, Long ownerMemberId) {
        if (taskId == null || ownerMemberId == null) return Collections.emptyList();
        List<AuditProblem> rows = problemMapper.selectByTaskForOwner(taskId, ownerMemberId);
        return rows == null ? Collections.<AuditProblem>emptyList() : rows;
    }

    private void requireTopicItem(Long topicId, String itemType, Long itemId, List<Long> topicTemplateIds) {
        if (AuditProblem.ITEM_TOPIC.equals(itemType)) {
            if (itemId != null && !itemId.equals(topicId)) throw notInScope();
            return;
        }
        if (itemId == null) throw new IllegalArgumentException("章节/节点/玩法问题必须指明条目 id");
        if (AuditProblem.ITEM_CHAPTER.equals(itemType)) {
            CmsTopicChapter chapter = chapterMapper.selectCmsTopicChapterById(itemId);
            if (chapter == null || !topicId.equals(chapter.getTopicId())) throw notInScope();
        } else if (AuditProblem.ITEM_NODE.equals(itemType)) {
            CmsTopicNode node = nodeMapper.selectCmsTopicNodeById(itemId);
            if (node == null || !topicId.equals(node.getTopicId())) throw notInScope();
        } else if (AuditProblem.ITEM_TEMPLATE.equals(itemType)) {
            if (topicTemplateIds == null || !topicTemplateIds.contains(itemId)) throw notInScope();
        } else {
            throw new IllegalArgumentException("未知的问题条目类型");
        }
    }

    private List<Long> topicTemplateIds(Long topicId) {
        CmsTopicNode locator = new CmsTopicNode();
        locator.setTopicId(topicId);
        List<Long> ids = new ArrayList<>();
        List<CmsTopicNode> nodes = nodeMapper.selectCmsTopicNodeList(locator);
        if (nodes != null) for (CmsTopicNode node : nodes) {
            if (node != null && topicId.equals(node.getTopicId()) && node.getTemplateId() != null) {
                ids.add(node.getTemplateId());
            }
        }
        return ids;
    }

    private static IllegalArgumentException notInScope() {
        return new IllegalArgumentException("问题条目不属于本次审核内容");
    }
}
