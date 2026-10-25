package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.config.WxPayConfig;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.utils.JsonUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.http.HttpUtils;
import com.chengyinhub.framework.web.domain.server.Sys;
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
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.chengyinhub.common.utils.OwnershipUtils;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.enums.LimitType;
/**
 * 评论接口
 *
 * @author webxh
 * @date 2025-08-29
 */
@RestController
@Tag(name = "评论")
@RequestMapping("/api/comment")
public class ApiCommentController extends BaseController {

    @Value("${community.post.legacy-writes-enabled:true}")
    private boolean legacyCommunityWritesEnabled;

    private AjaxResult legacySquareWriteDisabled() {
        return AjaxResult.error(HttpStatus.UPGRADE_REQUIRED, "广场互动已升级，请更新客户端后重试");
    }

    @Autowired
    private IUmsCommentService umsCommentService;

    @Autowired
    private ContentInteractionGuard contentInteractionGuard;

    @Autowired
    private IAuditTaskService auditTaskService;

    @Autowired
    private IUmsCommentLikeService umsCommentLikeService;

    @Autowired
    private IViewCommentService viewCommentService;

    @Autowired
    private IUmsMemberFollowService umsMemberFollowService;

    @Autowired(required = false)
    private IPlayerProgressionService playerProgressionService;


    @Autowired
    private IUmsMemberService umsMemberService;


    @Autowired
    private CommentPublicationService commentPublicationService;

    @Autowired
    private com.chengyinhub.business.mapper.UmsCommentMapper commentFacts;

    @Autowired
    private IWxContentSecurityService wxContentSecurityService;


    @Operation(summary = "添加评论")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    @PostMapping("/add")
    @Parameters({
            @Parameter(name = "owner_type", description = "类型1、主题  2、活动  3、创意广场"),
            @Parameter(name = "owner_id", description = "主题ID/活动ID/创意广场帖子ID"),
            @Parameter(name = "rating", description = "评分0-5"),
            @Parameter(name = "contents", description = "评论内容"),
            @Parameter(name = "reply_id", description = "回复评论ID"),
            @Parameter(name = "img_arr", description = "评论图片（多张）分号隔开")
    })
    public AjaxResult addComment(String owner_type, String owner_id, String rating, String contents, String reply_id, String img_arr) {
        UmsComment umsComment = new UmsComment();
        Long member_id = getAppUserId();
        // 参数校验
        if (member_id == null || member_id <= 0) {
            return error("请先登录");
        }
        Long requestedOwnerType = Convert.toLong(owner_type, 0L);
        if (requestedOwnerType == 3L && !legacyCommunityWritesEnabled) {
            return legacySquareWriteDisabled();
        }

        umsComment.setMemberId(member_id);

        if (StringUtils.isNotEmpty(contents)) {
            umsComment.setContents(contents);
        } else {
            return error("请输入发布内容");
        }
        if (StringUtils.isNotEmpty(owner_type)) {
            umsComment.setOwnerType(Convert.toLong(owner_type, 0L));
        }
        if (StringUtils.isNotEmpty(owner_id)) {
            umsComment.setOwnerId(Convert.toLong(owner_id, 0L));
        }
        if (StringUtils.isNotEmpty(rating)) {
            umsComment.setRating(Convert.toLong(rating, 0L));
        }
        if (StringUtils.isNotEmpty(contents)) {
            umsComment.setContents(contents);
        }
        if (StringUtils.isNotEmpty(reply_id)) {
            umsComment.setReplyId(Convert.toLong(reply_id, 0L));
        }
        if (StringUtils.isNotEmpty(img_arr)) {
            umsComment.setImgArr(img_arr);
        }
        UmsMember member = umsMemberService.selectUmsMemberById(member_id);
        String openid = member == null ? null : member.getWxOpenId();
        String securityError = wxContentSecurityService.checkText(contents, openid);
        if (securityError != null) return error(securityError);

        String publicationError = commentPublicationService.publish(umsComment);
        if (publicationError != null) return error(publicationError);
        // 评论和奖励已提交后才送检，避免回调看到未提交内容或奖励失败后遗留送检。
        wxContentSecurityService.submitMediaCheck(IWxContentSecurityService.BIZ_TYPE_COMMENT,
                umsComment.getId(), img_arr, openid);
        return success(umsComment);
    }


