package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ContentInteractionGuard;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.CommunityPostRequests;
import com.chengyinhub.business.domain.vo.ActivityInfoVO;
import com.chengyinhub.business.service.IUmsCreativeSquareLikeService;
import com.chengyinhub.business.service.IUmsCreativeSquareService;
import com.chengyinhub.business.service.IUmsMemberFollowService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IViewCreativeSquareService;
import com.chengyinhub.business.service.ICreativeSquareCommunityPolicyService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.ICommunityPostService;
import com.chengyinhub.business.service.support.TopicRouteModePolicy;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.support.TopicCompletionRulePolicy;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.constant.ProductType;
import com.github.pagehelper.PageHelper;
import com.chengyinhub.common.config.WxPayConfig;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.ServletUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.bean.BeanUtils;
import com.chengyinhub.common.utils.http.HttpUtils;
import com.chengyinhub.common.utils.JsonUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.chengyinhub.common.utils.OwnershipUtils;
/**
 * 创意广场
 *
 * @author webxh
 * @date 2025-08-28
 */
@RestController
@Tag(name = "创意广场")
@RequestMapping("/api/creativesquare")
public class ApiCreativeSquareController extends BaseController {
    @Value("${community.post.legacy-writes-enabled:true}")
    private boolean legacyCommunityWritesEnabled;

    private AjaxResult legacyWriteDisabled() {
        return AjaxResult.error(HttpStatus.UPGRADE_REQUIRED, "广场发布已升级，请更新客户端后重试");
    }
    private final TopicCompletionRulePolicy topicCompletionRulePolicy = new TopicCompletionRulePolicy();
    @Autowired
    private IUmsCreativeSquareService umsCreativeSquareService;

    /** [P0-17] ③ 报名单批量探针(口径单一真源在 service 层)。 */
    @Autowired
    private com.chengyinhub.business.service.ICmsRegistrationService cmsRegistrationService;

    /** [P0-17] ③ 通关口径:认核销不认打卡。 */
    @Autowired
    private com.chengyinhub.business.service.IPerkLedgerService perkLedgerService;

    @Autowired
    private IAuditTaskService auditTaskService;

    @Autowired
    private IViewCreativeSquareService viewCreativeSquareService;

    @Autowired
    private ICommunityPostService communityPostService;

    @Autowired
    private IUmsCreativeSquareLikeService umsCreativeSquareLikeService;

    @Autowired
    private ContentInteractionGuard contentInteractionGuard;

    @Autowired
    private IUmsMemberFollowService umsMemberFollowService;

    @Autowired
    private ICreativeSquareCommunityPolicyService creativeSquareCommunityPolicyService;

    @Autowired
    private IWxContentSecurityService wxContentSecurityService;

    /** R10-03 幂等发布审核单飞锁(仅并发重试窗口 30s,不长期占锁)。 */
    @Autowired
    private com.chengyinhub.common.core.redis.RedisCache redisCache;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private ICmsActivityService cmsActivityService;

    @Autowired
    private ICmsTopicService cmsTopicService;

    @Autowired(required = false)
    private ITopicRouteRuntimeService topicRouteRuntimeService;

    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;

    @Autowired
    private IPlayerNodeProgressService playerNodeProgressService;

    @Autowired(required = false)
    private com.chengyinhub.business.service.IPlayerProgressionService playerProgressionService;

    /**
     * 运动 feed 和个人态读模型批量补全:查询次数只随批量种类变化,不随列表行数变化。
     */
    private void enrichCreativeSquareList(List<ViewCreativeSquare> list, Long memberId) {
        if (list == null || list.isEmpty()) return;
        stripForeignLocation(list, memberId);
        enrichPersonalStateBatch(list, memberId);
        enrichSportFeedBatch(list);
        enrichGrowthLevels(list);
    }

    /**
     * O-08:旧广场读面只把精确位置(地址/经纬度)给作者本人。社区帖文平台对他人只投影城市,
     * 且作者撤回位置、改私密不会回写 ums_creative_square —— 旧 list/info 若照旧下发,撤回过的坐标仍能被任何人读到。
     */
    private void stripForeignLocation(List<ViewCreativeSquare> list, Long viewerMemberId) {
        for (ViewCreativeSquare square : list) {
            if (square == null) continue;
            if (viewerMemberId != null && viewerMemberId > 0 && viewerMemberId.equals(square.getMemberId())) continue;
            square.setAddress(null);
            square.setLongitude(null);
            square.setLatitude(null);
        }
    }

    /**
     * RUN-13(拍板 2026-09-19 第4条):等级单一真源 = player_growth。
     * 视图列 member_level_id 仍 join 旧 ums_member.level_id(视图不动,避免生产 DDL),
     * 下发前用成长等级批量覆盖 —— 一次 IN 查询,不随行数变化;无行兜底 Lv.1。
     */
    private void enrichGrowthLevels(List<ViewCreativeSquare> list) {
        if (playerProgressionService == null) return;
        List<Long> authorIds = uniqueAuthorIds(list);
        if (authorIds.isEmpty()) return;
        Map<Long, Integer> levels = playerProgressionService.levelsByMemberIds(authorIds);
        for (ViewCreativeSquare square : list) {
            if (square != null && square.getMemberId() != null) {
                Integer lv = levels.get(square.getMemberId());
                square.setMemberLevelId((long) (lv == null ? 1 : lv));
            }
        }
    }

    private void enrichPersonalStateBatch(List<ViewCreativeSquare> list, Long memberId) {
        for (ViewCreativeSquare square : list) {
            if (square != null) {
                square.setIsLiked(0);
                square.setIsBookmarked(0);
                square.setIsFollowTheUser(0);
            }
        }
        if (memberId == null || memberId <= 0) return;

        List<Long> squareIds = uniqueSquareIds(list);
        if (!squareIds.isEmpty()) {
            List<UmsCreativeSquareLike> likes =
                    viewCreativeSquareService.selectCreativeSquareLikesByMemberAndDataIds(memberId, squareIds);
            Map<Long, Integer> likeBySquare = new HashMap<Long, Integer>();
            if (likes != null) {
                for (UmsCreativeSquareLike like : likes) {
                    if (like != null && like.getDataId() != null && like.getLikeType() != null
                            && !likeBySquare.containsKey(like.getDataId())) {
                        likeBySquare.put(like.getDataId(), like.getLikeType());
                    }
                }
            }
            for (ViewCreativeSquare square : list) {
                if (square != null && likeBySquare.containsKey(square.getId())) {
                    square.setIsLiked(likeBySquare.get(square.getId()));
                }
            }
            List<Long> bookmarkedIds = viewCreativeSquareService.selectCommunityBookmarkedPostIds(memberId, squareIds);
            Set<Long> bookmarked = bookmarkedIds == null
                    ? new LinkedHashSet<Long>() : new LinkedHashSet<Long>(bookmarkedIds);
            for (ViewCreativeSquare square : list) {
                if (square != null && bookmarked.contains(square.getId())) square.setIsBookmarked(1);
            }
        }

        List<Long> authorIds = uniqueAuthorIds(list);
        if (!authorIds.isEmpty()) {
            List<UmsMemberFollow> follows =
                    viewCreativeSquareService.selectMemberFollowsByMemberAndFollowIds(memberId, authorIds);
            Set<Long> followed = new LinkedHashSet<Long>();
            if (follows != null) {
                for (UmsMemberFollow follow : follows) {
                    if (follow != null && follow.getFollowMemberId() != null) {
                        followed.add(follow.getFollowMemberId());
                    }
                }
            }
            for (ViewCreativeSquare square : list) {
                if (square != null && followed.contains(square.getMemberId())) {
                    square.setIsFollowTheUser(1);
                }
            }
        }
    }

