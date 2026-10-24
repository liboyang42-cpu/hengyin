package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.vo.PublicTemplateListItemVO;
import com.chengyinhub.business.service.ICmsTemplateLibraryService;
import com.github.pagehelper.PageHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模板市场的形态筛选轴(pack_type)。
 *
 * <p><b>★★ 这组断言的核心是一条判断:「认不出的筛选值,按不过滤还是按 0?」</b>
 * 答案是<b>不过滤</b>。按 0 会让人以为「筛选生效了、这个形态下就这么几个」,
 * 而他实际看到的是另一个形态的列表 —— 那比明显的报错更坏,因为看起来一切正常。
 *
 * <p>另一条:<b>缺省归一在服务端做。</b>老数据 pack_type 为 null 时后端就回 0,
 * 客户端不必自己再兜一次 —— 两端各兜一次,迟早有一端兜成别的值。
 */
class TemplatePackTypeTest {

    private ICmsTemplateLibraryService libraryService;
    private ApiTemplateController controller;

    @BeforeEach
    void setUp() {
        // commonTemplateList 的 startPage() 会读 servlet 请求参数,给个空请求上下文。
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        libraryService = mock(ICmsTemplateLibraryService.class);
        when(libraryService.selectCmsTemplateLibraryList(any())).thenReturn(Collections.emptyList());
        controller = new ApiTemplateController();
        ReflectionTestUtils.setField(controller, "templateLibraryService", libraryService);
    }