    /**
     * 文本内容安全检查
     * @param content 要检查的文本内容
     * @return AjaxResult 检查通过返回success()，否则返回error()
     */
    // 收口到中心化内容安全服务(v2)：原 v1 直连实现已下线，统一走 IWxContentSecurityService。
    // 返回 success() 放行；error(msg) 拦截。开关/凭据仍由 WxPayConfig 提供（同一套配置）。
    private AjaxResult checkTextSecurity(String content) {
        String openid = null;
        try {
            Long memberId = getAppUserId();
            if (memberId != null && memberId > 0) {
                UmsMember m = umsMemberService.selectUmsMemberById(memberId);
                if (m != null) {
                    openid = m.getWxOpenId();
                }
            }
        } catch (Exception ignore) {
        }
        String msg = wxContentSecurityService.checkText(content, openid);
        return msg == null ? success() : error(msg);
    }

    @Operation(summary = "评论列表")
    @PostMapping("/list")
    @Parameters({
            @Parameter(name = "owner_type", description = "类型1、主题  2、活动  3、创意广场"),
            @Parameter(name = "owner_id", description = "主题ID/活动ID/创意广场帖子ID"),
    })
    public AjaxResult commentList(String owner_type, String owner_id) {

        Long ownerType = Convert.toLong(owner_type, 0L);
        Long ownerId = Convert.toLong(owner_id, 0L);
        if (ownerType < 1 || ownerType > 4 || ownerId < 1) {
            return error("参数不合规");
        }
        if (ownerType == 4 && !commentFacts.reviewTargetExists(4L, ownerId))
            return error("据点未开放或已下线");

        ViewComment viewComment = new ViewComment();
        viewComment.setOwnerType(ownerType);
        viewComment.setOwnerId(ownerId);
        viewComment.setStatus(1);
        viewComment.setDelFlag(0);

        List<ViewComment> list;
        startPage();
        list = viewCommentService.selectViewCommentList(viewComment);

        // 只查当前页，复用旧评论 status/del_flag 可见性；不改变 PageHelper 的 rows/total。
        Map<Long, Long> replyCounts = new HashMap<>();
        if (!list.isEmpty()) {
            List<Long> parentIds = new java.util.ArrayList<>();
            for (ViewComment comment : list) parentIds.add(comment.getId());
            for (ViewComment count : viewCommentService.selectVisibleReplyCountsByParents(ownerType, ownerId, parentIds)) {
                replyCounts.put(count.getId(), count.getReplyCommentCount());
            }
        }
        for (ViewComment comment : list) {
            //该评论的回复评论数
            comment.setReplyCommentCount(replyCounts.getOrDefault(comment.getId(), 0L));

            Long member_id = 0L;
            try {
                member_id = getAppUserId();
            } catch (Exception e) {

            }

            if (member_id == null || member_id <= 0) {
                comment.setIsLiked(0);
                comment.setIsFollowTheUser(0);
            } else {
                //查询用户对该评论是否点赞
                UmsCommentLike umsCommentLike = new UmsCommentLike();
                umsCommentLike.setCommentId(comment.getId());
                umsCommentLike.setMemberId(member_id);
                List<UmsCommentLike> list2 = umsCommentLikeService.selectUmsCommentLikeList(umsCommentLike);
                if (list2.size() > 0) {
                    comment.setIsLiked(1);
                } else {
                    comment.setIsLiked(0);
                }
                //查询用户对该评论发表者是否关注
                UmsMemberFollow umsMemberFollow = new UmsMemberFollow();
                umsMemberFollow.setFollowMemberId(comment.getMemberId());
                umsMemberFollow.setMemberId(member_id);
                List<UmsMemberFollow> list3 = umsMemberFollowService.selectUmsMemberFollowList(umsMemberFollow);
                if (list3.size() > 0) {
                    comment.setIsFollowTheUser(1);
                } else {
                    comment.setIsFollowTheUser(0);
                }
            }
        }

        // RUN-13(拍板 2026-09-19 第4条):评论里的 Lv. 与动态 feed 同一真源 —— player_growth,
        // 视图列 member_level_id(旧 ums_member.level_id)下发前批量覆盖,无行兜底 Lv.1。
        if (playerProgressionService != null && !list.isEmpty()) {
            java.util.Set<Long> commenterIds = new java.util.LinkedHashSet<Long>();
            for (ViewComment comment : list) {
                if (comment != null && comment.getMemberId() != null) commenterIds.add(comment.getMemberId());
            }
            if (!commenterIds.isEmpty()) {
                Map<Long, Integer> levels =
                        playerProgressionService.levelsByMemberIds(new java.util.ArrayList<Long>(commenterIds));
                for (ViewComment comment : list) {
                    if (comment != null && comment.getMemberId() != null) {
                        Integer lv = levels.get(comment.getMemberId());
                        comment.setMemberLevelId((long) (lv == null ? 1 : lv));
                    }
                }
            }
        }

        TableDataInfo data = getDataTable(list);
        return AjaxResult.success(data);
    }

