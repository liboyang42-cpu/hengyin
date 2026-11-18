package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopicNode;

import java.util.List;

/**
 * F07(CG-ALL-3):后续站点照片到站前不显示,由服务端执行(抓包也拿不到图片地址)。
 *
 * <ul>
 *   <li>城市定向(product_type / mode 1):一个站的图可以下发,当且仅当它是第一站,或者本 run 已经到达
 *       (到店行 checkin_time 不为空)或已完成。扫码到站也算到达。</li>
 *   <li>自由探索(mode 2):店铺照片用来找店,不是谜题,不遮。</li>
 * </ul>
 * 任何返回站点图的玩家接口都必须经过本类判定(docs/contracts/station-image-visibility.md)。
 * 主题封面、章节封面不归本类管,照常下发。
 */
public final class StationImageVisibility {

    public static final String HIDDEN_FIELD = "imgHidden";

    private StationImageVisibility() {
    }

    /** null 按城市定向(1)处理:与 PlayNodeAccessService 的模式缺省一致,宁可多遮。 */
    public static boolean hidesLaterStations(Integer mode) {
        return mode == null || mode.intValue() != 2;
    }

    public static boolean visible(Integer mode, boolean firstStation, boolean reached) {
        return !hidesLaterStations(mode) || firstStation || reached;
    }

    /** 第一站:sort_id 最小(相同取 id 最小)的站;列表为空返回 null。 */
    public static Long firstStationId(List<CmsTopicNode> nodes) {
        if (nodes == null) return null;
        CmsTopicNode first = null;
        for (CmsTopicNode node : nodes) {
            if (node == null || node.getId() == null) continue;
            if (first == null || before(node, first)) first = node;
        }
        return first == null ? null : first.getId();
    }

    private static boolean before(CmsTopicNode a, CmsTopicNode b) {
        Long sa = a.getSortId() == null ? null : Long.valueOf(a.getSortId().longValue());
        Long sb = b.getSortId() == null ? null : Long.valueOf(b.getSortId().longValue());
        if (sa != null && sb != null && !sa.equals(sb)) return sa < sb;
        if (sa != null && sb == null) return true;
        if (sa == null && sb != null) return false;
        return a.getId() < b.getId();
    }
}
