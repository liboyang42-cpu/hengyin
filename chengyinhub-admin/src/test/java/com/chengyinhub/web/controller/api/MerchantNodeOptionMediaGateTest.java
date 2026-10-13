package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.util.QuestionOptionMedia;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 选项媒体有【两条】写入路径，两条都得过闸：
 * <ol>
 *   <li>模板发布 {@code TemplatePublishServiceImpl}（已由 TemplatePublishQuestionMediaTest 覆盖）</li>
 *   <li>商家据点 {@code /api/merchant/city-node/template/submit} —— 收整实体后
 *       直接 insert/update，不经发布链路</li>
 * </ol>
 *
 * <p>★ 只在第 1 条上设闸，第 2 条会把畸形 JSON 直接写进 varchar(2000)，
 * 读出端 {@code parse} 再静默丢 —— 正是「闸放错层」那类：逻辑对、入口绕开、测试全绿。
 * 小程序 {@code pages/publish/temp} 的 {@code toNodeTemplate} 是 {@code Object.assign}
 * 整体转发，新字段会原样跟着走这条路。
 *
 * <p>本测试是静态的：断言 controller 源码在落库前调了 sanitize。行为测要起 Spring 上下文
 * 和一堆 mock，成本远高于它能多抓到的东西；但静态匹配挡不住「调了却没把返回值写回 body」，
 * 所以下面连 setter 回写一起断言。
 */
class MerchantNodeOptionMediaGateTest
{
    private static final Path CONTROLLER = Paths.get(
            "src/main/java/com/chengyinhub/web/controller/api/ApiMerchantNodeController.java");

    private String source() throws IOException
    {
        return new String(Files.readAllBytes(CONTROLLER), StandardCharsets.UTF_8);
    }

    @Test
    void merchantNodeSubmitSanitizesOptionMediaBeforePersisting() throws IOException
    {
        String src = source();

        assertTrue(src.contains("QuestionOptionMedia.sanitize(body.getQuestionOptionMediaJson())"),
                "商家据点提交入口必须过 sanitize —— 否则畸形选项媒体直接落库");
        assertTrue(src.contains("body.setQuestionOptionMediaJson("),
                "光调用不算数：归一后的值必须写回 body，否则落库的还是原始脏值");

        int gate = src.indexOf("QuestionOptionMedia.sanitize");
        int save = src.indexOf("templatePublishService.saveMerchantNodeTemplate(body");
        assertTrue(gate > 0 && save > gate, "闸必须在事务保存入口之前");
    }

    /**
     * 该入口的错误口径是 {@code error(msg)}；让 IllegalArgumentException 冒出去
     * 会变成 500，商家只看到「服务器错误」，不知道是哪个选项的媒体不合法。
     */
    @Test
    void gateReportsThroughErrorInsteadOfLettingItBecomeA500() throws IOException
    {
        String src = source();
        int gate = src.indexOf("QuestionOptionMedia.sanitize");
        String after = src.substring(gate, Math.min(src.length(), gate + 400));

        assertTrue(after.contains("catch (IllegalArgumentException e)"),
                "sanitize 会抛 IllegalArgumentException，本入口必须接住");
        assertTrue(after.contains("return error(e.getMessage())"),
                "接住之后要按本入口的口径回话，而不是吞掉");
    }

    /** 顺带钉住 sanitize 确实会对越界字母抛 —— 上面的静态断言才有意义。 */
    @Test
    void sanitizeActuallyThrowsSoTheCatchIsNotDeadCode()
    {
        assertNotNull(assertThrows(IllegalArgumentException.class,
                () -> QuestionOptionMedia.sanitize("{\"E\":{\"img\":\"https://cdn/e.jpg\"}}")));
    }
}
