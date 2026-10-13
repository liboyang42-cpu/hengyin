package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-09-06 给俱乐部加了三个现场事件动作(Figma D8)。这是一次**授权放宽**,
 * 本类锁住放宽的边界,防止以后被人顺手抹平:
 *
 *  ① 两个动作集必须互斥 —— 一旦有人把 STATION_PAUSE 直接塞进 CLUB_ACTIONS,
 *    preauthorize 会先进 CLUB 分支,商家那层「站点属于我」的校验再也跑不到。
 *  ② 俱乐部的提交清单不许查 evidence_json —— 俱乐部不看玩家提交的照片与答案。
 *  ③ 俱乐部只能驳回,不能通过 —— 看不到内容还点通过就是盖橡皮章。
 *  ④ 俱乐部暂停站点仍必须走 resolveFallbackPlan —— 备用方案只能是已批准的那些。
 */
class ClubSiteEventAuthorityTest {
    private String read(String relative) throws Exception {
        Path path = Paths.get(relative);
        assertTrue(Files.isRegularFile(path), "缺文件:" + relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private String service() throws Exception {
        return read("../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/"
                + "GameSessionRuntimeServiceImpl.java");
    }

    private Set<String> actionSet(String source, String constantName) {
        Matcher m = Pattern.compile(
                constantName + "\\s*=\\s*new HashSet<>\\(Arrays\\.asList\\(([^;]*?)\\)\\);",
                Pattern.DOTALL).matcher(source);
        assertTrue(m.find(), "没解析到 " + constantName + ",常量写法变了就说明这条契约需要重写");
        Set<String> out = new LinkedHashSet<>();
        Matcher lit = Pattern.compile("\"([A-Z_]+)\"").matcher(m.group(1));
        while (lit.find()) out.add(lit.group(1));
        assertFalse(out.isEmpty(), constantName + " 解析出空集合");
        return out;
    }

    @Test
    void 俱乐部动作集与商家动作集必须互斥() throws Exception {
        String source = service();
        Set<String> club = actionSet(source, "CLUB_ACTIONS");
        Set<String> merchant = actionSet(source, "MERCHANT_ACTIONS");
        Set<String> overlap = new LinkedHashSet<>(club);
        overlap.retainAll(merchant);
        assertTrue(overlap.isEmpty(),
                "动作集重叠:" + overlap + "。preauthorize 会先进 CLUB 分支,"
                        + "商家那层「站点属于当前商家」的校验再也跑不到 —— 俱乐部要同形动作请单开 CLUB_ 前缀的名字。");
        assertTrue(club.contains("CLUB_STATION_PAUSE"), "俱乐部现场事件的暂停动作丢了");
        assertTrue(club.contains("CLUB_REJECT_SUBMISSION"), "俱乐部现场事件的驳回动作丢了");
    }

    @Test
    void 俱乐部的提交清单不许查提交内容() throws Exception {
        String xml = read("../chengyinhub-system/src/main/resources/mapper/business/"
                + "GameSessionRuntimeMapper.xml");
        int start = xml.indexOf("<select id=\"selectClubSubmissionProjection\"");
        assertTrue(start > 0, "俱乐部提交清单查询不见了");
        String sql = xml.substring(start, xml.indexOf("</select>", start));
        assertFalse(sql.contains("evidence_json"),
                "俱乐部提交清单查了 evidence_json —— 俱乐部不该看到玩家提交的照片与答案");
    }

    @Test
    void 俱乐部只能驳回不能通过() throws Exception {
        String source = service();
        int start = source.indexOf("private ActionResult clubRejectSubmission(");
        assertTrue(start > 0, "clubRejectSubmission 不见了");
        String body = source.substring(start, source.indexOf("\n    private ", start + 10));
        assertTrue(body.contains("\"REJECT\".equals(upper(command.getPayload().get(\"decision\")))"),
                "clubRejectSubmission 不再拒绝非 REJECT 的 decision —— 俱乐部看不到内容却能点通过");
        assertTrue(body.contains("submission.setStatus(\"REJECTED\")"), "驳回没有写成 REJECTED");
        assertFalse(body.contains("\"APPROVED\""), "clubRejectSubmission 里出现了 APPROVED");
    }

    @Test
    void 俱乐部暂停站点仍必须用已批准的备用方案() throws Exception {
        String source = service();
        int start = source.indexOf("private ActionResult clubStationPause(");
        assertTrue(start > 0, "clubStationPause 不见了");
        String body = source.substring(start, source.indexOf("\n    private ", start + 10));
        // R9-38(2026-09-15):没有已批准备用方案时也必须能停本站(现实停业不能无路可走),
        // 所以断言从「必须调用 resolveFallbackPlan」改成「必须走共用的暂停解析链」——
        // 给了方案就还是只能从已批准版本里解析,俱乐部照样不能自己指定备用站点。
        assertTrue(body.contains("resolvePauseFallback("),
                "俱乐部暂停站点绕过了共用的备用方案解析 —— 备用方案必须是已批准的那些");
        assertTrue(body.contains("fallbackNodeId"),
                "没有再拒绝调用方直接指定 fallbackNodeId");
        int helper = source.indexOf("private FallbackPlan resolvePauseFallback(");
        assertTrue(helper > 0, "resolvePauseFallback 不见了");
        String helperBody = source.substring(helper, source.indexOf("\n    private ", helper + 10));
        assertTrue(helperBody.contains("resolveFallbackPlan("),
                "resolvePauseFallback 没有把已声明方案交回 resolveFallbackPlan 严格解析");
    }
}
