package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.domain.vo.CityNodeVO;
import com.chengyinhub.business.domain.vo.NearbyRunnerVO;
import com.chengyinhub.business.domain.vo.RunnerShopVO;
import com.chengyinhub.business.domain.vo.ShopVisitorAvatarVO;
import com.chengyinhub.business.domain.vo.ShopVisitorCountVO;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * P2 漫游 · roam Mapper(会话 + 迷雾格 + POI + POI 发现)。
 * 迷雾格与 POI 发现均靠唯一键做幂等,XP 只在 finish 结算一次。
 */
public interface RoamMapper {

    /** C-6 后台奖励调证：据点及奖励日志只读查询。 */
    List<Map<String,Object>> selectAdminRewardEvidence(@Param("poiId") Long poiId, @Param("memberId") Long memberId,
                                                        @Param("status") Integer status);

    // --- POI ---
    /** 经纬度包围盒内的上架 POI(粗筛,精确半径由前端/后端二次判距)。
     *  memberId 用于投影 found(该账号是否已发现过,roam_poi_found / roam_poi_reward_log);
     *  口径与 cityNodeContentApproved 一致 —— 未过审的商家投放不进玩家图层。 */
    List<RoamPoi> selectPoisNear(@Param("minLat") double minLat, @Param("maxLat") double maxLat,
                                 @Param("minLng") double minLng, @Param("maxLng") double maxLng,
                                 @Param("memberId") Long memberId);

    List<RoamPoi> selectPoisByIds(@Param("ids") List<Long> ids);

    /** 玩家侧按 id 查据点(同 selectPoisNear 的 status=1 + 审核口径),后台/官方活动请用 selectPoisByIds。 */
    List<RoamPoi> selectPlayerPoisByIds(@Param("ids") List<Long> ids);

    // --- 会话 ---
    RoamSession selectOwnedRecoverySession(@Param("memberId") Long memberId, @Param("sessionId") Long sessionId,
                                         @Param("clientSessionKey") String clientSessionKey);
    int saveFinishResult(@Param("sessionId") Long sessionId, @Param("memberId") Long memberId,
                         @Param("resultJson") String resultJson);
    int insertSession(RoamSession session);
    int insertKeyedSession(@Param("memberId") Long memberId, @Param("clientSessionKey") String clientSessionKey);
    RoamSession selectOwnedSessionByKeyForUpdate(@Param("memberId") Long memberId,
                                               @Param("clientSessionKey") String clientSessionKey);
    RoamSession selectSessionById(@Param("id") Long id);
    /** 会话写入与结算共同持有此行锁，必须在调用方事务内使用。 */
    RoamSession selectOwnedSessionForUpdate(@Param("id") Long id, @Param("memberId") Long memberId);
    /** CAS 结算写回:仅 status=0 可更新为 1,返回 1=本请求抢到结算权 */
    int updateSessionFinish(RoamSession session);
    /** 已结算 24–72h、未成功召回且仍可重试的会话。 */
    List<RoamSession> selectRecallCandidates(@Param("from") Date from, @Param("to") Date to,
                                              @Param("retryBefore") Date retryBefore,
                                              @Param("limit") Integer limit);
    /** 原子抢占一次发送尝试；并发任务只有一个返回 1。 */
    int claimRecallAttempt(@Param("id") Long id, @Param("attemptedAt") Date attemptedAt);
    /** 微信明确受理后才标记成功。 */
    int markRecallSent(@Param("id") Long id, @Param("sentAt") Date sentAt);

    // --- 迷雾格(INSERT IGNORE 幂等,返回新插入行数=真正新点亮的格子) ---
    int batchInsertTile(@Param("memberId") Long memberId, @Param("sessionId") Long sessionId,
                        @Param("tiles") List<String> tiles, @Param("revealedAt") Date revealedAt);
    /** 本会话新点亮格子数(prior session 已点的格子因 uk 不会带本 session_id) */
    int countTilesBySession(@Param("sessionId") Long sessionId);
    /** 本会话服务端确认的首次 POI 数。 */
    int countPoisBySession(@Param("sessionId") Long sessionId);
    /** 该 member 今日新点亮格子数(日限 200 用) */
    int countTilesTodayByMember(@Param("memberId") Long memberId);
    /** 该 member 累计点亮格子数(里程碑勋章用) */
    int countTilesEverByMember(@Param("memberId") Long memberId);
    /** 该 member 历史点亮格子(供小程序换设备/清缓存后恢复迷雾状态) */
    List<String> selectTilesByMember(@Param("memberId") Long memberId, @Param("limit") Integer limit);
    /** 该 member 全量迷雾记忆的增量页；按 id 正序推进，limit 由调用方多取一条判断 hasMore。 */
    List<Map<String, Object>> selectTilePageByMember(@Param("memberId") Long memberId,
                                                     @Param("afterId") Long afterId,
                                                     @Param("limit") Integer limit);

