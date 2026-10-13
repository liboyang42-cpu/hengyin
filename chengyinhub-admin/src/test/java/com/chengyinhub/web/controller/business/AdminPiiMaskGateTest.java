package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.domain.ViewOrder;
import com.chengyinhub.business.security.AdminPiiProtector;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IOmsOrderItemService;
import com.chengyinhub.business.service.IOmsOrderService;
import com.chengyinhub.business.service.IViewOrderService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.framework.security.context.PermissionContextHolder;
import com.chengyinhub.framework.web.service.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台 PII 脱敏闸门。
 *
 * <p>为什么必须在后端断言、而不是在 Vue 模板里看:PiiCryptoTypeHandler 在 ORM 边界就把密文解成明文,
 * Controller 直接把 entity 序列化出去。只在模板里掐字符的话,明文照样躺在 /list 的 JSON 与 /export 的
 * xlsx 里,DevTools 一开就有。所以断言的对象是「出后端的那个对象」。
 *
 * <p>每条断言都配了负控(见 test_负控_*):把闸门摘掉必须变红,否则这测试是假绿。
 */
class AdminPiiMaskGateTest {

    private static final String REAL_NAME = "张三丰";
    private static final String REAL_PHONE = "13812341234";
    private static final String REAL_EMAIL = "zhangsan@example.com";
    private static final String REAL_ADDRESS = "北京市朝阳区建国路88号";

    private static final String MASKED_NAME = "张*";   // 口径已统一为「只保留首字」,不暴露姓名长度
    private static final String MASKED_PHONE = "138****1234";
    private static final String MASKED_EMAIL = "z****@example.com";
    private static final String MASKED_ADDRESS = "北京市朝阳区****";

    private ICmsRegistrationService registrationService;
    private CmsRegistrationController registrationController;

    @BeforeEach
    void setUp() {
        // startPage() 经 ServletUtils 读请求参数,没有绑定请求会 NPE
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        registrationService = mock(ICmsRegistrationService.class);
        registrationController = new CmsRegistrationController();
        ReflectionTestUtils.setField(registrationController, "cmsRegistrationService", registrationService);
    }