    @AfterEach
    void tearDown() {
        PageHelper.clearPage();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void parsesTheThreeDefinedPackTypes() {
        assertThat(ApiTemplateController.parsePackType("0")).isEqualTo(0);
        assertThat(ApiTemplateController.parsePackType("1")).isEqualTo(1);
        assertThat(ApiTemplateController.parsePackType("2")).isEqualTo(2);
        assertThat(ApiTemplateController.parsePackType(" 2 ")).isEqualTo(2);
    }

    /** ★ 不传 = 不过滤(看全部形态),不是「只看单节点玩法」。 */
    @Test
    void absentMeansNoFilter() {
        assertThat(ApiTemplateController.parsePackType(null)).isNull();
        assertThat(ApiTemplateController.parsePackType("")).isNull();
        assertThat(ApiTemplateController.parsePackType("   ")).isNull();
    }

    /**
     * ★★ 认不出的值按**不过滤**,不按 0。
     *
     * <p>红的场景:有人把 parsePackType 的兜底从 null 改成 0。那时前端传错、
     * 或将来加了形态 3 而后端还没上,用户会看到一个「已筛选」的列表,
     * 里面其实是单节点玩法 —— 看起来一切正常,所以永远不会有人报这个 bug。
     */
    @Test
    void unknownValueMeansNoFilterNotZero() {
        for (final String raw : new String[] {"3", "-1", "99", "abc", "1.5", "0x1"}) {
            assertThat(ApiTemplateController.parsePackType(raw))
                    .as("认不出的形态值 %s 必须落不过滤(null),不能落 0", raw)
                    .isNull();
        }
    }

    /** C:U-039/U-148:认不出的值仍不过滤,但要能被识别出来留告警;不传/空白不算异常。 */
    @Test
    void unrecognizedPackTypeIsDetectedForWarningButAbsentIsNot() {
        for (final String raw : new String[] {"3", "-1", "99", "abc", "1.5", "0x1", "３"}) {
            assertThat(ApiTemplateController.isUnrecognizedPackType(raw)).as(raw).isTrue();
        }
        for (final String raw : new String[] {null, "", "   ", "0", "1", "2", " 2 ", "０"}) {
            assertThat(ApiTemplateController.isUnrecognizedPackType(raw)).as(String.valueOf(raw)).isFalse();
        }
    }

    /**
     * 全角数字按半角解析。
     *
     * <p>★ 这不是「宽容到出错」——Java 的 {@code Integer.parseInt} 走 {@code Character.digit},
     * 本来就认全角数字。中文输入法下打出「０」与「0」是同一个意思,判成非法反而是错的。
     * 写这条是为了让**下一个人知道这是已知且刻意的**,而不是看到它以为漏了校验。
     */
    @Test
    void acceptsFullWidthDigits() {
        assertThat(ApiTemplateController.parsePackType("０")).isEqualTo(0);
        assertThat(ApiTemplateController.parsePackType("２")).isEqualTo(2);
        assertThat(ApiTemplateController.parsePackType("３"))
                .as("全角也要走同一条范围校验,3 仍然越界", (Object[]) null)
                .isNull();
    }

    /** 缺省归一在服务端做:null → 0。客户端不必再兜一次。 */
    @Test
    void projectionNormalizesNullsOnServer() {
        CmsTemplateLibrary source = new CmsTemplateLibrary();
        source.setId(1L);
        source.setTitle("隐藏菜单");
        // packType / useNum 都不设 —— 模拟迁移前就存在的老行

        PublicTemplateListItemVO vo = PublicTemplateListItemVO.from(source);

        assertThat(vo.getPackType()).isZero();
        assertThat(vo.getUseNum()).isZero();
    }

    @Test
    void projectionCarriesPackTypeAndUseCount() {
        CmsTemplateLibrary source = new CmsTemplateLibrary();
        source.setId(2L);
        source.setTitle("店铺 IP 包");
        source.setPackType(2);
        source.setUseNum(37);

        PublicTemplateListItemVO vo = PublicTemplateListItemVO.from(source);

        assertThat(vo.getPackType()).isEqualTo(2);
        assertThat(vo.getUseNum()).isEqualTo(37);
    }

    /**
     * ★ 列表投影仍然是**最小投影**:详情与编辑字段不得从列表透出。
     *
     * <p>本批只加了 packType / useNum 两个展示字段。这条钉住的是
     * 「以后有人顺手把答案、正确项加进来」—— 那等于把玩法答案发给所有人。
     */
    @Test
    void projectionStaysMinimal() {
        CmsTemplateLibrary source = new CmsTemplateLibrary();
        source.setId(3L);
        source.setQuestionAnswer("答案就是这个");
        source.setCorrectAnswer("B");
        source.setFeedbackText("完成反馈");

        PublicTemplateListItemVO vo = PublicTemplateListItemVO.from(source);

        assertThat(vo)
                .extracting(PublicTemplateListItemVO::getId,
                        PublicTemplateListItemVO::getTitle,
                        PublicTemplateListItemVO::getPackType,
                        PublicTemplateListItemVO::getUseNum)
                .containsExactly(3L, null, 0, 0);
        // 没有 getter 就发不出去 —— 用反射钉住「没多出别的读方法」。
        assertThat(PublicTemplateListItemVO.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .filteredOn((String name) -> name.startsWith("get"))
                .containsExactlyInAnyOrder(
                        "getId", "getTitle", "getDescription", "getImgUrl",
                        "getPlayers", "getDuration", "getActivityCategoryids",
                        "getPackType", "getUseNum");
    }

    /**
     * ★ 真实走一次 {@link ApiTemplateController#commonTemplateList}:0/1/2 必须落到
     * service 查询对象的 packType 上。
     *
     * <p>为什么不能只测 {@code parsePackType}:解析对了、但忘了把它设进 query(或设成了
     * 别的字段),列表照样返回全部模板 —— 用户以为筛选生效,其实没有。这条钉住的是
     * 「解析结果真的传到了查询边界」,而不是解析函数本身。
     */
    @Test
    void commonTemplateListWiresRecognizedPackTypeIntoTheQuery() {
        controller.commonTemplateList(null, null, null, "0");
        controller.commonTemplateList(null, null, null, "1");
        controller.commonTemplateList(null, null, null, "2");
        controller.commonTemplateList(null, null, null, " 2 ");

        ArgumentCaptor<CmsTemplateLibrary> query = ArgumentCaptor.forClass(CmsTemplateLibrary.class);
        verify(libraryService, times(4)).selectCmsTemplateLibraryList(query.capture());
        assertThat(query.getAllValues())
                .extracting(CmsTemplateLibrary::getPackType)
                .containsExactly(0, 1, 2, 2);
    }

    /**
     * ★★ 请求边界:缺省与认不出的形态值必须**不设过滤**(query.packType 为 null),
     * 而不是落 0。
     *
     * <p>落 0 的坏处是静默的:用户看到的是一个「已筛选」的列表,里面其实是单节点玩法,
     * 看起来一切正常,所以永远不会有人报这个 bug。这条直接断言查询对象上没被设值。
     */
    @Test
    void commonTemplateListDropsAbsentOrUnknownPackTypeInsteadOfFilteringZero() {
        String[] noFilter = {null, "", "   ", "3", "-1", "99", "abc", "1.5", "0x1"};
        for (String raw : noFilter) {
            controller.commonTemplateList(null, null, null, raw);
        }

        ArgumentCaptor<CmsTemplateLibrary> query = ArgumentCaptor.forClass(CmsTemplateLibrary.class);
        verify(libraryService, times(noFilter.length)).selectCmsTemplateLibraryList(query.capture());
        assertThat(query.getAllValues())
                .as("缺省或认不出的形态值必须不设过滤(query.packType 为 null),不能落 0")
                .allSatisfy(row -> assertThat(row.getPackType()).isNull());
    }
}
