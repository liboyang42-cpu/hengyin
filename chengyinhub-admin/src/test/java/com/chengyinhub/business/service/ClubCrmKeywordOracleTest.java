package com.chengyinhub.business.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户列表的 keyword 不得成为手机号预言机(2026-09-02 安全审查)。
 *
 * <p>下发给调用方的是掩码 {@code 138****5621}(前 3 后 4)。一旦 keyword 能对
 * {@code ums_member.phone} 明文列做**非锚定** LIKE,"这个客户在不在结果里"就是一个布尔回答:
 * 从已知前缀逐位延长试探,约 40 次请求即可还原完整 11 位号码;拿外部号码名单逐个试,
 * 还能反查"这个号码是不是我俱乐部的客户"。字段没下发,号码照样能取回。
 *
 * <p>兄弟实现(商家 CRM {@code MerchantCrmAppMapper.xml})的同形功能**刻意只搜昵称**,
 * 这里与它对齐。确需按手机号找人要做全 11 位精确等值匹配,不留部分匹配的试探面。
 *
 * <p>判据钉的是 <b>mapper XML 里 phone 列有没有出现在 LIKE 谓词里</b>(结构),
 * 不是钉某一行字面量 —— 解释这次修复的注释里必然出现 {@code u.phone like},
 * 所以扫描前先把 XML 注释整段剥掉。
 */
class ClubCrmKeywordOracleTest {

    private static final Path MAPPER = Paths.get(
            "..", "chengyinhub-system", "src", "main", "resources",
            "mapper", "business", "ClubCrmMapper.xml");

    private static String codeWithoutComments() throws Exception {
        String xml = new String(Files.readAllBytes(MAPPER), StandardCharsets.UTF_8);
        return xml.replaceAll("(?s)<!--.*?-->", "");
    }

    /** 找出所有「phone 列 + LIKE」的谓词,不管中间隔了什么别名/函数 */
    private static int countPhoneLikePredicates(String sql) {
        Pattern p = Pattern.compile("[\\w.]*phone[\\w.]*\\s+like", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(sql);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    @Test
    @DisplayName("keyword 不得对 phone 列做 LIKE —— 那会把掩码变成可枚举的预言机")
    void keywordMustNotLikePhone() throws Exception {
        String sql = codeWithoutComments();
        assertEquals(0, countPhoneLikePredicates(sql),
                "ClubCrmMapper.xml 里出现了 phone 列的 LIKE 谓词。"
                        + "下发的是掩码,但 LIKE 能逐位试探还原完整号码(约 40 次请求),"
                        + "并且能反查任意号码是不是本俱乐部客户。"
                        + "按昵称搜即可;确需按号码找人要用全 11 位精确等值匹配。");
    }

    @Test
    @DisplayName("keyword 仍然要能按昵称搜 —— 别把功能整个删掉")
    void keywordStillSearchesNickname() throws Exception {
        String sql = codeWithoutComments();
        assertTrue(Pattern.compile("nickname[^\\n]*like", Pattern.CASE_INSENSITIVE).matcher(sql).find(),
                "keyword 应当仍按昵称模糊匹配,修复不该把搜索功能一起删掉");
    }

    @Test
    @DisplayName("负控:把 phone LIKE 加回去必须判红")
    void negativeControlPhoneLikeIsCaught() throws Exception {
        String clean = codeWithoutComments();
        assertEquals(0, countPhoneLikePredicates(clean), "干净输入不该报");

        String mutated = clean.replaceFirst(
                "(and coalesce\\(nullif\\(u\\.nickname, ''\\), ''\\) like concat\\('%', #\\{keyword\\}, '%'\\))",
                "$1 or u.phone like concat('%', #{keyword}, '%')");
        assertNotEqualsIgnoringWhitespace(clean, mutated);
        assertEquals(1, countPhoneLikePredicates(mutated), "检查器没抓到注入的 phone LIKE");
    }

    @Test
    @DisplayName("负控:注释里提到 u.phone like 不算违规 —— 判据看代码不看注解")
    void negativeControlCommentIsNotAViolation() throws Exception {
        String withComment = "<!-- 原本还有 or u.phone like concat('%', #{keyword}, '%') -->\n"
                + "and u.nickname like concat('%', #{keyword}, '%')";
        String stripped = withComment.replaceAll("(?s)<!--.*?-->", "");
        assertEquals(0, countPhoneLikePredicates(stripped),
                "解释修复的注释里必然出现该字样,按原文扫会把注释判成违规");
        assertTrue(countPhoneLikePredicates(withComment) > 0,
                "证明剥注释这一步真的起了作用,而不是恒不报");
    }

    private static void assertNotEqualsIgnoringWhitespace(String a, String b) {
        assertFalse(a.equals(b), "负控变异注入失败 —— 锚点没命中,这条负控是假的");
    }
}
