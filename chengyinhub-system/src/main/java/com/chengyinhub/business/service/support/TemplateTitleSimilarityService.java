package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.mapper.CmsTemplateLibraryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * O5b-5:模板发布时的标题相似度提示 —— 只提示(WARN),不拦发布。
 *
 * <p>确定性算法,不调外部 AI、不引新依赖:标题去空白/标点/大小写后,按字符二元组 Dice 系数比对模板广场
 * 在架条目(不含本人);归一化后全等或 Dice ≥ {@link #WARN_THRESHOLD} 即出一条 warn。
 * 返回结构沿用发布前试检查的 {@link TopicPublishCheckService.PublishCheckIssue}({level, code, nodeId, message}),
 * 与 O5a 的 publish-check WARN 档同形(O5a 合入后如有字段增补,以它为准对齐)。
 * 比对池查询失败只记日志、不出提示 —— 提示本身不是闸,不能因它让发布失败。</p>
 */
@Service
public class TemplateTitleSimilarityService {

    private static final Logger log = LoggerFactory.getLogger(TemplateTitleSimilarityService.class);

    public static final String CODE_TEMPLATE_TITLE_SIMILAR = "TEMPLATE_TITLE_SIMILAR";
    static final double WARN_THRESHOLD = 0.8d;
    static final int POOL_LIMIT = 500;
    static final int MAX_MATCHES = 3;

    private final CmsTemplateLibraryMapper libraryMapper;

    /** O5b-5 开关(默认关)。 */
    @Value("${chengyin.audit.o5b.template-title-similarity.enabled:false}")
    private boolean enabled = false;

    public TemplateTitleSimilarityService(CmsTemplateLibraryMapper libraryMapper) {
        this.libraryMapper = libraryMapper;
    }

    /** 发布时调用:开关关闭或标题为空返回空列表;否则最多 {@link #MAX_MATCHES} 条 warn。 */
    public List<TopicPublishCheckService.PublishCheckIssue> check(String title, Long ownerMemberId) {
        List<TopicPublishCheckService.PublishCheckIssue> issues = new ArrayList<>();
        if (!enabled || title == null) return issues;
        String normalized = ContentEditClassifier.normalize(title);
        if (normalized.isEmpty()) return issues;
        List<CmsTemplateLibrary> pool;
        try {
            pool = libraryMapper.selectPublicTitlesForSimilarity(ownerMemberId, POOL_LIMIT);
        } catch (RuntimeException e) {
            log.warn("[O5b-5] 模板标题相似度比对池读取失败,本次不提示: {}", e.getMessage());
            return issues;
        }
        if (pool == null) return issues;
        Map<String, Integer> self = bigrams(normalized);
        for (CmsTemplateLibrary row : pool) {
            if (row == null || row.getTitle() == null) continue;
            if (ownerMemberId != null && ownerMemberId.equals(row.getMemberId())) continue;
            String other = ContentEditClassifier.normalize(row.getTitle());
            if (other.isEmpty()) continue;
            double score = normalized.equals(other) ? 1d : dice(self, bigrams(other));
            if (score >= WARN_THRESHOLD) {
                issues.add(TopicPublishCheckService.PublishCheckIssue.warn(CODE_TEMPLATE_TITLE_SIMILAR, null,
                        "标题与模板广场已有模板「" + row.getTitle() + "」高度相似(相似度 "
                                + Math.round(score * 100) + "%),建议换个更有辨识度的名字;本提示不影响发布"));
                if (issues.size() >= MAX_MATCHES) break;
            }
        }
        return issues;
    }

    /** 字符二元组 Dice 系数;单字标题按单字比。 */
    static double similarity(String left, String right) {
        String a = ContentEditClassifier.normalize(left == null ? "" : left);
        String b = ContentEditClassifier.normalize(right == null ? "" : right);
        if (a.isEmpty() || b.isEmpty()) return 0d;
        if (a.equals(b)) return 1d;
        return dice(bigrams(a), bigrams(b));
    }

    private static double dice(Map<String, Integer> a, Map<String, Integer> b) {
        int total = 0;
        for (int count : a.values()) total += count;
        for (int count : b.values()) total += count;
        if (total == 0) return 0d;
        int overlap = 0;
        for (Map.Entry<String, Integer> entry : a.entrySet()) {
            Integer other = b.get(entry.getKey());
            if (other != null) overlap += Math.min(entry.getValue(), other);
        }
        return 2d * overlap / total;
    }

    private static Map<String, Integer> bigrams(String text) {
        int[] cps = text.codePoints().toArray();
        Map<String, Integer> grams = new HashMap<>();
        if (cps.length == 1) {
            grams.put(new String(cps, 0, 1), 1);
            return grams;
        }
        for (int i = 0; i + 1 < cps.length; i++) {
            grams.merge(new String(cps, i, 2), 1, Integer::sum);
        }
        return grams;
    }
}
