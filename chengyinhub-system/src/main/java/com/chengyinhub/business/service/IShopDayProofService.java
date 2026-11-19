package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;

public interface IShopDayProofService {
    /**
     * 真实到店:写本人该节点的到店行(checkin_time)。
     * R16-ARRIVE:同主题同章节同地点的兄弟节点一并补写到店行(只补到店事实,不给完成/照片);
     * 已存在的行(含并发写入)不覆盖,重复调用幂等。
     */
    PlayerNodeProgress markArrived(Long memberId, Long activityId, CmsTopicNode node);
    PlayerNodeProgress submitPhoto(Long memberId, Long activityId, Long nodeId, String picUrl);

    /** 是否已扫店内码进店(B5 收窄:游戏题面与作答都以进店为前置)。 */
    boolean hasArrived(Long memberId, Long activityId, Long nodeId);

    /**
     * 本局是否已开局(本主题本局已有任一站的进度行,不看其他主题)。业主 2026-11-13:开局一次 300 米定位校验通过后,
     * 本局各站到点不再要求定位;还没有任何进度行的局,首次到达即「开局」,须过开局校验。
     */
    default boolean runStarted(Long memberId, Long activityId, Long topicId) {
        return false;
    }

    /** 游戏完成:进店行 status 0→1 条件更新;返回 true = 本次为首次完成(该发奖)。 */
    boolean markGameDone(Long memberId, Long activityId, Long topicId, Long nodeId);

    /**
     * F08:记下 {@link #markArrived} 写出的到店行的到达来源
     * ({@link com.chengyinhub.business.service.support.ArrivalSource})。只写第一次:排队计时只认 QR。
     */
    default void recordArrivalSource(PlayerNodeProgress arrived, String arrivalSource) {
    }

    /**
     * 自由探索到访(FE-STORY 5.2):在已写到店行的基础上记 visit_time / visit_method(只写第一次),
     * 同章节已有到店行的兄弟节点一起标记。不改 status、不核销、不发分。
     */
    default void markVisited(PlayerNodeProgress arrived, CmsTopicNode node, int visitMethod,
                             String longitude, String latitude) {
    }

    /** 自由探索到访配置;查不到返回 null(调用方按默认 GPS 300 米处理)。 */
    default com.chengyinhub.business.domain.FreeExploreVisitConfig visitConfig(Long nodeId) {
        return null;
    }

    /** 整个主题的到访配置,按 nodeId 索引;查不到返回空表。 */
    default java.util.Map<Long, com.chengyinhub.business.domain.FreeExploreVisitConfig> visitConfigs(Long topicId) {
        return java.util.Collections.emptyMap();
    }

    /* ---- CG-ALL-11 开局方式(DESK 桌面完成 / ALT 替代站开局);默认实现失败关闭 ---- */

    /** 本局开局方式;读不到 / 正常开局为 null。 */
    default String runStartMode(Long memberId, Long activityId, Long topicId) {
        return null;
    }

    /** 本局是否桌面完成(读失败按桌面完成，奖励只封顶不放宽)。 */
    default boolean deskRun(Long memberId, Long activityId, Long topicId) {
        return false;
    }

    /** 开局时记录方式:DESK/ALT 写行(写失败抛出，开局随之失败);正常开局清旧行(清理失败不拦开局)。 */
    default void recordRunStart(Long memberId, Long activityId, Long topicId, String mode, Long altStationId) {
        if (mode != null) throw new IllegalStateException("开局方式暂时不可用");
    }

    /** 本主题启用中的替代起点:id/name/longitude/latitude。 */
    default java.util.List<java.util.Map<String, Object>> altStations(Long topicId) {
        return java.util.Collections.emptyList();
    }

    /** 本主题启用中的某个替代起点;不存在 / 不属于本主题返回 null。 */
    default java.util.Map<String, Object> altStation(Long topicId, Long altStationId) {
        if (altStationId == null) return null;
        for (java.util.Map<String, Object> row : altStations(topicId)) {
            Object id = row.get("id");
            if (id != null && altStationId.toString().equals(String.valueOf(id))) return row;
        }
        return null;
    }
}
