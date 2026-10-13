package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.RoamAiContent;
import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.RoamAiContentMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 漫游无形陪伴 V1 的唯一消费口。
 *
 * <p>没有模型调用、没有地点原文回退：内容查询在 Mapper 层已经限制为审核通过，
 * 本类再对输入、占位符和输出长度做失败关闭校验。</p>
 */
@Service
public class RoamAiContentService {
    @Autowired
    private RoamAiContentMapper roamAiContentMapper;

    /** 只有非商户 POI 才允许读取独立审核的地点意义。 */
    public String approvedPoiMeaning(RoamPoi poi) {
        if (!isNonMerchantPoi(poi)) {
            return null;
        }
        RoamAiContent content = roamAiContentMapper.selectApprovedPoiMeaning(poi.getId());
        String text = displayableText(content == null ? null : content.getContentText());
        return text == null || RoamRouteStoryPolicy.containsForwardGuidance(text) ? null : text;
    }

    /**
     * 用已结算会话的服务端指标渲染一条审核模板。只有两个白名单占位符可用，
     * 不拼接玩家身份、原始坐标或轨迹文本。
     */
    public String approvedRouteStory(RoamSession session) {
        if (session == null || session.getId() == null || !Integer.valueOf(1).equals(session.getStatus())) {
            return null;
        }
        List<RoamAiContent> all = roamAiContentMapper.selectApprovedRouteStories();
        if (all == null || all.isEmpty()) {
            return null;
        }
        List<RoamAiContent> usable = new ArrayList<>();
        for (RoamAiContent content : all) {
            String text = displayableText(content == null ? null : content.getContentText());
            if (text != null && !RoamRouteStoryPolicy.containsForwardGuidance(text)) {
                usable.add(content);
            }
        }
        if (usable.isEmpty()) {
            return null;
        }
        int index = (int) Math.floorMod(session.getId(), (long) usable.size());
        String template = usable.get(index).getContentText().trim();
        String rendered = template
                .replace("{{tiles}}", String.valueOf(nonNegative(session.getTilesRevealed())))
                .replace("{{pois}}", String.valueOf(nonNegative(session.getPoisFound())));
        // 出现任何未解析占位符，宁可整条不展示，不能带未知字段或临时文本上线。
        if (rendered.contains("{{") || rendered.contains("}}")) {
            return null;
        }
        if (RoamRouteStoryPolicy.containsForwardGuidance(rendered)) {
            return null;
        }
        return displayableText(rendered);
    }

    private boolean isNonMerchantPoi(RoamPoi poi) {
        return poi != null && poi.getId() != null && poi.getMerchantId() == null
                && !Integer.valueOf(2).equals(poi.getType());
    }

    private int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private String displayableText(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() || text.length() > RoamAiContent.MAX_TEXT_LENGTH ? null : text;
    }
}
