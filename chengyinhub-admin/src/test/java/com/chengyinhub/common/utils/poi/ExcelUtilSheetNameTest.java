package com.chengyinhub.common.utils.poi;

import com.chengyinhub.common.annotation.Excel;
import org.apache.poi.ss.util.WorkbookUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * sheet 名的非法字符不能再把导出打成 500。
 *
 * <p>病史:POI 的 setSheetName 拒收 / \ ? * [ ] :,直接抛 IllegalArgumentException。仓库里一度有 4 处
 * sheet 名含 '/'(活动/主题报名数据、主题/活动浏览记录数据、主题/活动票务数据、活动/主题合作者数据),
 * 这 4 个导出端点在生产上就是 500——报名那个的导出按钮在前端被注释掉了,和这个症状对得上。
 *
 * <p>4 次中招 = 模式,不是假想,所以收口在 ExcelUtil.init(POI 自带的 createSafeSheetName),
 * 而不是逐个改字符串了事。本用例钉住的是「机制」:随便谁再写个带 '/' 的 sheet 名,也不该 500。
 */
class ExcelUtilSheetNameTest {

    /** ExcelUtil 只导出带 @Excel 的字段,故 fixture 需要一个。 */
    public static class Row {
        @Excel(name = "字段")
        private String field;

        public String getField() {
            return field;
        }

        public void setField(String field) {
            this.field = field;
        }
    }

    private List<Row> oneRow() {
        Row r = new Row();
        r.setField("v");
        return new ArrayList<>(Collections.singletonList(r));
    }

    @Test
    void sheet名含斜杠不再抛异常() {
        assertDoesNotThrow(() -> new ExcelUtil<>(Row.class)
                .exportExcel(new MockHttpServletResponse(), oneRow(), "活动/主题报名数据"));
    }

    /** 历史上真实存在过的 4 个名字,逐个跑通;改名后它们已不含 '/',这里防的是有人改回去。 */
    @Test
    void 四个历史sheet名全部导得出来() {
        for (String name : new String[]{
                "活动/主题报名数据", "主题/活动浏览记录数据", "主题/活动票务数据", "活动/主题合作者数据",
                "活动、主题报名数据", "主题、活动浏览记录数据", "主题、活动票务数据", "活动、主题合作者数据"}) {
            assertDoesNotThrow(() -> new ExcelUtil<>(Row.class)
                    .exportExcel(new MockHttpServletResponse(), oneRow(), name), name + " 必须能导出");
        }
    }

    /** 现用的 4 个名字必须本来就合法,别依赖 sanitizer 兜底(兜底会塞空格,看着像手滑)。 */
    @Test
    void 现用的四个名字本身就是POI合法名() {
        for (String name : new String[]{
                "活动、主题报名数据", "主题、活动浏览记录数据", "主题、活动票务数据", "活动、主题合作者数据"}) {
            assertDoesNotThrow(() -> WorkbookUtil.validateSheetName(name), name + " 应无需 sanitizer 兜底");
        }
    }

    @Test
    void 其余非法字符同样被兜住() {
        for (String name : new String[]{"a\\b", "a?b", "a*b", "a[b]", "a:b"}) {
            assertDoesNotThrow(() -> new ExcelUtil<>(Row.class)
                    .exportExcel(new MockHttpServletResponse(), oneRow(), name), name + " 必须能导出");
        }
    }

    /** 合法名不该被 sanitizer 改动,否则等于悄悄改了全站几十个导出的页签名。 */
    @Test
    void 合法名原样通过_不影响既有导出() {
        ExcelUtil<Row> util = new ExcelUtil<>(Row.class);
        util.init(oneRow(), "用户提现申请数据", "", Excel.Type.EXPORT);
        assertTrue(sheetNameOf(util).equals("用户提现申请数据"),
                "合法 sheet 名必须原样保留,实际=" + sheetNameOf(util));
    }

    /**
     * isEmpty 那道守卫不是防御性冗余,它守的是「静默错」而不是「崩」:
     * 实测 POI 的 createSafeSheetName(null) 返回**字符串** "null"、("") 返回 "empty" ——
     * 放行给它,空/null 的 sheet 名会凭空变出一个叫 null / empty 的页签,还没人会发现。
     * 有守卫时空名照旧当场抛(响,能查);摘掉守卫这两条就不抛了,于是变红。
     *
     * <p>没把空名一并"修好"是故意的:全仓没有任何调用点传空名(全是字面量),
     * 给不可能的入参写兜底属于多余代码;这里只钉住「不许静默改名」。
     */
    @Test
    void 空sheet名必须当场抛_而不是被POI悄悄改叫null或empty() {
        assertThrows(Exception.class, () ->
                        new ExcelUtil<>(Row.class).init(oneRow(), "", "", Excel.Type.EXPORT),
                "空串必须抛,而不是产出一个叫 \"empty\" 的页签");

        assertThrows(Exception.class, () ->
                        new ExcelUtil<>(Row.class).init(oneRow(), null, "", Excel.Type.EXPORT),
                "null 必须抛,而不是产出一个叫 \"null\" 的页签");
    }

    private String sheetNameOf(ExcelUtil<Row> util) {
        return (String) org.springframework.test.util.ReflectionTestUtils.getField(util, "sheetName");
    }
}