    private void enrichSportFeedBatch(List<ViewCreativeSquare> list) {
        List<ViewCreativeSquare> sportRows = sportRows(list);
        if (sportRows.isEmpty()) return;

        applyRoutePreviews(sportRows);

        Map<Long, Map<String, Object>> activities =
                indexRows(viewCreativeSquareService.selectSportActivitySummaries(uniqueDataIds(sportRows, 1L)), "activityId");
        Map<Long, Map<String, Object>> topics =
                indexRows(viewCreativeSquareService.selectSportTopicSummaries(uniqueDataIds(sportRows, 2L)), "topicId");

        List<Long> topicIds = new ArrayList<Long>();
        List<Map<String, Object>> activityPairs = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> topicPairs = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> explorePairs = new ArrayList<Map<String, Object>>();
        List<TopicRouteSession> routeScopes = new ArrayList<TopicRouteSession>();

        for (ViewCreativeSquare square : sportRows) {
            Map<String, Object> summary = square.getDataType() == 1L
                    ? activities.get(square.getDataId()) : topics.get(square.getDataId());
            if (summary == null) continue;
            Long topicId = square.getDataType() == 1L ? toLong(summary.get("topicId")) : square.getDataId();
            if (topicId == null) continue;

            square.setSportTopicId(topicId);
            square.setSportName((String) summary.get("sportName"));
            square.setSportCover((String) summary.get("sportCover"));
            square.setIsTopicTemplate(square.getDataType() == 2L
                    && Integer.valueOf(1).equals(toInteger(summary.get("isTemplate"))));
            addUnique(topicIds, topicId);
            TopicRouteSession routeScope = new TopicRouteSession();
            routeScope.setMemberId(square.getMemberId());
            routeScope.setTopicId(topicId);
            routeScope.setActivityId(square.getDataType() == 1L ? square.getDataId() : 0L);
            routeScopes.add(routeScope);

            if (square.getDataType() == 1L) {
                activityPairs.add(pair("memberId", square.getMemberId(), "activityId", square.getDataId()));
                if (Integer.valueOf(ProductType.FREE_EXPLORE).equals(toInteger(summary.get("productType")))) {
                    explorePairs.add(pair("memberId", square.getMemberId(), "activityId", square.getDataId()));
                }
            } else {
                topicPairs.add(pair("memberId", square.getMemberId(), "topicId", topicId));
            }
        }

        Map<Long, Integer> totalByTopic = countByLongKey(
                viewCreativeSquareService.selectNodeCountsByTopicIds(topicIds), "topicId", "cnt");
        Map<String, Integer> doneByActivity = countByPair(
                viewCreativeSquareService.selectProgressCountsByMemberActivities(activityPairs), "memberId", "activityId", "cnt");
        Map<String, Integer> doneByTopic = countByPair(
                viewCreativeSquareService.selectProgressCountsByMemberTopics(topicPairs), "memberId", "topicId", "cnt");
        // 奖牌:每页固定 2 条批量查询(会话口径 + 主题口径),不随行数增长。
        // ⚠️ 必须兜底:jar 可能先于 migration_20260902_node_medal_claim.sql 上线(表还不存在),
        //    查询会抛 BadSqlGrammarException。enrichCreativeSquareList 同时服务 /list 与 /info ⇒
        //    不兜的话挂掉的不是「节点奖牌」,是【整个广场列表 + 帖子详情】。锦上添花的功能没有这个权力。
        //    查不到就当 0 枚。日志打在这里:每次请求最多 1 行,不进下面的逐帖循环。
        //    切 DataAccessException 而不是 Exception:MyBatis 的建表缺失 / 超时都在这条线下
        //    (mybatis-spring 会把 PersistenceException 翻成 MyBatisSystemException),
        //    而本方法自己的 NPE 之类编程错仍然穿透,不被静默吃掉。
        List<Map<String, Object>> medalActivityRows = java.util.Collections.emptyList();
        List<Map<String, Object>> medalTopicRows = java.util.Collections.emptyList();
        try {
            medalActivityRows = viewCreativeSquareService.selectMedalCountsByMemberActivities(activityPairs);
            medalTopicRows = viewCreativeSquareService.selectMedalCountsByMemberTopics(topicPairs);
        } catch (org.springframework.dao.DataAccessException e) {
            logger.warn("medal enrich degraded to zero, sportRows={}: {}", sportRows.size(), e.getMessage());
        }
        Map<String, Integer> goldByActivity = countByPair(medalActivityRows, "memberId", "activityId", "gold");
        Map<String, Integer> silverByActivity = countByPair(medalActivityRows, "memberId", "activityId", "silver");
        Map<String, Integer> bronzeByActivity = countByPair(medalActivityRows, "memberId", "activityId", "bronze");
        Map<String, Integer> goldByTopic = countByPair(medalTopicRows, "memberId", "topicId", "gold");
        Map<String, Integer> silverByTopic = countByPair(medalTopicRows, "memberId", "topicId", "silver");
        Map<String, Integer> bronzeByTopic = countByPair(medalTopicRows, "memberId", "topicId", "bronze");
        Map<String, Map<String, Object>> exploreByMemberActivity = indexRowsByPair(
                viewCreativeSquareService.selectExplorePassesByMemberActivities(explorePairs), "memberId", "activityId");

        List<Long> registrationIds = new ArrayList<Long>();
        for (Map<String, Object> row : exploreByMemberActivity.values()) {
            addUnique(registrationIds, toLong(row.get("registrationId")));
        }
        // topicId from registration may be NULL (③ registrations don't set it);
        // use sportTopicId from activity summary instead (already set on squares)
        List<Long> exploreTopicIds = new ArrayList<Long>();
        for (ViewCreativeSquare square : sportRows) {
            if (square.getDataType() == 1L && square.getSportTopicId() != null
                    && exploreByMemberActivity.containsKey(pairKey(square.getMemberId(), square.getDataId()))) {
                addUnique(exploreTopicIds, square.getSportTopicId());
            }
        }
        Map<Long, Integer> requiredByTopic = countByLongKey(
                viewCreativeSquareService.selectRequiredChapterCountsByTopicIds(exploreTopicIds), "topicId", "requiredCount");
        Map<String, Integer> redeemedByRegistrationTopic = countByPair(
                viewCreativeSquareService.selectRedeemedRequiredChapterCountsByRegistrations(registrationIds),
                "registrationId", "topicId", "redeemedCount");
        Map<String, Integer> routeStatuses = topicRouteRuntimeService == null
                ? java.util.Collections.<String, Integer>emptyMap()
                : topicRouteRuntimeService.sessionStatuses(routeScopes);

        for (ViewCreativeSquare square : sportRows) {
            Long topicId = square.getSportTopicId();
            if (topicId == null) continue;
            int total = valueOrZero(totalByTopic, topicId);
            int done = square.getDataType() == 1L
                    ? valueOrZero(doneByActivity, pairKey(square.getMemberId(), square.getDataId()))
                    : valueOrZero(doneByTopic, pairKey(square.getMemberId(), topicId));
            Map<String, Object> summary = square.getDataType() == 1L
                    ? activities.get(square.getDataId()) : topics.get(square.getDataId());
            boolean completed = topicCompletionRulePolicy.isCompleted(
                    summary == null ? null : (String) summary.get("completeRuleJson"), total, done);

            if (square.getDataType() == 1L) {
                Map<String, Object> explore = exploreByMemberActivity.get(pairKey(square.getMemberId(), square.getDataId()));
                if (explore != null) {
                    Long registrationId = toLong(explore.get("registrationId"));
                    int required = valueOrZero(requiredByTopic, topicId);
                    int redeemed = valueOrZero(redeemedByRegistrationTopic, pairKey(registrationId, topicId));
                    completed = required > 0 && redeemed >= required;
                }
            }
            Long routeActivityId = square.getDataType() == 1L ? square.getDataId() : 0L;
            Integer routeStatus = routeStatuses.get(ITopicRouteRuntimeService.sessionKey(
                    square.getMemberId(), topicId, routeActivityId));
            if (TopicRouteModePolicy.isPublishedBranchGraph(
                    summary == null ? null : (String) summary.get("publishedRouteGraphJson"))
                    || routeStatus != null) {
                completed = Integer.valueOf(TopicRouteSession.STATUS_COMPLETED).equals(routeStatus);
            }

            square.setNodeTotal(total);
            square.setNodeDoneCount(done);
            // 口径:活动帖按 (member, activity_id),自玩帖按 (member, topic_id) 且 activity_id = 0。
            // ⚠️ pairKey 拼法一致【不等于】SQL 范围一致 —— 会话边界在 selectMedalCountsByMemberTopics
            //    的 `activity_id = 0` 上,不在这里的 key 上。改 key 之前先去看那条 SQL。
            boolean byActivity = square.getDataType() == 1L;
            String medalKey = byActivity
                    ? pairKey(square.getMemberId(), square.getDataId())
                    : pairKey(square.getMemberId(), topicId);
            square.setMedalGold(valueOrZero(byActivity ? goldByActivity : goldByTopic, medalKey));
            square.setMedalSilver(valueOrZero(byActivity ? silverByActivity : silverByTopic, medalKey));
            square.setMedalBronze(valueOrZero(byActivity ? bronzeByActivity : bronzeByTopic, medalKey));
            square.setCompleted(completed);
        }
    }