    // --- POI 发现(INSERT IGNORE 幂等,返回 1=新发现 0=已发现过) ---
    int insertPoiFoundIgnore(@Param("memberId") Long memberId, @Param("poiId") Long poiId,
                             @Param("sessionId") Long sessionId, @Param("foundAt") Date foundAt);

    // --- 会话内探店(任意真商家:roam_poi 据点 / cms_registration_merchant 报名商家)---
    /** INSERT IGNORE 幂等(uk: session_id+source_type+source_id),返回 1=新记 0=本会话已记过 */
    int insertShopVisitIgnore(@Param("memberId") Long memberId, @Param("sessionId") Long sessionId,
                              @Param("sourceType") int sourceType, @Param("sourceId") Long sourceId,
                              @Param("shopName") String shopName, @Param("visitedAt") Date visitedAt);

    /**
     * 「谁在这儿打过卡」的头像堆:按来源批量取,每个来源最多 limit 张,最近来的排前面。
     * 商家卡上那一行头像就靠它 —— 之前那格是空的,因为节点上根本没有这个事实。
     * 同一个人在同一家店去过多次只算一个人(先按 member 收敛再排队)。
     */
    List<ShopVisitorAvatarVO> selectShopVisitorAvatars(@Param("sourceType") Integer sourceType,
                                                       @Param("sourceIds") List<Long> sourceIds,
                                                       @Param("limit") int limit);

    /** 每个来源打过卡的**人数**(去重到人)。头像堆右边那枚 +N 用的是它,不是头像条数。 */
    List<ShopVisitorCountVO> selectShopVisitorCounts(@Param("sourceType") Integer sourceType,
                                                     @Param("sourceIds") List<Long> sourceIds);
    // --- 附近正在漫游的人(原型 otherRunners / otherSheet)---

    /** 心跳:把最近一次上报的位置写进这一场未结算的会话。member_id + status=0 双条件,写不进别人的。 */
    int updatePresence(@Param("id") Long id, @Param("memberId") Long memberId,
                       @Param("lat") java.math.BigDecimal lat, @Param("lng") java.math.BigDecimal lng,
                       @Param("reportAt") Date reportAt, @Param("explorePct") Integer explorePct);

    /** 半径内此刻还在走的人(不含自己)。freshAfter 之前没上报的一律算下线。 */
    List<NearbyRunnerVO> selectNearbyRunners(@Param("memberId") Long memberId,
                                             @Param("lat") Double lat, @Param("lng") Double lng,
                                             @Param("radiusM") Integer radiusM,
                                             @Param("freshAfter") Date freshAfter,
                                             @Param("now") Date now,
                                             @Param("limit") Integer limit);

    /** 这些人各自「这一趟」点亮的店(店名 + 封面),每人最多 perMember 张。 */
    List<RunnerShopVO> selectRunnerShops(@Param("memberIds") List<Long> memberIds,
                                         @Param("perMember") Integer perMember);

    /** 本会话探店数,按【店名】收敛 —— 与前端 utils/roam-poi-group.js 的去重口径一致:
     *  同一家店可能既是 roam 据点又是报名商家(两条 source),不去重会数成两家。 */
    int countDistinctShopsBySession(@Param("sessionId") Long sessionId);

    // --- 商家节点(漫游 · roam_poi type=2 + 商家 + 模板 联合)---
    /** 包围盒内上架商家节点(粗筛,精确半径由前端/后端二次判距) */
    List<CityNodeVO> selectMerchantNodesNear(@Param("minLat") double minLat, @Param("maxLat") double maxLat,
                                             @Param("minLng") double minLng, @Param("maxLng") double maxLng,
                                             @Param("originLat") double originLat, @Param("originLng") double originLng,
                                             @Param("keyword") String keyword, @Param("categoryId") Integer categoryId,
                                             @Param("tag") String tag, @Param("cityRole") String cityRole);
    /** 单个商家节点详情(type=2) */
    CityNodeVO selectMerchantNodeDetail(@Param("id") Long id);
    /** 玩家详情：公开内容，或本人已有打卡记录的下线内容；memberId=0 仅查公开。 */
    CityNodeVO selectPlayerMerchantNodeDetail(@Param("id") Long id, @Param("memberId") Long memberId);
    /** 待核销时承诺的券，不读后来修改的模板。 */
    Long selectPendingCityNodeCouponId(@Param("poiId") Long poiId, @Param("memberId") Long memberId);
    /** 玩家收藏的仍在架商家节点，按收藏时间倒序。 */
    List<CityNodeVO> selectFavoriteMerchantNodes(@Param("memberId") Long memberId);
    /** 该 member 在给定 poi 集合里已领过券(已完成)的 poi_id 列表(用于标记 completed) */
    List<Long> selectRewardedPoiIdsByMember(@Param("memberId") Long memberId, @Param("ids") List<Long> ids);

