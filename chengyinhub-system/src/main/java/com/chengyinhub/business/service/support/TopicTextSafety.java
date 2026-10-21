package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.dto.PublishSafetyRequest;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.service.IContentSafetyGate;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.util.JourneyReviewTexts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 主题创建/编辑的文本机审(2026-10-20 分级审核 P1):主题内容先发后审。
 *
 * <p>全部文本(名称/副标题/简介 + 剧情/章节/票种等叙事文本)都走统一门禁
 * {@link IContentSafetyGate#decide},审核模式 POST_REVIEW:
 * <ul>
 *   <li>明确违规 → BLOCK,拒绝保存(叙事文本逐条复检,提示里带字段名);</li>
 *   <li>机审要人工 / 审不了 / 缺 openid / 检测关闭 → 照常发布,但打标(flagged)留待抽检;</li>
 *   <li>通过 → 照常发布。</li>
 * </ul>
 * 本类只裁决,不建审核单;打标后的抽检单由调用方在主题落库后建(门禁拿不到 bizId)。
 */
public final class TopicTextSafety {

    /** 违规拦截的统一文案(与活动 P0「活动内容包含违规信息」同口径) */
    public static final String BLOCK_MESSAGE = "主题内容包含违规信息，请修改后重试";

    /** 与 JourneyReviewTexts 的分批上限同值:msgSecCheck v2 单次 content 上限 2500,留余量 */
    static final int MAX_BATCH_CHARS = 2000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TopicTextSafety() {
    }

    /** 裁决结果:blocked=拒绝保存;flagged=照常发布但留待抽检。 */
    public static final class Verdict {
        private final String blockMessage;
        private final String flagReason;

        private Verdict(String blockMessage, String flagReason) {
            this.blockMessage = blockMessage;
            this.flagReason = flagReason;
        }

        public boolean isBlocked() { return blockMessage != null; }
        public String getBlockMessage() { return blockMessage; }
        public boolean isFlagged() { return blockMessage == null && flagReason != null; }
        public String getFlagReason() { return flagReason; }
    }

    /**
     * @param contentId 编辑时传主题 id,新建时为 null(门禁先发后审不建单,不需要 bizId)
     * @param mainText  名称/副标题/简介合并文本;为空或未改动时传 null 跳过
     * @param entries   叙事文本(调用方已按「未改动不复检」过滤)
     */
    public static Verdict check(IContentSafetyGate gate, Long memberId, String openid, Long contentId,
                                String mainText, List<JourneyReviewTexts.Entry> entries) {
        String flag = null;
        if (mainText != null && !mainText.trim().isEmpty()) {
            SafetyDecision d = decide(gate, memberId, openid, contentId, mainText);
            if (d == null || d.isBlock()) return new Verdict(BLOCK_MESSAGE, null);
            if (d.isFlagged()) flag = d.getFlagReason();
        }
        List<JourneyReviewTexts.Entry> batch = new ArrayList<JourneyReviewTexts.Entry>();
        int chars = 0;
        if (entries != null) for (JourneyReviewTexts.Entry entry : entries) {
            if (entry == null || entry.getText() == null || entry.getText().trim().isEmpty()) continue;
            if (!batch.isEmpty() && chars + entry.getText().length() > MAX_BATCH_CHARS) {
                Verdict v = checkBatch(gate, memberId, openid, contentId, batch);
                if (v.isBlocked()) return v;
                if (flag == null) flag = v.getFlagReason();
                batch = new ArrayList<JourneyReviewTexts.Entry>();
                chars = 0;
            }
            batch.add(entry);
            chars += entry.getText().length();
        }
        if (!batch.isEmpty()) {
            Verdict v = checkBatch(gate, memberId, openid, contentId, batch);
            if (v.isBlocked()) return v;
            if (flag == null) flag = v.getFlagReason();
        }
        return new Verdict(null, flag);
    }

    private static Verdict checkBatch(IContentSafetyGate gate, Long memberId, String openid, Long contentId,
                                      List<JourneyReviewTexts.Entry> batch) {
        String[] parts = new String[batch.size()];
        for (int i = 0; i < batch.size(); i++) parts[i] = batch.get(i).getText();
        SafetyDecision whole = decide(gate, memberId, openid, contentId, IWxContentSecurityService.joinText(parts));
        boolean blocked = whole == null || whole.isBlock();
        // 合并文本只得到「要人工」时也逐条复检(与旧 JourneyReviewTexts.check 同口径):
        // 一条要人工的段落不能把同批里另一条违规段落盖过去。审不了/缺身份/检测关闭逐条也审不了,不复检。
        boolean reviewOnly = !blocked && whole.isFlagged() && "TEXT_REVIEW".equals(whole.getFlagReason())
                && batch.size() > 1;
        if (!blocked && !reviewOnly) return new Verdict(null, whole.isFlagged() ? whole.getFlagReason() : null);
        // 逐条复检找出字段,给作者能改的提示;合并命中而单条都不命中,仍按合并结果拦(上下文拼接后违规)
        for (JourneyReviewTexts.Entry entry : batch) {
            SafetyDecision one = decide(gate, memberId, openid, contentId, entry.getText());
            if (one == null || one.isBlock()) {
                return new Verdict(BLOCK_MESSAGE + "（字段：" + entry.getField() + "）", null);
            }
        }
        return blocked ? new Verdict(BLOCK_MESSAGE, null) : new Verdict(null, whole.getFlagReason());
    }

    private static SafetyDecision decide(IContentSafetyGate gate, Long memberId, String openid, Long contentId,
                                         String text) {
        PublishSafetyRequest req = new PublishSafetyRequest();
        req.setContentType(AuditTask.BIZ_TOPIC);
        req.setContentId(contentId);
        req.setMemberId(memberId);
        req.setOpenid(openid);
        req.setText(text);
        req.setReviewMode(PublishSafetyRequest.ReviewMode.POST_REVIEW.name());
        return gate.decide(req);
    }

    // ---------- 编辑「只复检改动过的文本」 ----------

    /** 把已保存的文本字段收进语料;JSON 字段取全部字符串叶子(剧情/规则/章节块配置等)。 */
    public static void addText(Set<String> known, String text) {
        if (text != null && !text.trim().isEmpty()) {
            known.add(text);
            known.add(text.trim());
        }
    }

    public static void addJsonTexts(Set<String> known, String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) return;
        try {
            collectLeaves(MAPPER.readTree(rawJson), known);
        } catch (Exception notJson) {
            addText(known, rawJson);
        }
    }

    private static void collectLeaves(JsonNode node, Set<String> known) {
        if (node == null) return;
        if (node.isTextual()) {
            addText(known, node.asText());
        } else if (node.isContainerNode()) {
            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) collectLeaves(children.next(), known);
        }
    }

    /** 已保存过的同一段文本不再送检;语料里没有的(新增/改动)照常送检。 */
    public static List<JourneyReviewTexts.Entry> changedOnly(Collection<JourneyReviewTexts.Entry> entries,
                                                             Set<String> known) {
        List<JourneyReviewTexts.Entry> changed = new ArrayList<JourneyReviewTexts.Entry>();
        if (entries == null) return changed;
        for (JourneyReviewTexts.Entry entry : entries) {
            if (entry == null || entry.getText() == null) continue;
            if (known == null || !known.contains(entry.getText())) changed.add(entry);
        }
        return changed;
    }

    /** 名称/副标题/简介:null = 本次没送(不改原值);送了且与原值不同才算改动。 */
    public static boolean changed(String incoming, String stored) {
        return incoming != null && !incoming.equals(stored);
    }
}