    private List<ViewCreativeSquare> sportRows(List<ViewCreativeSquare> list) {
        List<ViewCreativeSquare> rows = new ArrayList<ViewCreativeSquare>();
        for (ViewCreativeSquare square : list) {
            if (square == null || square.getDataType() == null || square.getDataId() == null
                    || square.getDataId() <= 0 || square.getMemberId() == null) {
                continue;
            }
            if (square.getDataType() == 1L || square.getDataType() == 2L) {
                rows.add(square);
            }
        }
        return rows;
    }

    private void applyRoutePreviews(List<ViewCreativeSquare> sportRows) {
        List<UmsCreativeSquare> bases = viewCreativeSquareService.selectCreativeSquareBatchByIds(uniqueSquareIds(sportRows));
        if (bases == null || bases.isEmpty()) return;
        Map<Long, String> previewById = new HashMap<Long, String>();
        for (UmsCreativeSquare base : bases) {
            if (base != null && base.getId() != null) {
                previewById.put(base.getId(), base.getRoutePreviewImg());
            }
        }
        for (ViewCreativeSquare square : sportRows) {
            if (previewById.containsKey(square.getId())) {
                square.setRoutePreviewImg(previewById.get(square.getId()));
            }
        }
    }

    private List<Long> uniqueSquareIds(List<ViewCreativeSquare> list) {
        List<Long> ids = new ArrayList<Long>();
        for (ViewCreativeSquare square : list) {
            if (square != null) addUnique(ids, square.getId());
        }
        return ids;
    }

    private List<Long> uniqueAuthorIds(List<ViewCreativeSquare> list) {
        List<Long> ids = new ArrayList<Long>();
        for (ViewCreativeSquare square : list) {
            if (square != null) addUnique(ids, square.getMemberId());
        }
        return ids;
    }

    private List<Long> uniqueDataIds(List<ViewCreativeSquare> list, Long dataType) {
        List<Long> ids = new ArrayList<Long>();
        for (ViewCreativeSquare square : list) {
            if (square != null && dataType.equals(square.getDataType())) addUnique(ids, square.getDataId());
        }
        return ids;
    }

    private void addUnique(List<Long> ids, Long id) {
        if (id != null && id > 0 && !ids.contains(id)) ids.add(id);
    }

    private Map<String, Object> pair(String firstKey, Long first, String secondKey, Long second) {
        Map<String, Object> pair = new HashMap<String, Object>();
        pair.put(firstKey, first);
        pair.put(secondKey, second);
        return pair;
    }

    private Map<Long, Map<String, Object>> indexRows(List<Map<String, Object>> rows, String key) {
        Map<Long, Map<String, Object>> map = new HashMap<Long, Map<String, Object>>();
        if (rows == null) return map;
        for (Map<String, Object> row : rows) {
            Long id = toLong(rowValue(row, key));
            if (id != null && !map.containsKey(id)) map.put(id, row);
        }
        return map;
    }

    private Map<String, Map<String, Object>> indexRowsByPair(List<Map<String, Object>> rows, String firstKey, String secondKey) {
        Map<String, Map<String, Object>> map = new HashMap<String, Map<String, Object>>();
        if (rows == null) return map;
        for (Map<String, Object> row : rows) {
            Long first = toLong(rowValue(row, firstKey));
            Long second = toLong(rowValue(row, secondKey));
            String key = pairKey(first, second);
            if (key != null && !map.containsKey(key)) map.put(key, row);
        }
        return map;
    }

    private Map<Long, Integer> countByLongKey(List<Map<String, Object>> rows, String key, String countKey) {
        Map<Long, Integer> map = new HashMap<Long, Integer>();
        if (rows == null) return map;
        for (Map<String, Object> row : rows) {
            Long id = toLong(rowValue(row, key));
            if (id != null) map.put(id, toInt(rowValue(row, countKey)));
        }
        return map;
    }

    private Map<String, Integer> countByPair(List<Map<String, Object>> rows, String firstKey, String secondKey, String countKey) {
        Map<String, Integer> map = new HashMap<String, Integer>();
        if (rows == null) return map;
        for (Map<String, Object> row : rows) {
            String key = pairKey(toLong(rowValue(row, firstKey)), toLong(rowValue(row, secondKey)));
            if (key != null) map.put(key, toInt(rowValue(row, countKey)));
        }
        return map;
    }

    private String pairKey(Long first, Long second) {
        return first == null || second == null ? null : first + ":" + second;
    }

    private int valueOrZero(Map<?, Integer> map, Object key) {
        Integer value = map.get(key);
        return value == null ? 0 : value;
    }

    private Object rowValue(Map<String, Object> row, String key) {
        if (row == null || key == null) return null;
        if (row.containsKey(key)) return row.get(key);
        String lower = key.toLowerCase();
        if (row.containsKey(lower)) return row.get(lower);
        String upper = key.toUpperCase();
        if (row.containsKey(upper)) return row.get(upper);
        return null;
    }

