package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.utils.GeoDistanceUtils;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IShopDayProofService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class ShopDayProofServiceImpl implements IShopDayProofService {

    /** R16-ARRIVE:同地址无商家绑定时,两点间距 ≤ 该值才算同一地点。 */
    private static final double SAME_PLACE_METERS = 30d;

    @Autowired
    private PlayerNodeProgressMapper playerNodeProgressMapper;

    @Autowired
    private IPlayerNodeProgressService playerNodeProgressService;

    @Autowired
    private CmsTopicNodeMapper cmsTopicNodeMapper;

    /**
     * R16-ARRIVE 方案 b:本人真实到店(扫店内码/GPS 到点)成功后,把到店记录**追加**给
     * 本主题本章节内同一地点的兄弟节点 —— 原型「一站连玩多步」只需在第一站扫一次码。
     *
     * <p>为什么写在 markArrived 而不是放宽 hasArrived 查询:拍照(submitPhoto)、完成
     * (markGameDone)、核销等下游戏节都按 node 读 player_node_progress 行,读侧补算要在
     * 每个下游各改一遍;写侧共享一次,下游一字不改。
     *
     * <p>边界:只在同一 member、同一 activity、同一 topic、同一 chapter 内共享;
     * 只共享「到店」(checkin_time)事实,不给兄弟节点写完成/照片;已有行(含软删)一律不覆盖;
     * 动态码的单次消费语义在调用方,不在此处。
     */
    @Override
    public PlayerNodeProgress markArrived(Long memberId, Long activityId, CmsTopicNode node) {
        if (memberId == null || activityId == null || node == null
                || node.getId() == null || node.getTopicId() == null) {
            throw new IllegalArgumentException("到店记录缺少归属");
        }
        PlayerNodeProgress probe = probe(memberId, activityId, node.getId());
        probe.setTopicId(node.getTopicId());
        // 复用进度服务的原实例解析，入口只解析一次；后续读写显式使用同一实例，
        // 即使过程中开了重玩局，也不能把本次到达或兄弟节点迁到新局。
        List<PlayerNodeProgress> existing = playerNodeProgressService.selectPlayerNodeProgressList(probe);
        final String playInstanceId = probe.getPlayInstanceId();
        if (StringUtils.isBlank(playInstanceId)) {
            throw new IllegalStateException("到店记录缺少游玩实例");
        }
        if (existing != null && !existing.isEmpty()) {
            PlayerNodeProgress row = existing.get(0);
            if (row.getCheckinTime() != null) {
                shareArrivalWithSiblings(memberId, activityId, node, row.getCheckinTime(), playInstanceId);
            }
            return row;
        }

        PlayerNodeProgress progress = arrivalProbe(memberId, activityId, node.getTopicId(), node.getId(), playInstanceId);
        progress.setStatus(0);
        progress.setCheckinTime(new Date());
        progress.setDelFlag(0);
        progress.setCreateTime(progress.getCheckinTime());
        try {
            if (playerNodeProgressMapper.insertPlayerNodeProgress(progress) != 1) {
                throw new IllegalStateException("到店记录写入失败");
            }
            shareArrivalWithSiblings(memberId, activityId, node, progress.getCheckinTime(), playInstanceId);
            return progress;
        } catch (DuplicateKeyException duplicate) {
            List<PlayerNodeProgress> raced = playerNodeProgressMapper.selectPlayerNodeProgressList(probe);
            if (raced == null || raced.isEmpty()) throw duplicate;
            return raced.get(0);
        }
    }

    /** 为同章节同地点的兄弟节点补写到店行;已存在(含并发写入)一律跳过,不覆盖任何既有事实。 */
    private void shareArrivalWithSiblings(Long memberId, Long activityId, CmsTopicNode node, Date checkinTime,
                                         String playInstanceId) {
        if (cmsTopicNodeMapper == null || node == null
                || node.getTopicId() == null || node.getChapterId() == null) {
            return;
        }
        CmsTopicNode siblingProbe = new CmsTopicNode();
        siblingProbe.setTopicId(node.getTopicId());
        siblingProbe.setChapterId(node.getChapterId());
        List<CmsTopicNode> siblings = cmsTopicNodeMapper.selectCmsTopicNodeList(siblingProbe);
        if (siblings == null || siblings.isEmpty()) return;
        for (CmsTopicNode sibling : siblings) {
            if (sibling == null || sibling.getId() == null || sibling.getId().equals(node.getId())) continue;
            if (!samePlace(node, sibling)) continue;
            if (hasProgressRow(memberId, activityId, node.getTopicId(), sibling.getId(), playInstanceId)) continue;

            PlayerNodeProgress row = arrivalProbe(memberId, activityId, node.getTopicId(), sibling.getId(), playInstanceId);
            row.setStatus(0);
            row.setCheckinTime(checkinTime);
            row.setDelFlag(0);
            row.setCreateTime(checkinTime);
            try {
                playerNodeProgressMapper.insertPlayerNodeProgress(row);
            } catch (DuplicateKeyException raced) {
                // 并发下同一兄弟节点已被写入:保留那一行,不覆盖
            }
        }
    }

    private boolean hasProgressRow(Long memberId, Long activityId, Long topicId, Long nodeId, String playInstanceId) {
        List<PlayerNodeProgress> rows = playerNodeProgressMapper.selectPlayerNodeProgressList(
                arrivalProbe(memberId, activityId, topicId, nodeId, playInstanceId));
        return rows != null && !rows.isEmpty();
    }

    private PlayerNodeProgress arrivalProbe(Long memberId, Long activityId, Long topicId, Long nodeId,
                                            String playInstanceId) {
        PlayerNodeProgress p = probe(memberId, activityId, nodeId);
        p.setTopicId(topicId);
        p.setPlayInstanceId(playInstanceId);
        return p;
    }

    /**
     * 同一地点判据(按优先级):
     * 1) 双方都有承接商家 ⇒ 认商家绑定(member_id 相同即同一地点);
     * 2) 否则 ⇒ 地址完全相同,且双方坐标有效、球面距离 ≤ 30 米。
     * 商家为主、地址为辅:商家节点即使地址手填不一致,也仍算同一家店;
     * 无商家绑定(主办方自建)的节点没有商家可比,只能靠地址 + 坐标。
     */
    private static boolean samePlace(CmsTopicNode a, CmsTopicNode b) {
        if (a == null || b == null) return false;
        if (a.getMerchantMemberId() != null && b.getMerchantMemberId() != null) {
            return a.getMerchantMemberId().equals(b.getMerchantMemberId());
        }
        String address = StringUtils.trimToNull(a.getAddress());
        if (address == null || !address.equals(StringUtils.trimToNull(b.getAddress()))) return false;
        Double lat1 = parseCoordinate(a.getLatitude(), -90d, 90d);
        Double lng1 = parseCoordinate(a.getLongitude(), -180d, 180d);
        Double lat2 = parseCoordinate(b.getLatitude(), -90d, 90d);
        Double lng2 = parseCoordinate(b.getLongitude(), -180d, 180d);
        if (lat1 == null || lng1 == null || lat2 == null || lng2 == null) return false;
        double distance = GeoDistanceUtils.haversineMeters(lat1, lng1, lat2, lng2);
        return Double.isFinite(distance) && distance <= SAME_PLACE_METERS;
    }

    /** 经纬度文本 → 有限且在范围内的 double;NaN、Infinity、越界、坏文本一律 null(调用方失败关闭)。 */
    private static Double parseCoordinate(String value, double min, double max) {
        if (StringUtils.isBlank(value)) return null;
        try {
            double parsed = Double.parseDouble(value.trim());
            if (!Double.isFinite(parsed) || parsed < min || parsed > max) return null;
            return parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public boolean hasArrived(Long memberId, Long activityId, Long nodeId) {
        List<PlayerNodeProgress> rows = playerNodeProgressMapper.selectPlayerNodeProgressList(
                probe(memberId, activityId, nodeId));
        return rows != null && !rows.isEmpty() && rows.get(0).getCheckinTime() != null;
    }

    @Override
    public boolean markGameDone(Long memberId, Long activityId, Long topicId, Long nodeId) {
        return playerNodeProgressMapper.markShopDayGameDone(memberId, activityId, topicId, nodeId) > 0;
    }

    @Override
    public PlayerNodeProgress submitPhoto(Long memberId, Long activityId, Long nodeId, String picUrl) {
        List<PlayerNodeProgress> rows = playerNodeProgressMapper.selectPlayerNodeProgressList(
                probe(memberId, activityId, nodeId));
        if (rows == null || rows.isEmpty() || rows.get(0).getCheckinTime() == null) {
            throw new IllegalStateException("请先扫码确认到店");
        }
        PlayerNodeProgress row = rows.get(0);
        // ★status 在探店日(mode2)上承载的是「游戏答完没有」，不是「节点通关没有」——
        //   B5 收窄裁决把这一列复用给了 markShopDayGameDone(0→1)。所以 0 与 1 都是
        //   合法的拍照起点：0 = 扫码进店没配游戏 / 还没答，1 = 刚答完游戏。
        //   原来那句 `!= 0` 会让「答题 → 下一步·拍现场凭证」这条本 commit 自己新加的动线
        //   在配了游戏的店 100% 抛错，而通关走 registration_entitlement ⇒ 不报警、无资损，
        //   只静默丢失履约照片证据。
        //   ⚠️ 照片的「不可覆盖」由下面那道 picUrl 守卫 + SQL 的 `pic_url is null` 承重，
        //   从来不该由 status 承重 —— 两件事，两道闸。
        //   其余取值仍然 fail-closed：不认识的状态一律不许改写凭证。
        if (!isPhotoWritableStatus(row.getStatus())) {
            throw new IllegalStateException("该节点已完成，不能改写到店凭证");
        }
        if (row.getPicUrl() != null) {
            if (row.getPicUrl().equals(picUrl)) return row;
            throw new IllegalStateException("到店照片已提交，不能覆盖原始凭证");
        }
        if (playerNodeProgressMapper.updateShopDayPhoto(row.getId(), picUrl) != 1) {
            List<PlayerNodeProgress> readback = playerNodeProgressMapper.selectPlayerNodeProgressList(
                    probe(memberId, activityId, nodeId));
            if (readback == null || readback.isEmpty() || !isPhotoWritableStatus(readback.get(0).getStatus())
                    || !picUrl.equals(readback.get(0).getPicUrl())) {
                throw new IllegalStateException("到店照片写入失败");
            }
            return readback.get(0);
        }
        row.setPicUrl(picUrl);
        row.setReviewStatus(0);
        // ★不回写 status：库里那一行的 status 是「游戏答完没有」的真值(可能已是 1)，
        //   硬写 0 是对调用方说谎，而且会让读侧误以为游戏还能再答一次。
        return row;
    }

    /**
     * 可写入到店凭证的状态：0（已进店、游戏未答或无游戏）与 1（游戏已答）。
     *
     * <p>与 {@code PlayerNodeProgressMapper.updateShopDayPhoto} 的
     * {@code and status in (0, 1)} 是<b>同一个判据的两层表达</b>，必须一起改；
     * 只改一层就是 Java 放行、SQL 命中 0 行，或者反过来。
     */
    private static boolean isPhotoWritableStatus(Integer status) {
        return status != null && (status == 0 || status == 1);
    }

    private PlayerNodeProgress probe(Long memberId, Long activityId, Long nodeId) {
        PlayerNodeProgress p = new PlayerNodeProgress();
        p.setMemberId(memberId);
        p.setActivityId(activityId);
        p.setNodeId(nodeId);
        return p;
    }
}
