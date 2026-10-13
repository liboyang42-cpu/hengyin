package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.ClubPostComment;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IClubPostCommentService;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §8 阶段B 小尾巴:俱乐部帖/帖评论举报入口统一走 auditTaskService.reportContent
 * (与 comment/square 同法;batchA 已把 reportContent 改幂等,合并后俱乐部举报自动获得
 * 同内容汇聚一张未结单 + 按人去重防刷单)。
 * 契约:①举报走 reportContent 不再裸 submit;②已删内容不可举报、不进审核队列;
 * ③审核队列写入失败返回 error 不向上抛。
 */
class ApiClubReportTest {

    private IClubPostService clubPostService;
    private IClubPostCommentService clubPostCommentService;
    private IAuditTaskService auditTaskService;

    @BeforeEach
    void setUp() {
        clubPostService = mock(IClubPostService.class);
        clubPostCommentService = mock(IClubPostCommentService.class);
        auditTaskService = mock(IAuditTaskService.class);
    }

    private ApiClubController spyAs(long uid) {
        ApiClubController c = spy(new ApiClubController());
        doReturn(uid).when(c).getAppUserId();
        ReflectionTestUtils.setField(c, "clubPostService", clubPostService);
        ReflectionTestUtils.setField(c, "clubPostCommentService", clubPostCommentService);
        ReflectionTestUtils.setField(c, "auditTaskService", auditTaskService);
        return c;
    }

    private static int code(AjaxResult r) {
        return ((Integer) r.get("code")).intValue();
    }

    // ---------- 举报动态 ----------

    @Test
    void postReport_goesThroughReportContent_notBareSubmit() {
        ApiClubController c = spyAs(8L);
        ClubPost post = new ClubPost();
        post.setId(100L);
        post.setContent("正常动态");
        when(clubPostService.selectClubPostById(100L)).thenReturn(post);

        ClubPost body = new ClubPost();
        body.setId(100L);
        AjaxResult r = c.postReport(body);

        assertEquals(200, code(r), "举报应成功受理");
        verify(auditTaskService).reportContent(eq("CLUB_POST"), eq(100L), eq(8L), anyString());
        verify(auditTaskService, never()).submit(anyString(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void postReport_deletedPost_rejected_noAuditTask() {
        ApiClubController c = spyAs(8L);
        ClubPost post = new ClubPost();
        post.setId(101L);
        post.setDelFlag(2); // 已软删/已下架
        when(clubPostService.selectClubPostById(101L)).thenReturn(post);

        ClubPost body = new ClubPost();
        body.setId(101L);
        AjaxResult r = c.postReport(body);

        assertEquals(500, code(r), "已删动态不可举报");
        verify(auditTaskService, never()).reportContent(anyString(), anyLong(), anyLong(), anyString());
        verify(auditTaskService, never()).submit(anyString(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void postReport_auditQueueFailure_returnsErrorNotThrow() {
        ApiClubController c = spyAs(8L);
        ClubPost post = new ClubPost();
        post.setId(102L);
        when(clubPostService.selectClubPostById(102L)).thenReturn(post);
        when(auditTaskService.reportContent(anyString(), anyLong(), anyLong(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        ClubPost body = new ClubPost();
        body.setId(102L);
        AjaxResult r = c.postReport(body); // 不应向上抛异常

        assertEquals(500, code(r), "审核队列写入失败应返回 error 而非 500 异常页");
    }

    // ---------- 举报动态评论 ----------

    @Test
    void commentReport_goesThroughReportContent_notBareSubmit() {
        ApiClubController c = spyAs(9L);
        ClubPostComment cc = new ClubPostComment();
        cc.setId(200L);
        cc.setContent("正常评论");
        when(clubPostCommentService.selectClubPostCommentById(200L)).thenReturn(cc);

        ClubPostComment body = new ClubPostComment();
        body.setId(200L);
        AjaxResult r = c.commentReport(body);

        assertEquals(200, code(r), "举报应成功受理");
        verify(auditTaskService).reportContent(eq("CLUB_POST_COMMENT"), eq(200L), eq(9L), anyString());
        verify(auditTaskService, never()).submit(anyString(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void commentReport_deletedComment_rejected_noAuditTask() {
        ApiClubController c = spyAs(9L);
        ClubPostComment cc = new ClubPostComment();
        cc.setId(201L);
        cc.setDelFlag(2);
        when(clubPostCommentService.selectClubPostCommentById(201L)).thenReturn(cc);

        ClubPostComment body = new ClubPostComment();
        body.setId(201L);
        AjaxResult r = c.commentReport(body);

        assertEquals(500, code(r), "已删评论不可举报");
        verify(auditTaskService, never()).reportContent(anyString(), anyLong(), anyLong(), anyString());
        verify(auditTaskService, never()).submit(anyString(), anyLong(), anyLong(), anyString(), any());
    }

    /**
     * CU-C-138:评论作者本人不给自己开审核单。走查实证 —— 成员 990006 举报自己那条隔离评论,
     * 一路「举报 → 确认 → 已举报」,隔离库真长出 audit_task(CLUB_POST_COMMENT, submitter=990006),
     * 而被举报的评论 member_id 就是同一个人:一张没有对手的工单。
     * 同页的动态举报、成员举报早就有这道作者闸,只有评论这一行漏了。
     */
    @Test
    void commentReport_byItsOwnAuthor_isRejectedAndWritesNoAuditTask() {
        ApiClubController c = spyAs(9L);
        ClubPostComment cc = new ClubPostComment();
        cc.setId(202L);
        cc.setMemberId(9L);            // 作者就是当前登录的人
        cc.setContent("我自己的评论");
        when(clubPostCommentService.selectClubPostCommentById(202L)).thenReturn(cc);

        ClubPostComment body = new ClubPostComment();
        body.setId(202L);
        AjaxResult r = c.commentReport(body);

        assertEquals(500, code(r), "自举报不得受理");
        assertEquals("不能举报自己的评论", r.get("msg"));
        verify(auditTaskService, never()).reportContent(anyString(), anyLong(), anyLong(), anyString());
    }

    /**
     * 负控的另一半:这道闸只能挡住作者本人。别人的评论若一起被挡,就把真实举报通道掐死了
     * —— 上面 commentReport_goesThroughReportContent 用的是未设作者的样本,量不到这一条。
     */
    @Test
    void commentReport_onSomeoneElsesComment_stillAccepted() {
        ApiClubController c = spyAs(9L);
        ClubPostComment cc = new ClubPostComment();
        cc.setId(203L);
        cc.setMemberId(11L);           // 别人的评论
        cc.setContent("隔离走查:这条有问题");
        when(clubPostCommentService.selectClubPostCommentById(203L)).thenReturn(cc);

        ClubPostComment body = new ClubPostComment();
        body.setId(203L);
        AjaxResult r = c.commentReport(body);

        assertEquals(200, code(r), "举报他人评论是正当入口,不能顺手一起关");
        verify(auditTaskService).reportContent(eq("CLUB_POST_COMMENT"), eq(203L), eq(9L), anyString());
    }
}
