package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Objects;

@Service
public class ShopDayProofServiceImpl implements IShopDayProofService {

    /** R16-ARRIVE:同地址无商家绑定时,两点间距 ≤ 该值才算同一地点。 */
    private static final double SAME_PLACE_METERS = 30d;

    @Autowired
    private PlayerNodeProgressMapper playerNodeProgressMapper;

    @Autowired
    private CmsTopicNodeMapper cmsTopicNodeMapper;

    @Autowired
    @Lazy
    private ITopicRouteRuntimeService routeRuntimeService;

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
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public PlayerNodeProgress markArrived(Long memberId, Long activityId, CmsTopicNode node) {
        if (node == null) throw new IllegalArgumentException("节点参数有误");
        CmsTopicNode storedNode = requireNode(node.getId());
        if (!Objects.equals(storedNode.getTopicId(), node.getTopicId())) {
            throw new IllegalArgumentException("节点不属于该主题");
        }
        PlayerNodeProgress scope = scope(memberId, activityId, storedNode, true);
        PlayerNodeProgress row = findProgress(scope);
        if (row == null) {
            scope.setStatus(0);
            scope.setCheckinTime(new Date());
            scope.setDelFlag(0);
            scope.setCreateTime(scope.getCheckinTime());
            row = insertArrival(scope);
        }
        if (row.getCheckinTime() != null) shareArrivalWithSiblings(scope, storedNode, row.getCheckinTime());
        return row;
    }

    /** The source and every sibling retain one server-selected instance and one transaction. */
    private void shareArrivalWithSiblings(PlayerNodeProgress scope, CmsTopicNode node, Date checkinTime) {
        if (node.getChapterId() == null) return;
        CmsTopicNode siblingProbe = new CmsTopicNode();
        siblingProbe.setTopicId(node.getTopicId());
        siblingProbe.setChapterId(node.getChapterId());
        List<CmsTopicNode> siblings = cmsTopicNodeMapper.selectCmsTopicNodeList(siblingProbe);
        if (siblings == null || siblings.isEmpty()) return;
        for (CmsTopicNode sibling : siblings) {
            if (sibling == null || sibling.getId() == null || sibling.getId().equals(node.getId())) continue;
            if (!Objects.equals(node.getTopicId(), sibling.getTopicId())
                    || !Objects.equals(node.getChapterId(), sibling.getChapterId())
                    || !samePlace(node, sibling)) continue;
            PlayerNodeProgress row = probe(scope.getMemberId(), scope.getActivityId(),
                    scope.getTopicId(), sibling.getId(), scope.getPlayInstanceId());
            if (hasSiblingProgressIncludingDeleted(row)) continue;
            row.setStatus(0);
            row.setCheckinTime(checkinTime);
            row.setDelFlag(0);
            row.setCreateTime(checkinTime);
            try {
                insertArrival(row);
            } catch (DuplicateKeyException duplicate) {
                // A concurrently created/deleted exact sibling row is still evidence to preserve.
                if (!hasSiblingProgressIncludingDeleted(row)) throw duplicate;
            }
        }
    }

    private boolean hasSiblingProgressIncludingDeleted(PlayerNodeProgress scope) {
        int count = playerNodeProgressMapper.countPlayerNodeProgressForInstanceIncludingDeleted(
                scope.getMemberId(), scope.getActivityId(), scope.getTopicId(), scope.getNodeId(), scope.getPlayInstanceId());
        if (count < 0 || count > 1) throw new IllegalStateException("到店记录不唯一");
        return count == 1;
    }