    /** C2:到店打卡记录(INSERT IGNORE,uk_poi_member;返回 1=首次到访 0=已到访)。有券节点传 status=0「待核销」(券由商家扫码 redeemCityNodeReward 发放);无券节点传 status=1「已完成」。 */
    int insertRewardLogIgnore(@Param("poiId") Long poiId, @Param("memberId") Long memberId,
                              @Param("couponId") Long couponId, @Param("lat") java.math.BigDecimal lat,
                              @Param("lng") java.math.BigDecimal lng, @Param("status") Integer status,
                              @Param("createTime") Date createTime);

    /** C2 扫码核销:商家扫玩家据点码时,把「待核销」记录 status 0→1(已核销/发券);条件更新保证幂等,返回 1=首次核销 0=未到访或已核销。 */
    int redeemCityNodeReward(@Param("poiId") Long poiId, @Param("memberId") Long memberId);

    /** R6:本会话服务端已校验到点的 POI(经 /poi/discover 记录);finish 只据此发 POI XP,不采信客户端 poiIds。 */
    List<RoamPoi> selectFoundPoisBySession(@Param("sessionId") Long sessionId);

    /** R6 反作弊:该 member 最近一次 POI 发现的时间(foundAt)+ 该 POI 坐标(lat/lng),供瞬移速度校验。 */
    java.util.Map<String, Object> selectLastPoiFoundLoc(@Param("memberId") Long memberId);

    // --- 商家自助据点(status 只表示可见性；audit_status 独立表示审核态)---
    /** 新建待审商户据点(type=2,status=0,merchant_id=NULL)。 */
    int insertPendingMerchantPoi(RoamPoi poi);
    int countPendingPlacements(@Param("applicantMerchantId") Long applicantMerchantId);
    /** 已生效 owner 才可读取；不得使用 applicant_merchant_id。 */
    RoamPoi selectMerchantPoiOwned(@Param("poiId") Long poiId, @Param("merchantId") Long merchantId);
    /** 可认领资产：在架、尚无 owner 且没有待审认领。 */
    RoamPoi selectPoiForClaim(@Param("poiId") Long poiId);
    List<RoamPoi> selectClaimablePois(@Param("keyword") String keyword, @Param("limit") Integer limit);
    /** 在目标 POI 原子写入待审认领；返回 0 表示已有待审或归属已变化。 */
    int submitClaimIfAvailable(@Param("poiId") Long poiId,
                               @Param("applicantMerchantId") Long applicantMerchantId);
    /** 申请人撤回自己的待审认领：清空申请字段让 POI 回到可认领；返回 0 表示已被审核处置或已撤回。 */
    int cancelPendingClaim(@Param("poiId") Long poiId,
                           @Param("applicantMerchantId") Long applicantMerchantId);
    RoamPoi selectRoamApplicationById(@Param("id") Long id);
    List<RoamPoi> selectRoamApplications(@Param("applicantMerchantId") Long applicantMerchantId,
                                         @Param("auditStatus") Integer auditStatus);
    int markRoamApplicationReviewing(@Param("id") Long id);
    /** 审核通过时把 applicant 原子提升为 effective owner；投放上架，认领保留原可见性。 */
    int approveRoamApplication(@Param("id") Long id, @Param("applicationType") Integer applicationType,
                               @Param("applicantMerchantId") Long applicantMerchantId);
    int rejectRoamApplicationIfPending(@Param("id") Long id, @Param("reason") String reason);
    /** 锁定商家行，串行化同一商家审核时的配额判定。 */
    Long lockMerchantForNodeReview(@Param("memberId") Long memberId);
    /** 更新自有据点(where id + merchant_id + type=2);applicant 不算 owner。 */
    int updateMerchantPoiOwned(RoamPoi poi);
    /** 我的据点列表(含所有状态,联合模板/商家)。 */
    List<CityNodeVO> selectMyNodes(@Param("merchantId") Long merchantId);
    /** 公开主页收藏入口：仅返回一个在架节点。 */
    CityNodeVO selectActiveMerchantNode(@Param("merchantId") Long merchantId);
    /** 店铺装修标签同步到该商家的全部搜索节点。 */
    int updateMerchantPoiTags(@Param("merchantId") Long merchantId, @Param("tags") String tags);
    /** 在架据点数(status=1),配额校验用。 */
    int countActiveNodesByMerchant(@Param("merchantId") Long merchantId);
    /** 上/下线自有据点(where id + merchant_id + type=2);applicant 不算 owner。 */
    int setNodeStatusOwned(@Param("poiId") Long poiId, @Param("merchantId") Long merchantId, @Param("status") Integer status);

    /** 后台 POI 治理列表(§4.4):⚠️ 刻意不硬编码 status —— 治理要看全量,status=1 是玩家口径(selectPoisNear) */
    List<RoamPoi> selectAdminPois(@Param("status") Integer status, @Param("cityCode") String cityCode,
                                  @Param("merchantId") Long merchantId);

    /** 后台上/下架(无 owner 限制；始终可下架，未批准申请不可绕过审核上架)。 */
    int updatePoiStatus(@Param("id") Long id, @Param("status") Integer status);
}