    private Long toLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return null;
        return Convert.toLong(String.valueOf(value), null);
    }

    private Integer toInteger(Object value) {
        return value == null ? null : Integer.valueOf(toInt(value));
    }

    private int toInt(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null) return 0;
        return Convert.toInt(String.valueOf(value), 0);
    }

    /** 分页钳制:上限 100 —— 创意广场读模型批量补查询次数随批量种类不随行数,但上限仍应合理。 */
    static int clampPageSize(int raw) {
        if (raw < 1) return 10;
        return Math.min(raw, 100);
    }

    @Operation(summary = "添加/编辑创意广场文章")
    @PostMapping("/action")
    @Parameters({
            @Parameter(name = "id", description = "创意ID，填写了ID是编辑，不填写是新增"),
            @Parameter(name = "contents", description = "发布内容"),
            @Parameter(name = "pics", description = "发布的图片，英文分号隔开"),
            @Parameter(name = "file_url", description = "上传的文件下载地址，英文分号隔开"),
            @Parameter(name = "address", description = "地址"),
            @Parameter(name = "longitude", description = "地址经度"),
            @Parameter(name = "latitude", description = "地址纬度"),
            @Parameter(name = "is_crowdfunding", description = "是否参与众筹投票  1参与  2不参与"),
            @Parameter(name = "data_id", description = "关联的活动ID/主题ID/漫游会话ID"),
            @Parameter(name = "data_type", description = "0直接发布 1活动发布 2主题发布 3自由漫游成绩"),
            @Parameter(name = "route_preview_img", description = "路线静态预览图URL(发布时<map>快照上传OSS)"),
            @Parameter(name = "request_id", description = "R10-03 稳定发布意图键(16-64位,账号+草稿隔离;重试复用同键)")
    })
    public AjaxResult creativeSquareActoin(String id, String contents, String pics, String file_url, String address, String longitude, String latitude, String is_crowdfunding, String data_id, String data_type, String route_preview_img, String request_id) {

        UmsCreativeSquare umsCreativeSquare = new UmsCreativeSquare();
        Long member_id = getAppUserId();
        // 参数校验
        if (member_id == null || member_id <= 0) {
            return error("请先登录");
        }
        if (!legacyCommunityWritesEnabled) return legacyWriteDisabled();

        umsCreativeSquare.setMemberId(member_id);
        boolean isEdit = Convert.toLong(id, 0L) > 0;
        UmsCreativeSquare existing = null;
        if (isEdit) {
            //编辑
            umsCreativeSquare.setId(Convert.toLong(id));
            existing = umsCreativeSquareService.selectUmsCreativeSquareById(umsCreativeSquare.getId());
            if (existing == null) {
                return error("内容不存在");
            }
            if (OwnershipUtils.notOwner(existing.getMemberId(), member_id)) {
                return error("无权编辑该内容");
            }
        } else {
            //添加
            umsCreativeSquare.setId(0L);
            //删除标记 0 代表存在  2删除
            umsCreativeSquare.setDelFlag(0);
            //状态 1显示 2隐藏 0待审;先落待审,由统一安全门禁裁决
            umsCreativeSquare.setStatus(0);
        }
        if (StringUtils.isNotEmpty(contents)) {
            umsCreativeSquare.setContents(contents);
        } else {
            return error("请输入发布内容");
        }
        // R10-06:编辑态区分「未提交」与「显式清空」——参数非 null 即用户明确提交,空串=真清空;
        // 参数缺失(null)= 不提交,mapper 只写非 null 字段,原值保留。发布态维持非空才写。
        if (isEdit) {
            if (pics != null) {
                umsCreativeSquare.setPics(pics);
            }
            if (file_url != null) {
                umsCreativeSquare.setFileUrl(file_url);
            }
            if (address != null) {
                umsCreativeSquare.setAddress(address);
            }
            if (longitude != null) {
                umsCreativeSquare.setLongitude(longitude);
            }
            if (latitude != null) {
                umsCreativeSquare.setLatitude(latitude);
            }
        } else {
            if (StringUtils.isNotEmpty(pics)) {
                umsCreativeSquare.setPics(pics);
            }
            if (StringUtils.isNotEmpty(file_url)) {
                umsCreativeSquare.setFileUrl(file_url);
            }
            if (StringUtils.isNotEmpty(address)) {
                umsCreativeSquare.setAddress(address);
            }
            if (StringUtils.isNotEmpty(longitude)) {
                umsCreativeSquare.setLongitude(longitude);
            }
            if (StringUtils.isNotEmpty(latitude)) {
                umsCreativeSquare.setLatitude(latitude);
            }
        }
        // 关联运动feed:data_id=活动/主题/漫游会话ID, data_type=0直发/1活动/2主题/3自由漫游成绩
        Long dataIdVal = Convert.toLong(data_id, 0L);
        Long dataTypeVal = dataIdVal > 0 ? Convert.toLong(data_type, 1L) : 0L;
        AjaxResult attachDenied = rejectForeignActivity(member_id, dataIdVal, dataTypeVal);
        if (attachDenied != null) {
            return attachDenied;
        }
        if (dataIdVal > 0) {
            umsCreativeSquare.setDataId(dataIdVal);
            umsCreativeSquare.setDataType(dataTypeVal);
        } else {
            // 未关联或清除关联时落直发,避免编辑残留旧关联
            umsCreativeSquare.setDataId(0L);
            umsCreativeSquare.setDataType(0L);
        }
        // 路线静态预览图(发布时前端快照上传OSS后回传);编辑态非 null 即可清空
        if (isEdit) {
            if (route_preview_img != null) {
                umsCreativeSquare.setRoutePreviewImg(route_preview_img);
            }
        } else if (StringUtils.isNotEmpty(route_preview_img)) {
            umsCreativeSquare.setRoutePreviewImg(route_preview_img);
        }
        //是否参与众筹投票  1参与  2不参与;编辑态未提交(null)= 不碰原值(未提交字段不清空)
        if (isEdit) {
            if (is_crowdfunding != null) {
                umsCreativeSquare.setIsCrowdfunding(Convert.toInt(is_crowdfunding, 2));
            }
        } else if (StringUtils.isNotEmpty(is_crowdfunding)) {
            umsCreativeSquare.setIsCrowdfunding(Convert.toInt(is_crowdfunding, 2));
        } else {
            umsCreativeSquare.setIsCrowdfunding(2);
        }

        // R10-03:发布意图键的幂等发布;编辑与旧调用者保持原路径/原语义(空串清除语义不变)。
        boolean idempotentPublish = false;
        if (isEdit) {
            return applyEdit(umsCreativeSquare, existing, contents, pics, member_id);
        } else if (StringUtils.isNotEmpty(request_id)) {
            String rid = request_id.trim();
            if (!rid.matches("[A-Za-z0-9_-]{16,64}")) {
                return error("发布标识无效");
            }
            IUmsCreativeSquareService.IdempotentPublishResult idempotent =
                    umsCreativeSquareService.insertUmsCreativeSquareIdempotent(
                            umsCreativeSquare, rid, publishSignature(umsCreativeSquare));
            switch (idempotent.getKind()) {
                case NEW:
                    // 用回读结果对象(含生成的 id)锚定后续审核/送检
                    umsCreativeSquare = idempotent.getSquare();
                    idempotentPublish = true;
                    break;
                case PENDING:
                    if (idempotent.getSquare() == null) {
                        return error("发布结果确认中，请稍后重试");
                    }
                    umsCreativeSquare = idempotent.getSquare();
                    idempotentPublish = true;
                    break;
                case EXISTING_VISIBLE:
                    // 服务端已提交、客户端丢响应后的重试:不插入/不重复文本审核/不重复奖励。
                    // 但媒体送检是 best-effort(失败只落日志、无回执),短路会让「已插入但送检未完成」
                    // 永远失去补送机会 —— 这里补一次送检,由既有 inbox fencing 兜底重复回调。
                    recoverMediaCheck(idempotent.getSquare());
                    return success(idempotent.getSquare());
                case EXISTING_DELETED:
                    // 软删终态:不回成功,也不自动复活;标记让客户端丢弃该意图键(下次发布用新键)。
                    return deletedIntentError();
                case EXISTING_REJECTED:
                    return error("内容未通过审核，请修改后重新发布");
                case CONFLICT:
                    return error("发布标识已用于其他内容，请修改后重新发布");
                case INVALID:
                    return error("发布标识无效");
                default:
                    return error("添加失败");
            }
        } else {
            if (umsCreativeSquareService.insertUmsCreativeSquare(umsCreativeSquare) <= 0) {
                return error("添加失败");
            }
        }
        // 先发后审:文本命中→BLOCK 隐藏(status=2)拦截;否则发布即可见(status=1)+图片异步送检,
        // 命中违规由消息推送回调(handleMediaCheckResult)自动下架为 status=2。结构化字段(定位/众筹)直发,不再 HOLD。
        // 幂等路径先抢审核单飞锁:并发重试只允许一个请求真正送审,其余回读现状,避免重复审核/送检。
        String auditLockKey = idempotentPublish ? ("square:publish:audit:" + umsCreativeSquare.getId()) : null;
        boolean auditLocked = false;
        boolean redisUnavailable = false;
        if (auditLockKey != null) {
            try {
                auditLocked = Boolean.TRUE.equals(redisCache.setIfAbsent(
                        auditLockKey, "1", 30, java.util.concurrent.TimeUnit.SECONDS));
            } catch (Exception lockError) {
                // Redis 异常既不能伪装成功,也不能把发布卡死在待审:本次请求按「无锁完成审核」继续。
                // 安全性:唯一键保证不重复插入;文本审核对同意图幂等;媒体送检由 MySQL inbox fencing 幂等。
                redisUnavailable = true;
                logger.warn("广场发布审核单飞锁获取异常，本次请求按无锁完成审核 id={}", umsCreativeSquare.getId(), lockError);
            }
            if (!auditLocked && !redisUnavailable) {
                UmsCreativeSquare current = umsCreativeSquareService.selectUmsCreativeSquareById(umsCreativeSquare.getId());
                if (IUmsCreativeSquareService.isSoftDeleted(current)) {
                    return deletedIntentError();
                }
                if (current != null && Integer.valueOf(1).equals(current.getStatus())) {
                    recoverMediaCheck(current);
                    return success(current);
                }
                if (current != null && Integer.valueOf(2).equals(current.getStatus())) {
                    return error("内容未通过审核，请修改后重新发布");
                }
                return error("发布结果确认中，请稍后重试");
            }
        }
        try {
            UmsMember publisher = umsMemberService.selectUmsMemberById(member_id);
            String openid = publisher != null ? publisher.getWxOpenId() : null;
            String secMsg = wxContentSecurityService.checkText(contents, openid);
            // AUDIT-G3-2:机审「审不了」(繁忙/不可用)不是违规,先发后审照常公开、留待人工复核;只有真违规才下架。
            boolean detectionUnavailable = IWxContentSecurityService.isDetectionUnavailable(secMsg);
            if (secMsg != null && !detectionUnavailable) {
                umsCreativeSquare.setStatus(2); // BLOCK:下架不公开
                umsCreativeSquareService.updateUmsCreativeSquare(umsCreativeSquare);
                return error(secMsg);
            }
            umsCreativeSquare.setStatus(1); // 发布即可见
            umsCreativeSquareService.updateUmsCreativeSquare(umsCreativeSquare);
            wxContentSecurityService.submitMediaCheck(IWxContentSecurityService.BIZ_TYPE_SQUARE, umsCreativeSquare.getId(), pics, openid);
            if (detectionUnavailable) {
                flagForLaterReview(umsCreativeSquare, member_id, contents, pics);
            }
            return success(umsCreativeSquare);
        } finally {
            if (auditLocked && auditLockKey != null) {
                try {
                    redisCache.deleteObject(auditLockKey);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 编辑:先审新正文、再替换线上内容(AUDIT-G3-3)。原实现先把新正文写进线上帖子再送检,违规文本会公开一个往返;
     * 现在新正文违规直接拒绝编辑,线上继续是旧内容;机审审不了不当违规,照常保存并留待人工复核(AUDIT-G3-2)。
     * A-03:编辑不得重置平台审核态 —— 不写 status 列(mapper 只写非 null 字段),被下架的帖子编辑后仍下架。
     * 编辑换了图片就把新图重新送异步检测,命中由回调下架;原实现编辑后的图片从不复检。
     */
    private AjaxResult applyEdit(UmsCreativeSquare edit, UmsCreativeSquare existing, String contents, String pics,
                                 Long memberId) {
        UmsMember publisher = umsMemberService.selectUmsMemberById(memberId);
        String openid = publisher != null ? publisher.getWxOpenId() : null;
        String secMsg = wxContentSecurityService.checkText(contents, openid);
        boolean detectionUnavailable = IWxContentSecurityService.isDetectionUnavailable(secMsg);
        if (secMsg != null && !detectionUnavailable) {
            return error(secMsg);
        }
        if (umsCreativeSquareService.updateUmsCreativeSquare(edit) <= 0) {
            return error("编辑失败");
        }
        boolean picsChanged = StringUtils.isNotEmpty(pics) && (existing == null || !pics.equals(existing.getPics()));
        if (picsChanged) {
            wxContentSecurityService.submitMediaCheck(IWxContentSecurityService.BIZ_TYPE_SQUARE, edit.getId(), pics, openid);
        }
        if (detectionUnavailable) {
            flagForLaterReview(edit, memberId, contents, StringUtils.isNotEmpty(pics) ? pics
                    : existing == null ? null : existing.getPics());
        }
        UmsCreativeSquare latest = umsCreativeSquareService.selectUmsCreativeSquareById(edit.getId());
        if (latest != null && Integer.valueOf(2).equals(latest.getStatus())) {
            return error("内容已保存，但该帖曾被平台下架，仍不可公开，请联系客服复核");
        }
        return success(edit);
    }

    /**
     * 机审审不了时先发后审的「留待复核」:送统一审核台(与后台 /review 同一类安全复核单),驳回即下架。
     * 送审失败只记 ERROR,不把已发布的内容改成失败回执(同图片送检的 best-effort 口径)。
     */
    private void flagForLaterReview(UmsCreativeSquare square, Long memberId, String contents, String pics) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("bizType", AuditTask.BIZ_SQUARE);
            snapshot.put("bizId", square.getId());
            snapshot.put("memberId", memberId);
            snapshot.put("contents", contents);
            snapshot.put("pics", pics);
            Map<String, Object> gate = new LinkedHashMap<>();
            gate.put("reasonCode", "TEXT_DETECTION_UNAVAILABLE");
            gate.put("riskLevel", "NORMAL");
            snapshot.put("_gate", gate);
            auditTaskService.submitSafetyHold(AuditTask.BIZ_SQUARE, square.getId(), memberId, "NORMAL",
                    com.alibaba.fastjson2.JSON.toJSONString(snapshot));
        } catch (Exception e) {
            logger.error("广场帖机审不可用后送人工复核失败,需人工补审 id={}", square.getId(), e);
        }
    }

    /** 源码级兼容:不带发布意图键的旧调用者,行为与历史一致(无幂等键)。 */
    public AjaxResult creativeSquareActoin(String id, String contents, String pics, String file_url, String address,
                                           String longitude, String latitude, String is_crowdfunding, String data_id,
                                           String data_type, String route_preview_img) {
        return creativeSquareActoin(id, contents, pics, file_url, address, longitude, latitude, is_crowdfunding,
                data_id, data_type, route_preview_img, null);
    }

    /**
     * R10-03 收尾:软删帖的幂等回执。不返回成功、不复活;`intentDiscarded` 是给客户端的明确标记,
     * 收到后丢弃该发布意图键,用户重新发布自然换新键。
     */
    private AjaxResult deletedIntentError() {
        AjaxResult result = error("该发布已删除，请重新发布");
        result.put("intentDiscarded", true);
        return result;
    }

    /**
     * R10-03 收尾:恢复路径的媒体补送。媒体送检是 best-effort(内部吞异常只落 ERROR,失败无回执),
     * 已可见帖子的重试若永远短路,「已插入但送检未完成」就再无补送机会。这里在
     * 不重复插入/不重复文本审核/不重复奖励的前提下补一次送检;无媒体或非可见态不动。
     * 重复提交由既有 MySQL inbox fencing 幂等收口(process/isCurrentSubmittedMedia);
     * 补送失败只记日志,不把已确认成功的发布改成失败回执。
     */
    private void recoverMediaCheck(UmsCreativeSquare square) {
        if (square == null || square.getId() == null || StringUtils.isEmpty(square.getPics())) {
            return;
        }
        try {
            UmsMember publisher = umsMemberService.selectUmsMemberById(square.getMemberId());
            String openid = publisher != null ? publisher.getWxOpenId() : null;
            wxContentSecurityService.submitMediaCheck(
                    IWxContentSecurityService.BIZ_TYPE_SQUARE, square.getId(), square.getPics(), openid);
        } catch (Exception mediaError) {
            logger.warn("广场发布恢复路径补送媒体审核失败，留待下次重试 id={}", square.getId(), mediaError);
        }
    }

    /** 发布意图载荷指纹:内容/媒体/位置/关联键任一变化必须是新意图。 */
    static String publishSignature(UmsCreativeSquare square) {
        if (square == null) {
            return null;
        }
        // R10-03 收尾:原 \u0001 拼接无字段边界,contents/pics 内出现分隔符时不同载荷能算出同一签名。
        // 改为「长度前缀 + 原值」序列化:字段边界由长度唯一确定,任意字符都无歧义;
        // 字段集合与空值规范化规则保持不变(同 key 变 payload 仍必须冲突)。
        StringBuilder raw = new StringBuilder(256);
        appendSignatureField(raw, nvl(square.getContents()));
        appendSignatureField(raw, nvl(square.getPics()));
        appendSignatureField(raw, nvl(square.getFileUrl()));
        appendSignatureField(raw, nvl(square.getAddress()));
        appendSignatureField(raw, nvl(square.getLongitude()));
        appendSignatureField(raw, nvl(square.getLatitude()));
        appendSignatureField(raw, square.getIsCrowdfunding() == null ? "" : String.valueOf(square.getIsCrowdfunding()));
        appendSignatureField(raw, square.getDataId() == null ? "" : String.valueOf(square.getDataId()));
        appendSignatureField(raw, square.getDataType() == null ? "" : String.valueOf(square.getDataType()));
        appendSignatureField(raw, nvl(square.getRoutePreviewImg()));
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(raw.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(out.length * 2);
            for (byte b : out) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 长度前缀字段序:<len>:<value>,len 为字符数;解析边界由 len 唯一确定。 */
    private static void appendSignatureField(StringBuilder out, String value) {
        out.append(value.length()).append(':').append(value);
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    /** 广场帖挂活动时只能挂自己主办或自己报名的 id,禁止写别人的活动。其它 data_type 不走这闸。 */
    AjaxResult rejectForeignActivity(Long memberId, Long dataId, Long dataType) {
        if (dataId == null || dataId <= 0 || dataType == null || dataType != 1L) {
            return null;
        }
        CmsActivity activity = cmsActivityService.selectCmsActivityById(dataId);
        if (activity == null) {
            return error("活动不存在");
        }
        if (OwnershipUtils.isOwner(activity.getMemberId(), memberId)) {
            return null;
        }
        CmsRegistration probe = new CmsRegistration();
        probe.setMemberId(memberId);
        probe.setOwnerType(2L);
        probe.setOwnerId(dataId);
        List<CmsRegistration> regs = cmsRegistrationService.selectCmsRegistrationList(probe);
        if (regs != null && !regs.isEmpty()) {
            return null;
        }
        return error("只能关联自己主办或报名过的活动");
    }

    /**
     * 文本内容安全检查
     * @param content 要检查的文本内容
     * @return AjaxResult 检查通过返回success()，否则返回error()
     */
    // 收口到中心化内容安全服务(v2)：原 v1 直连实现已下线，统一走 IWxContentSecurityService。
    private AjaxResult checkTextSecurity(String content) {
        String openid = null;
        try {
            Long memberId = getAppUserId();
            if (memberId != null && memberId > 0) {
                com.chengyinhub.business.domain.UmsMember m = umsMemberService.selectUmsMemberById(memberId);
                if (m != null) {
                    openid = m.getWxOpenId();
                }
            }
        } catch (Exception ignore) {
        }
        String msg = wxContentSecurityService.checkText(content, openid);
        return msg == null ? success() : error(msg);
    }

    @Operation(summary = "创意广场列表")
    @PostMapping("/list")
    @Parameters({
            @Parameter(name = "is_my", description = "0所有 1我的"),
            @Parameter(name = "keyword", description = "搜索关键词"),
            @Parameter(name = "user_id", description = "查看用户ID"),
            @Parameter(name = "feed_mode", description = "latest最新 following关注"),
            @Parameter(name = "favorite_only", description = "1只看当前用户收藏的帖文"),
    })
    public AjaxResult viewCreativeSquareList(String is_my, String keyword, Integer user_id) {

        int isMy = Convert.toInt(is_my, 0);

        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {

        }

        ViewCreativeSquare viewCreativeSquare = new ViewCreativeSquare();
        boolean favoriteOnly = Convert.toBool(ServletUtils.getParameter("favorite_only"), false);
        if (favoriteOnly) {
            if (member_id == null || member_id <= 0) return error("请先登录");
            viewCreativeSquare.setFavoriteOnly(true);
        }
        if (StringUtils.isNotEmpty(keyword)) {
            viewCreativeSquare.setContents(keyword);
        }
        if (isMy == 1) {
            if (member_id > 0) {
                viewCreativeSquare.setMemberId(member_id);
            } else {
                return error("请先登录");
            }
        } else {
            if (user_id != null && user_id > 0) {
                viewCreativeSquare.setMemberId(Long.valueOf(user_id));
            }
        }

        try {
            creativeSquareCommunityPolicyService.applyFeedPolicy(
                    viewCreativeSquare, ServletUtils.getParameter("feed_mode"), member_id);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }

        // 可见性:已登录调用者放行本人 status=0/2 待审/驳回行(仅本人可见,前端渲染"审核中/未通过"徽章);
        // 匿名沿用仅 PASS。与上面 memberId 过滤叠加安全:看他人页 member_id≠self,mapper 第二析取项恒假,不泄露他人 HOLD。
        if (member_id != null && member_id > 0) {
            viewCreativeSquare.setSelfPendingMemberId(member_id);
        } else {
            viewCreativeSquare.setStatus(1);
        }
        viewCreativeSquare.setDelFlag(0);

        List<ViewCreativeSquare> list;
        int pageNum = Math.max(Convert.toInt(ServletUtils.getParameter("pageNum"), 1), 1);
        int pageSize = clampPageSize(Math.max(Convert.toInt(ServletUtils.getParameter("pageSize"), 10), 1));
        PageHelper.startPage(pageNum, pageSize);
        list = viewCreativeSquareService.selectViewCreativeSquareList(viewCreativeSquare);

        enrichCreativeSquareList(list, member_id);

        TableDataInfo data = getDataTable(list);
        return AjaxResult.success(data);
    }


    @Operation(summary = "广场详情")
    @PostMapping("/info")
    @Parameters({
            @Parameter(name = "id", description = "广场id")
    })
    // owner 2026-10-24「游客可浏览」:与已匿名开放的 /list 同一投影,游客 member_id=0 只看已审核未删内容,
    // 点赞/收藏/关注等本人态由 enrichCreativeSquareList 按 0 算为否。
    @Anonymous
    public AjaxResult viewCreativeSquareInfo(String id) {
        ViewCreativeSquare viewCreativeSquare = new ViewCreativeSquare();

        Long Id = Convert.toLong(id, 0L);

        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {

        }

        if (Id > 0) {
            viewCreativeSquare.setId(Id);
        } else {
            return error("参数不合规" + id);
        }


        viewCreativeSquare = viewCreativeSquareService.selectViewCreativeSquareById(Id);
        if (viewCreativeSquare != null) {
            if (creativeSquareCommunityPolicyService.isHiddenByBlock(
                    member_id, viewCreativeSquare.getMemberId())) {
                return error("内容不存在");
            }
            if (viewCreativeSquare.getDelFlag() == 2) {
                return error("已删除");
            }
            if (viewCreativeSquare.getStatus() != 1) {
                return error("审核中");
            }
            if (Long.valueOf(2L).equals(viewCreativeSquare.getDataType())) {
                CmsTopic topic = cmsTopicService.selectCmsTopicById(viewCreativeSquare.getDataId());
                if (topic == null || !Integer.valueOf(0).equals(topic.getDelFlag())
                        || !Integer.valueOf(1).equals(topic.getStatus())
                        || !Integer.valueOf(1).equals(topic.getUserStatus())) {
                    return error("内容不存在");
                }
            }

            List<ViewCreativeSquare> single = new ArrayList<>();
            single.add(viewCreativeSquare);
            enrichCreativeSquareList(single, member_id);

            return success(viewCreativeSquare);

        } else {
            return error("数据不存在");
        }

    }

    @Operation(summary = "收藏/取消收藏帖文")
    @PostMapping("/bookmark")
    @Parameters({
            @Parameter(name = "id", description = "帖文ID"),
            @Parameter(name = "bookmark", description = "1收藏 0取消收藏"),
            @Parameter(name = "request_id", description = "幂等请求号")
    })
    public AjaxResult creativeSquareBookmark(String id, Integer bookmark, String request_id) {
        Long memberId = getAppUserId();
        Long postId = Convert.toLong(id, 0L);
        if (memberId == null || memberId <= 0) return error("请先登录");
        if (postId <= 0 || (bookmark == null || (bookmark != 0 && bookmark != 1))) return error("参数不合规");
        if (StringUtils.isEmpty(request_id)) return error("缺少请求号");
        Long mappedPostId = viewCreativeSquareService.selectMappedCommunityPostId(postId);
        if (mappedPostId == null || mappedPostId <= 0) return error("旧帖尚无可信社区映射，无法收藏或取消收藏");
        postId = mappedPostId;
        try {
            Map<String, Object> state;
            if (bookmark == 1) {
                CommunityPostRequests.Action action = new CommunityPostRequests.Action();
                action.setActionType("BOOKMARK");
                action.setRequestId(request_id);
                action.setSource("LEGACY_MINIPROGRAM");
                state = communityPostService.act(memberId, postId, action);
            } else {
                state = communityPostService.unact(memberId, postId, "BOOKMARK", request_id);
            }
            return success(state);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "创意广场删除")
    @PostMapping("/delete")
    @Parameters({
            @Parameter(name = "id", description = "创意广场id")
    })
    public AjaxResult ceativeSquareDelete(String id) {
        UmsCreativeSquare umsCreativeSquare = new UmsCreativeSquare();
        Long cid = Convert.toLong(id, 0L);
        if (cid < 1) {
            return error("参数不合规");
        }
        Long member_id = getAppUserId();
        if (member_id == null || member_id <= 0) {
            return error("请先登录");
        }
        if (!legacyCommunityWritesEnabled) return legacyWriteDisabled();
        UmsCreativeSquare existing = umsCreativeSquareService.selectUmsCreativeSquareById(cid);
        if (existing == null) {
            return error("内容不存在");
        }
        if (OwnershipUtils.notOwner(existing.getMemberId(), member_id)) {
            return error("无权删除该内容");
        }
        umsCreativeSquare.setId(cid);
        umsCreativeSquare.setDelFlag(2);
        int rows = umsCreativeSquareService.updateUmsCreativeSquare(umsCreativeSquare);
        if (rows > 0) {
            return success("删除成功");
        } else {
            return error("删除失败");
        }
    }

    @Operation(summary = "创意广场举报")
    @PostMapping("/report")
    @Parameters({
            @Parameter(name = "id", description = "创意广场id"),
            @Parameter(name = "reason", description = "结构化举报原因")
    })
    public AjaxResult ceativeSquareReport(String id, String reason) {
        Long cid = Convert.toLong(id, 0L);
        if (cid < 1) {
            return error("参数不合规");
        }
        Long memberId = getAppUserId();
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        if (!legacyCommunityWritesEnabled) return legacyWriteDisabled();
        String normalizedReason;
        try {
            normalizedReason = creativeSquareCommunityPolicyService.normalizeReportReason(reason);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }

        UmsCreativeSquare existing = umsCreativeSquareService.selectUmsCreativeSquareById(cid);
        if (existing == null || existing.getDelFlag() != null && existing.getDelFlag() == 2) {
            return error("内容不存在");
        }

        // 举报只进统一审核队列,不立即下架(内容审核前仍可见);审核台 REJECT 才软封下架、APPROVE 不动
        try {
            auditTaskService.reportContent("SQUARE", cid, memberId, normalizedReason);
            return success("举报已提交，内容将进入审核");
        } catch (Exception e) {
            return error("举报失败");
        }
    }

    @Operation(summary = "广场头部统计(参与活动/通关数/本周打卡节点)")
    @PostMapping("/summary")
    public AjaxResult creativeSquareSummary() {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (memberId == null || memberId <= 0) return error("请先登录");

        PlayerNodeProgress probe = new PlayerNodeProgress();
        probe.setMemberId(memberId);
        List<PlayerNodeProgress> all = playerNodeProgressService.selectPlayerNodeProgressList(probe);

        // 本周起点(周一 00:00)
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        int diff = (dow == Calendar.SUNDAY) ? 6 : dow - Calendar.MONDAY;
        cal.add(Calendar.DAY_OF_MONTH, -diff);
        Date weekStart = cal.getTime();

        int weekCheckins = 0;
        Map<Long, Integer> doneByActivity = new LinkedHashMap<>();
        for (PlayerNodeProgress p : all) {
            if (p.getCheckinTime() != null && !p.getCheckinTime().before(weekStart)) weekCheckins++;
            if (p.getActivityId() != null) doneByActivity.merge(p.getActivityId(), 1, Integer::sum);
        }

        // [P0-17] ③ 认核销不认打卡 —— 一次批量捞该玩家的 ③ 报名单(新增查询恒 1 次,与活动数无关)。
        Map<Long, CmsRegistration> exploreByActivity =
                cmsRegistrationService.selectExplorePassesByActivity(memberId);

        // ★ 驱动集合 = 打卡过的活动 ∪ 有 ③ 票的活动。
        // 只用 doneByActivity 会漏掉「只核销没打卡」的 ③ 玩家(他连键都没有)。
        // ⚠️ 同时修好一处口径分叉:joinedActivities 原本只数打卡 ⇒ 一个只核销没打卡的 ③ 玩家会
        // 显示「参与 0 个活动 / 通关 1 个」。切 completed 而不切 joined 就是**造出**这个矛盾。
        java.util.Set<Long> candidates = new java.util.LinkedHashSet<>(doneByActivity.keySet());
        candidates.addAll(exploreByActivity.keySet());

        Map<Long, CmsActivity> candidateActivities = new LinkedHashMap<Long, CmsActivity>();
        List<TopicRouteSession> routeScopes = new ArrayList<TopicRouteSession>();
        for (Long activityId : candidates) {
            CmsActivity activity = cmsActivityService.selectCmsActivityById(activityId);
            if (activity == null || activity.getTopicId() == null) continue;
            candidateActivities.put(activityId, activity);
            TopicRouteSession scope = new TopicRouteSession();
            scope.setMemberId(memberId);
            scope.setTopicId(activity.getTopicId());
            scope.setActivityId(activityId);
            routeScopes.add(scope);
        }
        Map<String, Integer> routeStatuses = topicRouteRuntimeService == null
                ? java.util.Collections.<String, Integer>emptyMap()
                : topicRouteRuntimeService.sessionStatuses(routeScopes);

        int joinedActivities = candidates.size();
        int completedCount = 0;
        for (Long activityId : candidates) {
            CmsActivity activity = candidateActivities.get(activityId);
            if (activity == null || activity.getTopicId() == null) continue;
            CmsTopicNode np = new CmsTopicNode();
            np.setTopicId(activity.getTopicId());
            np.setStatus(1L);
            int total = cmsTopicNodeService.selectCmsTopicNodeList(np).size();
            int done = doneByActivity.getOrDefault(activityId, 0);
            CmsRegistration explore = exploreByActivity.get(activityId);
            CmsTopic topic = cmsTopicService.selectCmsTopicById(activity.getTopicId());
            boolean completed = explore != null
                    ? perkLedgerService.isExploreCompleted(explore.getId(), activity.getTopicId())
                    : topicCompletionRulePolicy.isCompleted(
                            topic == null ? null : topic.getCompleteRuleJson(), total, done);
            Integer routeStatus = routeStatuses.get(ITopicRouteRuntimeService.sessionKey(
                    memberId, activity.getTopicId(), activityId));
            if (TopicRouteModePolicy.isEffectiveBranchGraph(topic, null) || routeStatus != null) {
                completed = Integer.valueOf(TopicRouteSession.STATUS_COMPLETED).equals(routeStatus);
            }
            if (completed) completedCount++;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("joinedActivities", joinedActivities); // 参与活动数
        data.put("completedCount", completedCount);      // 通关数
        data.put("weekCheckins", weekCheckins);          // 本周打卡节点数
        return AjaxResult.success(data);
    }

    @Operation(summary = "创意广场点赞/踩")
    @PostMapping("/like")
    @Parameters({
            @Parameter(name = "id", description = "创意广场"),
            @Parameter(name = "type", description = "类型 1点赞  2踩")
    })
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public AjaxResult creativeSquareLike(String id, String type) {
        UmsCreativeSquareLike umsCreativeSquareLike = new UmsCreativeSquareLike();
        Long member_id = getAppUserId();
        // 参数校验
        if (member_id == null || member_id <= 0) {
            return error("请先登录");
        }
        if (!legacyCommunityWritesEnabled) return legacyWriteDisabled();
        Long dataId = Convert.toLong(id, 0L);
        Integer likeType = Convert.toInt(type, 0);
        if (dataId < 1) {
            return error("参数不合规");
        }
        if (likeType > 2 || likeType < 1) {
            return error("参数不合规");
        }
        String targetError = contentInteractionGuard.lockSquare(dataId);
        if (targetError != null) return error(targetError);
        umsCreativeSquareLike.setDataId(dataId);
        umsCreativeSquareLike.setMemberId(member_id);


        // 同一人对同一帖只保留一行态度:同态度再点=取消;赞↔踩=改写现有行(原来一律删除,
        // 已赞时点踩会落成「无态度」)。历史重复行一并清掉,回包给权威 isLiked(0/1/2)。
        List<UmsCreativeSquareLike> list = umsCreativeSquareLikeService.selectUmsCreativeSquareLikeList(umsCreativeSquareLike);
        UmsCreativeSquareLike existing = list.isEmpty() ? null : list.get(0);
        for (int i = 1; i < list.size(); i++) {
            umsCreativeSquareLikeService.deleteUmsCreativeSquareLikeById(list.get(i).getId());
        }
        Map<String, Object> state = new HashMap<String, Object>();
        if (existing != null && likeType.equals(existing.getLikeType())) {
            umsCreativeSquareLikeService.deleteUmsCreativeSquareLikeById(existing.getId());
            state.put("isLiked", 0);
            return AjaxResult.success(likeType == 1 ? "取消点赞成功" : "取消踩成功", state);
        }
        int rows;
        if (existing != null) {
            UmsCreativeSquareLike change = new UmsCreativeSquareLike();
            change.setId(existing.getId());
            change.setLikeType(likeType);
            rows = umsCreativeSquareLikeService.updateUmsCreativeSquareLike(change);
        } else {
            umsCreativeSquareLike.setLikeType(likeType);
            rows = umsCreativeSquareLikeService.insertUmsCreativeSquareLike(umsCreativeSquareLike);
        }
        if (rows <= 0) {
            throw new ServiceException("操作失败");
        }
        state.put("isLiked", likeType);
        return AjaxResult.success(likeType == 1 ? "点赞成功" : "踩成功", state);
    }
}