    @Operation(summary = "删除评论")
    @PostMapping("/delete")
    @Parameters({
            @Parameter(name = "id", description = "评论id")
    })
    public AjaxResult deleteComment(String id) {
        UmsComment umsComment = new UmsComment();
        Long member_id = getAppUserId();
        // 参数校验
        if (member_id == null || member_id <= 0) {
            return error("请先登录");
        }
        Long commentId = 0L;
        if (StringUtils.isNotEmpty(id)) {
            umsComment.setId(Convert.toLong(id, 0L));
        } else {
            return error("参数不合规");
        }
        UmsComment existing = umsCommentService.selectUmsCommentById(umsComment.getId());
        if (existing == null) {
            return error("评论不存在");
        }
        if (Long.valueOf(3L).equals(existing.getOwnerType()) && !legacyCommunityWritesEnabled) {
            return legacySquareWriteDisabled();
        }
        if (OwnershipUtils.notOwner(existing.getMemberId(), member_id)) {
            return error("无权删除该评论");
        }
        umsComment.setDelFlag(2);

        int rows = umsCommentService.updateUmsComment(umsComment);
        if (rows > 0) {
            return success("删除成功");
        } else {
            return error("删除失败");
        }
    }

    @Operation(summary = "举报评论")
    @RateLimiter(time = 60, count = 10, limitType = LimitType.MEMBER)
    @PostMapping("/report")
    @Parameters({
            @Parameter(name = "id", description = "评论id")
    })
    public AjaxResult reportComment(String id) {
        Long memberId = getAppUserId();
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        Long commentId = Convert.toLong(id, 0L);
        if (commentId < 1) {
            return error("参数不合规");
        }

        UmsComment existing = umsCommentService.selectUmsCommentById(commentId);
        if (existing == null || existing.getDelFlag() != null && existing.getDelFlag() == 2) {
            return error("评论不存在");
        }
        if (Long.valueOf(3L).equals(existing.getOwnerType()) && !legacyCommunityWritesEnabled) {
            return legacySquareWriteDisabled();
        }

        // 举报只进统一审核队列,不立即下架(评论审核前仍可见);审核台 REJECT 才软封下架、APPROVE 不动
        try {
            auditTaskService.reportContent("COMMENT", commentId, memberId, "用户举报");
            return success("举报已提交，评论将进入审核");
        } catch (Exception e) {
            return error("举报失败");
        }
    }

    @Operation(summary = "评论点赞")
    @RateLimiter(time = 60, count = 60, limitType = LimitType.MEMBER)
    @PostMapping("/like")
    @Parameters({
            @Parameter(name = "id", description = "评论id")
    })
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public AjaxResult commentLike(String id) {
        UmsCommentLike umsCommentLike = new UmsCommentLike();
        Long member_id = getAppUserId();
        // 参数校验
        if (member_id == null || member_id <= 0) {
            return error("请先登录");
        }
        Long commentId = 0L;
        if (StringUtils.isNotEmpty(id)) {
            commentId = Convert.toLong(id, 0L);
        } else {
            return error("参数不合规");
        }
        // 门禁读必须排在锁之后:RR 隔离下先做普通 SELECT 会把读视图建在拿锁等待之前,
        // 并发点赞会基于旧快照双插入(3-20 toggle 回归)。默认开闸时零额外读取。
        String targetError = contentInteractionGuard.lockComment(commentId);
        if (targetError != null) return error(targetError);
        if (!legacyCommunityWritesEnabled) {
            UmsComment targetComment = umsCommentService.selectUmsCommentById(commentId);
            if (targetComment != null && Long.valueOf(3L).equals(targetComment.getOwnerType())) {
                return legacySquareWriteDisabled();
            }
        }
        umsCommentLike.setCommentId(commentId);
        umsCommentLike.setMemberId(member_id);

        List<UmsCommentLike> list = umsCommentLikeService.selectUmsCommentLikeList(umsCommentLike);
        // 开关语义;data.isLiked 回终态,前端以它为准(3-20)
        if (list.size() > 0) {
            //取消关注
            // 提取ID集合
            Long[] ids = list.stream()
                    .map(UmsCommentLike::getId)
                    .toArray(Long[]::new);

            // 调用批量删除方法
            int rows = umsCommentLikeService.deleteUmsCommentLikeByIds(ids);
            if (rows > 0) {
                return AjaxResult.success("取消点赞成功", java.util.Collections.singletonMap("isLiked", 0));
            } else {
                return error("取消点赞失败");
            }
        } else {
            //新增关注
            int rows = umsCommentLikeService.insertUmsCommentLike(umsCommentLike);
            if (rows > 0) {
                return AjaxResult.success("点赞成功", java.util.Collections.singletonMap("isLiked", 1));
            } else {
                return error("点赞失败");
            }
        }
    }
}
