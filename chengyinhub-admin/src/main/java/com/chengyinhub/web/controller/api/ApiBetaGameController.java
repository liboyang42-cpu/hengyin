package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.system.domain.SysBanner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;

/**
 * 内测游戏调查
 *
 * @author webxh
 * @date 2025-10-13
 */
@RestController
@Tag(name = "内测游戏调查")
@RequestMapping("/api/betagame")

public class ApiBetaGameController extends BaseController {

    @Autowired
    private ICmsBetaGameService cmsBetaGameService;

    @Autowired
    private ICmsBetaGameVoteService cmsBetaGameVoteService;

    @Autowired
    private IUmsMemberService umsMemberService;


    @Autowired
    private IUmsMemberPointsDetailService umsMemberPointsDetailService;


    @Autowired
    private ICmsPointsSettingService cmsPointsSettingService;

    @Autowired
    private IWxContentSecurityService wxContentSecurityService;

    @Operation(summary = "游戏列表")
    @PostMapping(value = "/list")
    @Parameters({
    })
    public AjaxResult getList() {
        CmsBetaGame cmsBetaGame = new CmsBetaGame();
        cmsBetaGame.setStatus(Long.valueOf(1));
        List<CmsBetaGame> list = cmsBetaGameService.selectCmsBetaGameList(cmsBetaGame);
        return success(list);
    }


    @Operation(summary = "内测游戏调查详情")
    @PostMapping("/info")
    @Parameters({
            @Parameter(name = "id", description = "id")
    })
    @Anonymous
    public AjaxResult betaGameInfo(String id) {
        CmsBetaGame cmsBetaGame = new CmsBetaGame();
        Long gameId = Convert.toLong(id, 0L);

        if (gameId > 0) {
            cmsBetaGame.setId(gameId);
        } else {
            return error("参数不合规");
        }

        cmsBetaGame = cmsBetaGameService.selectCmsBetaGameById(gameId);
        if (cmsBetaGame == null) {
            return error("数据不存在");
        }

        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {
            logger.warn("betaGameDetail 获取用户ID异常", e);
        }

        if (member_id < 1) {
            cmsBetaGame.setIsVote(0);
        } else {
            CmsBetaGameVote cmsBetaGameVote = new CmsBetaGameVote();
            cmsBetaGameVote.setGameId(gameId);
            cmsBetaGameVote.setMemberId(member_id);
            List<CmsBetaGameVote> cmsBetaGameVoteList = cmsBetaGameVoteService.selectCmsBetaGameVoteList(cmsBetaGameVote);
            if (cmsBetaGameVoteList.size() > 0) {
                cmsBetaGame.setIsVote(1);
            } else {
                cmsBetaGame.setIsVote(0);
            }
        }
        return AjaxResult.success(cmsBetaGame);
    }

    @Operation(summary = "内测游戏调查投票")
    @PostMapping("/vote")
    @Parameters({
            @Parameter(name = "id", description = "id"),
            @Parameter(name = "star", description = "评分1-5分"),
            @Parameter(name = "contents", description = "评论内容")
    })
    public AjaxResult addVote(String id, String star, String contents) {
        CmsBetaGameVote cmsBetaGameVote = new CmsBetaGameVote();
        Long member_id = getAppUserId();
        // 参数校验
        if (member_id == null || member_id <= 0) {
            return error("请先登录");
        }

        cmsBetaGameVote.setMemberId(member_id);

        if (StringUtils.isNotEmpty(contents)) {
            // 内容安全:评价文本命中违规即拒
            com.chengyinhub.business.domain.UmsMember _u = umsMemberService.selectUmsMemberById(member_id);
            String secMsg = wxContentSecurityService.checkText(contents, _u == null ? null : _u.getWxOpenId());
            if (secMsg != null) return error(secMsg);
            cmsBetaGameVote.setContents(contents);
        } else {
            return error("请输入评价内容内容");
        }
        Long gameId;
        if (StringUtils.isNotEmpty(id)) {
            // 修复:此处应写投票的 game_id 外键,而非主键 id;
            // 原先误用 setId 导致插入行 game_id 恒为 null,防重查询永不命中 → 可无限重复投票刷积分。
            gameId = Convert.toLong(id, 0L);
            cmsBetaGameVote.setGameId(gameId);
        } else {
            return error("参数不合规");
        }
        if (StringUtils.isNotEmpty(star)) {
            cmsBetaGameVote.setStar(Convert.toLong(star, 0L));
        } else {
            return error("请选择评分");
        }

        //查询游戏存在不
        CmsBetaGame cmsBetaGame = cmsBetaGameService.selectCmsBetaGameById(gameId);
        if (cmsBetaGame == null) {
            return error("数据不存在");
        } else {
            if (cmsBetaGame.getStatus() != 1) {
                return error("活动已下架");
            } else if (cmsBetaGame.getDelFlag() == 2) {
                return error("活动已删除");
            } else if (cmsBetaGame.getStartDate().getTime() > System.currentTimeMillis()) {
                return error("活动未开始");
            } else if (cmsBetaGame.getEndDate().getTime() < System.currentTimeMillis()) {
                return error("活动已结束");
            }
        }

        //查询是否已投票
        CmsBetaGameVote cmsBetaGameVoteQuery = new CmsBetaGameVote();
        cmsBetaGameVoteQuery.setMemberId(member_id);
        cmsBetaGameVoteQuery.setGameId(gameId);
        List<CmsBetaGameVote> cmsBetaGameVoteList = cmsBetaGameVoteService.selectCmsBetaGameVoteList(cmsBetaGameVoteQuery);
        if (cmsBetaGameVoteList.size() > 0) {
            return error("您已投票，请不要重复投票");
        }
        //添加
        int rows = cmsBetaGameVoteService.insertCmsBetaGameVote(cmsBetaGameVote);
        if (rows > 0) {

            UmsMember umsMember = umsMemberService.selectUmsMemberById(member_id);
            //参与活动
            if (umsMember!=null){


                int points = 0;
                CmsPointsSetting cmsPointsSetting = cmsPointsSettingService.selectCmsPointsSettingById(4L);
                if (cmsPointsSetting != null) {
                    points = cmsPointsSetting.getValue().intValue();
                }
                umsMemberService.addPoints(umsMember.getId(), points);

                //赠送积分
                UmsMemberPointsDetail    umsMemberPointsDetail = new UmsMemberPointsDetail();
                umsMemberPointsDetail.setMemberId(umsMember.getId());
                umsMemberPointsDetail.setCurrentPoints(umsMember.getPoint());
                umsMemberPointsDetail.setAfterPoints(umsMember.getPoint() + points);
                umsMemberPointsDetail.setChangePoints(Long.valueOf(points));
                umsMemberPointsDetail.setChangeReason("活动评价/投票");
                umsMemberPointsDetail.setEventType(Long.valueOf(4));
                umsMemberPointsDetail.setEventId(cmsBetaGame.getId());
                umsMemberPointsDetail.setChangeType(1L);
                umsMemberPointsDetail.setDelFlag(0);
                umsMemberPointsDetail.setCreateTime(new Date());
                umsMemberPointsDetailService.insertUmsMemberPointsDetail(umsMemberPointsDetail);
            }

            return success(cmsBetaGameVote);
        } else {
            return error("投票失败");
        }
    }

}
