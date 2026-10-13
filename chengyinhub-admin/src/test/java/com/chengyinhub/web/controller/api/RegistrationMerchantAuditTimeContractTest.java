package com.chengyinhub.web.controller.api;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.ViewRegistrationMerchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 承接进度行的「审核通过 8.2 14:05」(稿 88:4368)。
 *
 * <p>补这一列之前,cms_registration_merchant 只记住了审核的**结论**(audit_status),
 * 没记住它发生在什么时候。前端因此只能把这一行的时间留空 —— 拿 update_time 顶替
 * 会写出一个会漂的时间:商家改一次资料、后台动一次分润,那个「审核通过时刻」就变了。
 *
 * <p>这条合同盯四件事,少一件这一列就是个摆设:
 * ① 审核时间与审核结论写在**同一条 CAS 语句**里,不会出现「已通过但时间是空」的中间态;
 * ② 通过和驳回都写(商家要知道是什么时候被拒的);
 * ③ 退回待定时清空 —— 上一次审核已经作废,留着会印出一个与当前状态矛盾的时刻;
 * ④ 读侧那张视图跟着加了这一列(视图不会自己跟上基表)。
 */
class RegistrationMerchantAuditTimeContractTest
{
    private static String source(String relativeToRepoRoot) throws Exception
    {
        Path repoRoot = Paths.get(System.getProperty("user.dir")).getParent();
        Path file = repoRoot.resolve(relativeToRepoRoot);
        assertTrue(Files.exists(file), "源文件不在:" + file);
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String baseMapper() throws Exception
    {
        return source("chengyinhub-system/src/main/resources/mapper/business/CmsRegistrationMerchantMapper.xml");
    }

    /** 取 <update id="x"> … </update> 之间那一段,避免在整份文件里瞎匹配。 */
    private static String statement(String mapper, String id)
    {
        int from = mapper.indexOf("<update id=\"" + id + "\"");
        assertTrue(from > 0, "mapper 里没有这条语句:" + id);
        int to = mapper.indexOf("</update>", from);
        assertTrue(to > from, id + " 没有闭合");
        return mapper.substring(from, to);
    }

    @Test
    void 两个域类都带得住这个时刻()
    {
        Date now = new Date();

        CmsRegistrationMerchant row = new CmsRegistrationMerchant();
        row.setAuditTime(now);
        assertEquals(now, row.getAuditTime(), "基表域类少这个字段,mapper 映射了也接不住");

        ViewRegistrationMerchant view = new ViewRegistrationMerchant();
        view.setAuditTime(now);
        assertEquals(now, view.getAuditTime(), "读侧走的是视图域类,承接进度页读的就是它");
    }

    @Test
    void 中标与驳回都在同一条语句里写下时刻() throws Exception
    {
        String mapper = baseMapper();

        String win = statement(mapper, "confirmWinnerIfPending");
        assertTrue(win.contains("audit_time = now()"),
                "中标是审核的一种结论 —— 时刻必须跟结论同一条 update 落库,"
                        + "分两步写会出现「状态已通过、时间还是空」的中间态");

        String reject = statement(mapper, "rejectIfPending");
        assertTrue(reject.contains("audit_time = now()"),
                "驳回也要写:商家得知道自己是什么时候被拒的,不然那一行永远没有时间");
    }

    @Test
    void 退回待定时清空_不留一个与当前状态矛盾的时刻() throws Exception
    {
        String edit = statement(baseMapper(), "updateEditableFieldsIfStateMatches");
        int rejectedBranch = edit.indexOf("<if test=\"rejected\">");
        assertTrue(rejectedBranch > 0, "商家改资料退回待定的分支不在了");
        int branchEnd = edit.indexOf("</if>", rejectedBranch);
        String branch = edit.substring(rejectedBranch, branchEnd);
        assertTrue(branch.contains("audit_time = null"),
                "退回待定 ⇒ 上一次审核作废。不清空的话进度行会印「审核通过 8.2 14:05」,"
                        + "而当前状态其实是「主办方审核中」");
    }

    @Test
    void 读侧视图与迁移都跟上了这一列() throws Exception
    {
        String viewMapper = source("chengyinhub-system/src/main/resources/mapper/business/ViewRegistrationMerchantMapper.xml");
        assertTrue(viewMapper.contains("audit_time"),
                "selectVo 不选这一列,域类有字段也拿不到值");

        String migration = source("chengyinhub-admin/sql/migration_registration_merchant_audit_time_20260910.sql");
        assertTrue(migration.contains("ADD COLUMN `audit_time`"), "迁移里没有加列语句");
        assertTrue(migration.contains("CREATE OR REPLACE"),
                "视图不会自己跟上基表新增的列 —— 迁移必须连带重建 view_registration_merchant");
        assertTrue(migration.contains("`crm`.`audit_time`"),
                "重建的视图定义里得真的把这一列投出来");
        assertFalse(migration.toLowerCase().contains("update cms_registration_merchant set audit_time"),
                "存量行不回填:历史上这个时刻没被记录过,编一个出来就是假数据");
    }
}
