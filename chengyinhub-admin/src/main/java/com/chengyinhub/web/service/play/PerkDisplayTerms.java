package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * /api/play/nodes 的只读附加字段,全部是加字段、不改既有判定:
 * <ul>
 *   <li>FE-G5:「本店权益」的展示条款(含在票内 / 另付说明 / 需预约);读不到(含未落迁移的库)只让这几项缺席。</li>
 *   <li>FE-G7:主题级 perkOnly —— 下发的节点里没有任何一个带玩法(hasGame)时为 true。</li>
 * </ul>
 */
final class PerkDisplayTerms {
    private static final Logger log = LoggerFactory.getLogger(PerkDisplayTerms.class);

    private PerkDisplayTerms() {
    }

    static Map<String, MerchantChapterOffer> load(MerchantChapterOfferMapper mapper, List<CmsTopicNode> nodes) {
        if (mapper == null || nodes == null || nodes.isEmpty()) return Collections.emptyMap();
        List<Long> chapterIds = new ArrayList<>();
        for (CmsTopicNode n : nodes) {
            if (n != null && n.getChapterId() != null && !chapterIds.contains(n.getChapterId())) chapterIds.add(n.getChapterId());
        }
        if (chapterIds.isEmpty()) return Collections.emptyMap();
        try {
            Map<String, MerchantChapterOffer> out = new HashMap<>();
            List<MerchantChapterOffer> rows = mapper.selectLiveDisplayByChapterIds(chapterIds);
            for (MerchantChapterOffer offer : rows == null ? Collections.<MerchantChapterOffer>emptyList() : rows) {
                if (offer == null || offer.getChapterId() == null || offer.getMerchantId() == null) continue;
                out.put(offer.getChapterId() + ":" + offer.getMerchantId(), offer);
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("本店权益展示条款读取失败 chapters={}", chapterIds, e);
            return Collections.emptyMap();
        }
    }

    static void put(Map<String, Object> perk, MerchantChapterOffer terms) {
        if (perk == null || terms == null) return;
        if (terms.getIncluded() != null) perk.put("included", terms.getIncluded() == 1);
        if (terms.getExtraSpendText() != null) perk.put("extraSpendText", terms.getExtraSpendText());
        if (terms.getReserveRequired() != null) perk.put("reserveRequired", terms.getReserveRequired() == 1);
    }

    /**
     * FE-G7:主题级「只有权益、没有游戏」。只有自由定向(playMode=2)才会逐节点算 hasGame,
     * 所以只在该模式、且每个下发节点都明确 hasGame=false 时为 true;其它模式恒 false;空列表 null(不下结论)。
     */
    static Boolean perkOnly(int playMode, Object nodes) {
        if (!(nodes instanceof List<?> list) || list.isEmpty()) return null;
        if (playMode != 2) return Boolean.FALSE;
        for (Object node : list) {
            if (!(node instanceof Map<?, ?> m) || !Boolean.FALSE.equals(m.get("hasGame"))) return Boolean.FALSE;
        }
        return Boolean.TRUE;
    }
}