    /** 装一个带指定权限集的登录态。闸门直接读它,故这里不打桩闸门本身,测的是真判定。 */
    private void loginWith(String... permissions) {
        SysUser user = new SysUser();
        user.setUserId(1L);
        LoginUser loginUser = new LoginUser(user, new HashSet<>(Arrays.asList(permissions)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private CmsRegistration plaintextRegistration() {
        CmsRegistration r = new CmsRegistration();
        r.setId(1L);
        r.setRealName(REAL_NAME);
        r.setPhone(REAL_PHONE);
        r.setEmail(REAL_EMAIL);
        r.setAddress(REAL_ADDRESS);
        return r;
    }

    /**
     * 授/不授「看全量」权限。不授时仍给一个别的权限,以免测出来的绿是「权限集为空」这种
     * 与闸门无关的原因——那样闸门读错权限名也照样绿。
     */
    private void grantPii(boolean granted) {
        loginWith(granted ? "business:registration:pii" : "business:registration:list");
    }

    // ---------- 列表 ----------

    @Test
    void 无权限_列表出后端时已脱敏() {
        CmsRegistration row = plaintextRegistration();
        when(registrationService.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(new ArrayList<>(Collections.singletonList(row)));
        grantPii(false);

        registrationController.list(new CmsRegistration());

        assertEquals(MASKED_NAME, row.getRealName());
        assertEquals(MASKED_PHONE, row.getPhone());
        assertEquals(MASKED_EMAIL, row.getEmail());
        assertEquals(MASKED_ADDRESS, row.getAddress());
    }

    @Test
    void 有权限_列表仍是全量() {
        CmsRegistration row = plaintextRegistration();
        when(registrationService.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(new ArrayList<>(Collections.singletonList(row)));
        grantPii(true);

        registrationController.list(new CmsRegistration());

        assertEquals(REAL_NAME, row.getRealName());
        assertEquals(REAL_PHONE, row.getPhone());
    }

    /**
     * 反推口:脱敏只遮了手机中间四位,若还能按 phone 精确检索,枚举 0000-9999 最多 1 万次就能还原真号
     * (mapper 第 81 行 phone_hash 盲索引精确匹配)。故无权限者的检索条件里 PII 必须先被清掉。
     */
    @Test
    void 无权限_不能按手机检索_否则脱敏可被枚举反推() {
        when(registrationService.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(new ArrayList<CmsRegistration>());
        grantPii(false);

        CmsRegistration probe = new CmsRegistration();
        probe.setPhone(REAL_PHONE);
        probe.setRegistrationNo("BM001");

        registrationController.list(probe);

        verify(registrationService).selectCmsRegistrationList(argThat(q ->
                q.getPhone() == null
                        && q.getRealName() == null
                        && q.getEmail() == null
                        && q.getAddress() == null
                        // 非 PII 检索条件必须保留,否则闸门顺手废掉了搜索功能
                        && "BM001".equals(q.getRegistrationNo())));
    }

    @Test
    void 有权限_仍可按手机检索() {
        when(registrationService.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(new ArrayList<CmsRegistration>());
        grantPii(true);

        CmsRegistration probe = new CmsRegistration();
        probe.setPhone(REAL_PHONE);

        registrationController.list(probe);

        verify(registrationService).selectCmsRegistrationList(argThat(q -> REAL_PHONE.equals(q.getPhone())));
    }

    @Test
    void 无权限_导出也不能按手机检索() {
        when(registrationService.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(new ArrayList<CmsRegistration>());
        grantPii(false);

        CmsRegistration probe = new CmsRegistration();
        probe.setPhone(REAL_PHONE);

        registrationController.export(new MockHttpServletResponse(), probe);

        verify(registrationService).selectCmsRegistrationList(argThat(q -> q.getPhone() == null));
    }

    // ---------- 详情 ----------

    @Test
    void 无权限_详情出后端时已脱敏() {
        CmsRegistration row = plaintextRegistration();
        when(registrationService.selectCmsRegistrationById(1L)).thenReturn(row);
        grantPii(false);

        registrationController.getInfo(1L);

        assertEquals(MASKED_PHONE, row.getPhone());
        assertEquals(MASKED_NAME, row.getRealName());
    }

    // ---------- 导出:真正的批量外泄口 ----------

    /**
     * 不吞异常:sheet 名的 '/' 已修(见 ExcelUtil.init 的 createSafeSheetName + 本 controller 改名),
     * 导出必须真的跑通。若这里再抛,说明 500 又回来了。
     */
    @Test
    void 无权限_导出的数据在交给ExcelUtil之前已脱敏() {
        CmsRegistration row = plaintextRegistration();
        when(registrationService.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(new ArrayList<>(Collections.singletonList(row)));
        grantPii(false);

        registrationController.export(new MockHttpServletResponse(), new CmsRegistration());

        // 断言那个要被写进 xlsx 的对象本身已被脱敏 —— xlsx 的内容就来自它
        assertEquals(MASKED_NAME, row.getRealName());
        assertEquals(MASKED_PHONE, row.getPhone());
        assertEquals(MASKED_ADDRESS, row.getAddress());
    }

    // ---------- 编辑回写:数据毁灭风险 ----------

    @Test
    void 无权限_编辑不会把脱敏串写回真实字段() {
        grantPii(false);
        CmsRegistration submitted = new CmsRegistration();
        submitted.setId(1L);
        submitted.setRealName(MASKED_NAME);
        submitted.setPhone(MASKED_PHONE);
        submitted.setEmail(MASKED_EMAIL);
        submitted.setAddress(MASKED_ADDRESS);
        submitted.setTicketName("不受影响的普通字段");

        registrationController.edit(submitted);

        // PII 置 null → updateCmsRegistration 的动态 <if> 跳过这些列 → 库里原值不动
        verify(registrationService).updateCmsRegistration(argThat(r ->
                r.getRealName() == null
                        && r.getPhone() == null
                        && r.getEmail() == null
                        && r.getAddress() == null
                        // 非 PII 字段必须照常提交,否则闸门顺手废掉了编辑功能
                        && "不受影响的普通字段".equals(r.getTicketName())));
    }

    @Test
    void 有权限_编辑可以正常改PII() {
        grantPii(true);
        CmsRegistration submitted = plaintextRegistration();

        registrationController.edit(submitted);

        verify(registrationService).updateCmsRegistration(argThat(r ->
                REAL_PHONE.equals(r.getPhone()) && REAL_NAME.equals(r.getRealName())));
    }

    // ---------- 超管 *:*:* ----------

    /**
     * 我方对用户的承诺是「超管仍看全量,故验收必须用非超管角色」。这条端到端跑真闸门验证该承诺:
     * 只装一个 *:*:*(并没有 business:registration:pii),列表仍须是全量。
     */
    @Test
    void 超管的星号权限天然匹配pii权限位_列表仍是全量() {
        CmsRegistration row = plaintextRegistration();
        when(registrationService.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(new ArrayList<>(Collections.singletonList(row)));
        loginWith("*:*:*");

        registrationController.list(new CmsRegistration());

        assertEquals(REAL_PHONE, row.getPhone(),
                "超管持 *:*:* 应看到全量;若此处变红,「验收必须用非超管角色」的结论不成立,口径要改");
    }

    /**
     * 本条是「闸门改为自己读权限集」的正主:证明副作用真的没了。
     *
     * <p>PermissionService.hasPermi 会顺手 PermissionContextHolder.setContext(权限串)。
     * 请求链路是:@PreAuthorize 先设 context = business:registration:list,
     * 然后闸门若调 hasPermi 就把它覆盖成 business:registration:pii;
     * 而 DataScopeAspect(第 76 行)在 @DataScope 没写 permission 时正是回退读这个 context 做数据范围过滤
     * ——于是数据范围会按错的权限串算。今天业务 service 上没有 @DataScope 所以够不着,
     * 这条钉住的是「别等哪天有人加了 @DataScope 才发现」。
     */
    @Test
    void 闸门不再污染DataScope依赖的权限context() {
        PermissionContextHolder.setContext("business:registration:list");   // 模拟 @PreAuthorize 刚设好
        CmsRegistration row = plaintextRegistration();
        when(registrationService.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(new ArrayList<>(Collections.singletonList(row)));
        grantPii(false);

        registrationController.list(new CmsRegistration());

        assertEquals("business:registration:list", PermissionContextHolder.getContext(),
                "闸门不得覆盖 @PreAuthorize 设的 context,否则 DataScopeAspect 会按错的权限串算数据范围");
    }

    /**
     * 闸门不再调 PermissionService.hasPermi(它会污染 DataScope 的 PermissionContextHolder),
     * 改为自己读权限集判定。这条交叉钉住两边语义一致:任一权限组合下,闸门的结论必须等于框架的结论。
     * 将来谁改了框架的匹配规则(比如支持 business:*:* 通配),这条会红,而不是让后台静默漏明文。
     */
    @Test
    void canViewPii与框架hasPermi语义一致() {
        String perm = "business:registration:pii";
        String[][] matrix = {
                {"*:*:*"},
                {perm},
                {"business:registration:list"},
                {"business:registration:list", perm},
                {"business:order:pii"},
                {},
        };
        PermissionService framework = new PermissionService();
        for (String[] perms : matrix) {
            loginWith(perms);
            boolean mine = Boolean.TRUE.equals(
                    ReflectionTestUtils.invokeMethod(registrationController, "canViewPii"));
            boolean theirs = framework.hasPermi(perm);
            assertEquals(theirs, mine,
                    "权限集 " + Arrays.toString(perms) + " 下,闸门与框架 hasPermi 结论必须一致");
        }
    }

    // ---------- 掩码本身 ----------

    @Test
    void 邮箱与地址不能用maskMiddle_它保留尾部() {
        // maskMiddle 保尾:地址尾部是门牌号、邮箱尾部是域名+末字符,遮不住人,故各自单写
        assertEquals(MASKED_EMAIL, AdminPiiProtector.maskEmail(REAL_EMAIL));
        assertEquals(MASKED_ADDRESS, AdminPiiProtector.maskAddress(REAL_ADDRESS));
        assertTrue(AdminPiiProtector.maskAddress(REAL_ADDRESS).indexOf("88号") < 0, "门牌号必须被遮掉");
    }

    @Test
    void 掩码对空值与畸形值不炸() {
        assertNull(AdminPiiProtector.maskEmail(null));
        assertNull(AdminPiiProtector.maskAddress(null));
        assertNotNull(AdminPiiProtector.maskEmail("没有at符号"));
        assertEquals("****", AdminPiiProtector.maskAddress("短地址"));
        // 不像邮箱也绝不原样返回
        assertTrue(!"没有at符号".equals(AdminPiiProtector.maskEmail("没有at符号")));
    }

    @Test
    void 空对象不炸() {
        AdminPiiProtector.maskInPlace((CmsRegistration) null);
        AdminPiiProtector.maskInPlace((ViewOrder) null);
        AdminPiiProtector.maskInPlace((OmsOrder) null);
        AdminPiiProtector.stripPii((CmsRegistration) null);
        AdminPiiProtector.stripPii((OmsOrder) null);
    }

    // ---------- 订单 ----------

    @Test
    void 无权限_订单列表脱敏但不动昵称() {
        IViewOrderService viewOrderService = mock(IViewOrderService.class);
        OmsOrderController controller = new OmsOrderController();
        ReflectionTestUtils.setField(controller, "viewOrderService", viewOrderService);
        ReflectionTestUtils.setField(controller, "omsOrderService", mock(IOmsOrderService.class));
        ReflectionTestUtils.setField(controller, "omsOrderItemService", mock(IOmsOrderItemService.class));

        ViewOrder order = new ViewOrder();
        order.setReceiverName(REAL_NAME);
        order.setReceiverPhone(REAL_PHONE);
        order.setReceiverDetailAddress(REAL_ADDRESS);
        order.setPhone(REAL_PHONE);
        order.setNickname("阿三");
        List<ViewOrder> rows = new ArrayList<>(Collections.singletonList(order));
        when(viewOrderService.selectViewOrderList(any(ViewOrder.class))).thenReturn(rows);
        loginWith("business:order:list");   // 有列表权、无 order:pii

        controller.list(new ViewOrder());

        assertEquals(MASKED_NAME, order.getReceiverName());
        assertEquals(MASKED_PHONE, order.getReceiverPhone());
        assertEquals(MASKED_ADDRESS, order.getReceiverDetailAddress());
        assertEquals(MASKED_PHONE, order.getPhone());
        // 昵称是用户自选展示名、小程序里本就公开,遮了只会让后台认不出人
        assertEquals("阿三", order.getNickname());
    }

    /** 不存在的 id 不该把端点打成 500:原先 selectViewOrderById 返回 null 后直接 order.getId() → NPE。 */
    @Test
    void 订单详情_id不存在时返回业务错误而不是NPE() {
        IViewOrderService viewOrderService = mock(IViewOrderService.class);
        OmsOrderController controller = new OmsOrderController();
        ReflectionTestUtils.setField(controller, "viewOrderService", viewOrderService);
        ReflectionTestUtils.setField(controller, "omsOrderService", mock(IOmsOrderService.class));
        ReflectionTestUtils.setField(controller, "omsOrderItemService", mock(IOmsOrderItemService.class));
        loginWith("business:order:list");
        when(viewOrderService.selectViewOrderById(9999999L)).thenReturn(null);

        AjaxResult result = controller.getInfo(9999999L);

        assertEquals(500, result.get("code"), "应是业务错误返回,而不是抛 NPE 打成 500 页面");
        assertEquals("订单不存在", result.get("msg"));
    }

    private static <T> T any(Class<T> type) {
        return org.mockito.ArgumentMatchers.any(type);
    }
}
