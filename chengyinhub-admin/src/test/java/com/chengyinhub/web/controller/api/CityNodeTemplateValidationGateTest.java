package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 据点互动模板「配得起来吗」这道闸。
 *
 * 为什么闸必须在服务端:小程序表单也拦一道,但那只是体验 —— 换个客户端就能绕过。
 * 配不全落了库,玩家侧只会表现成「怎么答都不对」,商家还看不出哪里错了 ——
 * 这正是 2026-08-06 修的那个 bug(表单没有选项输入框 ⇒ 永远提交不出 correctAnswer)。
 *
 * ⚠️ 负控已实测:注释掉 templateSubmit 里那两行 `validationMethodError` 调用 ⇒ 本类不受影响
 * (它直接测判定函数),但把判定函数改成恒返 null ⇒ 下面 5 条转红。
 */
class CityNodeTemplateValidationGateTest {

    /** 判定是私有的(它只服务这一个入口,不该为了测试提升可见性)。 */
    private String check(CmsMemberTemplate t) throws Exception {
        Method m = ApiMerchantNodeController.class
                .getDeclaredMethod("validationMethodError", CmsMemberTemplate.class);
        m.setAccessible(true);
        return (String) m.invoke(new ApiMerchantNodeController(), t);
    }

    private CmsMemberTemplate tpl(Integer vm) {
        CmsMemberTemplate t = new CmsMemberTemplate();
        t.setTitle("说出今日暗号");
        t.setValidationMethod(vm);
        return t;
    }

    @Test
    @Timeout(5)
    void textPassphrase_withAnswer_ok() throws Exception {
        CmsMemberTemplate t = tpl(1);
        t.setQuestionAnswer("夜航船");
        assertNull(check(t));
    }

    /** ★负控:vm=1 没答案 —— 后端没有可比的期望值,玩家永远过不了。 */
    @Test
    @Timeout(5)
    void textPassphrase_withoutAnswer_rejected() throws Exception {
        assertNotNull(check(tpl(1)), "文字暗号不填答案就该在提交时拦住");
    }

    @Test
    @Timeout(5)
    void choice_wellFormed_ok() throws Exception {
        CmsMemberTemplate t = tpl(3);
        t.setQuestionName("今天的限定造型是什么口味");
        t.setQuestionA("红豆");
        t.setQuestionB("抹茶");
        t.setCorrectAnswer("B");
        assertNull(check(t));
    }

    /** ★负控:这正是被修的原 bug —— 表单提交不出 correctAnswer,后端拿 null 去比。 */
    @Test
    @Timeout(5)
    void choice_missingCorrectAnswer_rejected() throws Exception {
        CmsMemberTemplate t = tpl(3);
        t.setQuestionName("题");
        t.setQuestionA("红豆");
        t.setQuestionB("抹茶");
        assertNotNull(check(t), "没有正确答案 = 玩家怎么答都错,必须在落库前拦住");
    }

    /** ★负控:正确项指向一个空选项,等于没有答案 —— 只查「填了 A-D」是不够的。 */
    @Test
    @Timeout(5)
    void choice_correctAnswerPointsAtEmptyOption_rejected() throws Exception {
        CmsMemberTemplate t = tpl(3);
        t.setQuestionName("题");
        t.setQuestionA("红豆");
        t.setQuestionB("抹茶");
        t.setCorrectAnswer("D");     // D 是空的
        assertNotNull(check(t), "正确答案指到空选项,玩家选不到它");
    }

    /** ★负控:只有一个选项不成其为选择题。 */
    @Test
    @Timeout(5)
    void choice_singleOption_rejected() throws Exception {
        CmsMemberTemplate t = tpl(3);
        t.setQuestionName("题");
        t.setQuestionA("红豆");
        t.setCorrectAnswer("A");
        assertNotNull(check(t));
    }

    /** ★负控:题面缺失时玩家只看到四个选项,不知道在问什么。 */
    @Test
    @Timeout(5)
    void choice_missingQuestion_rejected() throws Exception {
        CmsMemberTemplate t = tpl(3);
        t.setQuestionA("红豆");
        t.setQuestionB("抹茶");
        t.setCorrectAnswer("A");
        assertNotNull(check(t));
    }

    /** 大小写与空格不该卡住商家:'b' 和 ' B ' 都算 B。 */
    @Test
    @Timeout(5)
    void choice_correctAnswerCaseAndSpaceTolerant() throws Exception {
        for (String key : new String[] { "b", " B ", "B" }) {
            CmsMemberTemplate t = tpl(3);
            t.setQuestionName("题");
            t.setQuestionA("红豆");
            t.setQuestionB("抹茶");
            t.setCorrectAnswer(key);
            assertNull(check(t), "correctAnswer=[" + key + "] 应被接受");
        }
    }

    /** 2/4/5 靠照片、张贴码、GPS 判,不需要答案 —— 别顺手把它们也拦了。 */
    @Test
    @Timeout(5)
    void photoScanGps_needNoAnswer() throws Exception {
        for (Integer vm : new Integer[] { 2, 4, 5 }) {
            assertNull(check(tpl(vm)), "vm=" + vm + " 不该要求答案");
        }
    }

    @Test
    @Timeout(5)
    void preferenceGroup_requiresACompleteValidatedContract() throws Exception {
        CmsMemberTemplate valid = tpl(6);
        valid.setPreferenceJson("{\"steps\":[{\"key\":\"q1\",\"type\":\"single\",\"title\":\"选一个\","
                + "\"options\":[{\"key\":\"A\",\"text\":\"安静\",\"scores\":{\"quiet\":2}},"
                + "{\"key\":\"B\",\"text\":\"热闹\",\"scores\":{\"quiet\":1}}]}],"
                + "\"dimensions\":[\"quiet\"],\"results\":{\"quiet\":{\"title\":\"安静型\","
                + "\"body\":\"因为你选择了{{choices}}\",\"nextStep\":\"试一次\",\"nextStepDays\":7}},"
                + "\"tagOutput\":{\"code\":\"space_constraints\",\"fromDimension\":true,"
                + "\"purpose\":\"减少重复提问\",\"recipientLabel\":\"本主题下一站\",\"revocable\":true}}"
        );
        assertNull(check(valid));

        CmsMemberTemplate invalid = tpl(6);
        invalid.setPreferenceJson("{}");
        assertEquals("偏好题缺少维度", check(invalid));
    }

    /** 不传验证方式的老调用按原样放行,这道闸不在这里收紧存量。 */
    @Test
    @Timeout(5)
    void nullValidationMethod_passes() throws Exception {
        assertEquals(null, check(tpl(null)));
    }
}