    private PlayerNodeProgress insertArrival(PlayerNodeProgress row) {
        try {
            if (playerNodeProgressMapper.insertPlayerNodeProgress(row) != 1) {
                throw new IllegalStateException("到店记录写入失败");
            }
            return row;
        } catch (DuplicateKeyException duplicate) {
            // Only a readable row of this exact owner/node/instance can satisfy a raced insert.
            // Unrelated constraints, hidden rows and sibling failures must abort the whole transaction.
            PlayerNodeProgress raced = findProgress(row);
            if (raced == null) throw duplicate;
            return raced;
        }
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
        double distance = haversineMeters(lat1, lng1, lat2, lng2);
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

    /** 两坐标球面距离(米,haversine)。 */
    private static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000d;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return r * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public boolean hasArrived(Long memberId, Long activityId, Long nodeId) {
        PlayerNodeProgress scope = scope(memberId, activityId, requireNode(nodeId), false);
        PlayerNodeProgress row = findProgress(scope);
        // Recheck after the uncached progress read before returning an arrival fact.
        if (!scope.getPlayInstanceId().equals(routeRuntimeService.playInstance(
                memberId, scope.getTopicId(), scope.getActivityId()))) {
            throw new ServiceException("本局已更新，请刷新", 409);
        }
        return row != null && row.getCheckinTime() != null;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public boolean markGameDone(Long memberId, Long activityId, Long topicId, Long nodeId) {
        CmsTopicNode node = requireNode(nodeId);
        if (!Objects.equals(topicId, node.getTopicId())) throw new IllegalArgumentException("节点不属于该主题");
        PlayerNodeProgress scope = scope(memberId, activityId, node, true);
        return playerNodeProgressMapper.markShopDayGameDoneForInstance(memberId, scope.getActivityId(),
                node.getTopicId(), nodeId, scope.getPlayInstanceId()) > 0;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public PlayerNodeProgress submitPhoto(Long memberId, Long activityId, Long nodeId, String picUrl) {
        if (StringUtils.isBlank(picUrl)) throw new IllegalArgumentException("到店照片不能为空");
        PlayerNodeProgress scope = scope(memberId, activityId, requireNode(nodeId), true);
        PlayerNodeProgress row = findProgress(scope);
        if (row == null || row.getCheckinTime() == null) {
            throw new IllegalStateException("请先扫码确认到店");
        }
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
        if (playerNodeProgressMapper.updateShopDayPhotoForInstance(row.getId(), memberId, scope.getActivityId(),
                scope.getTopicId(), nodeId, scope.getPlayInstanceId(), picUrl) != 1) {
            PlayerNodeProgress readback = findProgress(scope);
            if (readback == null || readback.getCheckinTime() == null || !isPhotoWritableStatus(readback.getStatus())
                    || !picUrl.equals(readback.getPicUrl())) {
                throw new IllegalStateException("到店照片写入失败");
            }
            return readback;
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
     * <p>与 {@code PlayerNodeProgressMapper.updateShopDayPhotoForInstance} 的
     * {@code and status in (0, 1)} 是<b>同一个判据的两层表达</b>，必须一起改；
     * 只改一层就是 Java 放行、SQL 命中 0 行，或者反过来。
     */
    private static boolean isPhotoWritableStatus(Integer status) {
        return status != null && (status == 0 || status == 1);
    }

    private CmsTopicNode requireNode(Long nodeId) {
        if (nodeId == null || nodeId <= 0) throw new IllegalArgumentException("节点参数有误");
        CmsTopicNode node = cmsTopicNodeMapper.selectCmsTopicNodeById(nodeId);
        if (node == null || !nodeId.equals(node.getId()) || node.getTopicId() == null || node.getTopicId() <= 0) {
            throw new IllegalArgumentException("节点不存在或不属于有效主题");
        }
        return node;
    }

    private PlayerNodeProgress scope(Long memberId, Long activityId, CmsTopicNode node, boolean write) {
        Long activity = activityId == null ? 0L : activityId;
        if (memberId == null || memberId <= 0 || activity < 0) throw new IllegalArgumentException("场次参数有误");
        if (routeRuntimeService == null) throw new ServiceException("路线运行时不可用");
        String instance = routeRuntimeService.playInstance(memberId, node.getTopicId(), activity);
        if (StringUtils.isBlank(instance)) throw new ServiceException("本局不可读取，请刷新", 409);
        if (write) {
            // The route row lock stays held until this service transaction commits or rolls back.
            boolean theme = routeRuntimeService.assertCurrentPlayInstance(memberId, node.getTopicId(), activity, instance);
            if (!"default".equals(instance) && !theme) throw new ServiceException("本局不可读取，请刷新", 409);
        }
        return probe(memberId, activity, node.getTopicId(), node.getId(), instance);
    }

    private PlayerNodeProgress findProgress(PlayerNodeProgress scope) {
        List<PlayerNodeProgress> rows = playerNodeProgressMapper.selectPlayerNodeProgressListForInstance(
                scope, scope.getPlayInstanceId());
        if (rows == null) throw new IllegalStateException("到店记录不可读取");
        if (rows.isEmpty()) return null;
        PlayerNodeProgress row = rows.get(0);
        if (rows.size() != 1 || row == null || !Objects.equals(scope.getMemberId(), row.getMemberId())
                || !Objects.equals(scope.getActivityId(), row.getActivityId())
                || !Objects.equals(scope.getTopicId(), row.getTopicId())
                || !Objects.equals(scope.getNodeId(), row.getNodeId())
                || !Objects.equals(scope.getPlayInstanceId(), row.getPlayInstanceId())
                || !Integer.valueOf(0).equals(row.getDelFlag())) {
            throw new IllegalStateException("到店记录归属不一致");
        }
        return row;
    }

    private PlayerNodeProgress probe(Long memberId, Long activityId, Long topicId, Long nodeId, String instance) {
        PlayerNodeProgress p = new PlayerNodeProgress();
        p.setMemberId(memberId);
        p.setActivityId(activityId);
        p.setTopicId(topicId);
        p.setNodeId(nodeId);
        p.setPlayInstanceId(instance);
        return p;
    }
}
