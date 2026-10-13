package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.RoleEntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

/**
 * 验收场景 #22 · 二清硬闸 —— <b>行为测试,必须真的把方法调进去</b>。
 *
 * <p><b>闸在管什么</b>:平台把玩家的钱收进自己的商户号,再从库里记账分给第三方主理人 = 二清
 * (F8;判例:非法经营罪 8 年 + 127 万)。根治是收付通(P0-43,行政流程 ≥2 个月),
 * 在它落地前,<b>票款不归平台的产品一律不得真实收款</b>(D11/D16 写死:「别在过渡期用『量小没事』放行」)。
 *
 * <p><b>判据三条,顺序不能换</b>:
 * <ol>
 *   <li>不真实收款(payableAmount ≤ 0)⇒ 放行。免费报名/纯积分不进资金链,场景 #29 明确要它能跑。</li>
 *   <li>没有主题真源(独立活动 topic_id=null)⇒ 放行。后台手建活动 = 平台自办 = 卖自己的东西
 *       (2026-07-16 用户拍板)。生产实测:7 个独立活动、12 笔付费单走这条。</li>
 *   <li>有主题但 CONTENT 槽 ≠ PLATFORM ⇒ <b>拒</b>。含「压根没有槽」——{@code isPlatformOwned}
 *       fail-closed,而迁移刻意不回填存量(product_role 生产实测 0 行)。</li>
 * </ol>
 *
 * <p><b>为什么闸放在 createRegistration/createSelfPlayPass 而不是 WechatPayServiceImpl.createOrder</b>:
 * createOrder 看起来是「所有真实收款的唯一必经之路」,一处覆盖很诱人。但它拿不到 topic ——
 * {@code CmsRegistration.topicId} <b>只有自玩通行证填</b>(全类唯一写入点 :531),活动单根本不填 ⇒
 * 在支付层判会静默漏掉整个 ① 活动单,而那正是今天在用的售票路径。要补就得让支付基础设施注入
 * 三个业务 mapper,职责全歪。所以跟 {@code assertExploreSellable} 并列放在业务层。
 *
 * <p>⚠️ 负控必须实测(删了闸这些会红):
 * <ul>
 *   <li>删活动分支的闸 ⇒ {@code activityCarryingThirdPartyTopic_rejected} 红</li>
 *   <li>删自玩分支的闸 ⇒ {@code selfPlayPass_rejected} 红</li>
 *   <li>闸忘了判 payableAmount ⇒ {@code freeRegistration_passes} 红</li>
 *   <li>闸对独立活动 fail-closed ⇒ {@code standaloneActivity_passes} 红</li>
 * </ul>
 *
 * <p><b>诚实的覆盖缺口</b>:{@code createRegistration} 的 ownerType==1 分支从 API 不可达
 * ({@code ApiRegistrationController:199-222} 无条件 early-return 进 createSelfPlayPass),
 * 本类不覆盖它 —— 与 {@link ExploreSellGateTest} 同一个缺口,同一个原因。
 */
class ErqingGateTest {

    private static final Long TOPIC = 700L;
    private static final Long ACTIVITY = 800L;
    private static final Long TICKET = 9L;
    private static final Long MEMBER = 1L;

    private CmsTopicMapper topicMapper;
    private CmsActivityMapper activityMapper;
    private OmsTicketMapper ticketMapper;
    private UmsMemberMapper memberMapper;
    private CmsRegistrationMapper registrationMapper;
    private IProductRoleService productRoleService;
    private CmsRegistrationServiceImpl service;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);
        memberMapper = mock(UmsMemberMapper.class);
        registrationMapper = mock(CmsRegistrationMapper.class);
        productRoleService = mock(IProductRoleService.class);

        service = new CmsRegistrationServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "growthService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IGrowthService.class));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "productRoleService", productRoleService);

        // 默认:槽表空(生产实测 0 行)⇒ fail-closed ⇒ 非自营
        when(productRoleService.isPlatformOwned(anyLong())).thenReturn(false);
    }

    private CmsTopic topic() {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setName("测试主题");
        t.setProductType(ProductType.ORIENTEERING);  // ① —— 现网 14 个主题全是它
        t.setLifecycle(3);
        t.setSellingStartedAt(new Date(System.currentTimeMillis() - 60_000L));
        t.setSaleEndAt(new Date(System.currentTimeMillis() + 86_400_000L));
        t.setStatus(1);
        t.setUserStatus(1);
        t.setDelFlag(0);
        return t;
    }

    private void givenActivityCarrying(Long topicId) {
        CmsActivity a = new CmsActivity();
        a.setId(ACTIVITY);
        a.setName("测试活动");
        a.setTopicId(topicId);
        a.setStatus(1L);
        a.setPublishStatus(1L);
        a.setDelFlag(0);
        a.setEndDate(new Date(System.currentTimeMillis() + 86400_000L));
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(a);
    }

    private void givenTicket(BigDecimal price) {
        OmsTicket t = new OmsTicket();
        t.setId(TICKET);
        t.setName("测试票");
        t.setOwnerId(ACTIVITY);
        t.setOwnerType(2L);
        t.setPrice(price);
        t.setDelFlag(0);
        t.setMode(1);
        t.setRemainingInventory(100L);
        t.setStartTime(new Date(System.currentTimeMillis() + 3600_000L));
        t.setEndTime(new Date(System.currentTimeMillis() + 86400_000L));
        when(ticketMapper.selectOmsTicketById(TICKET)).thenReturn(t);
    }

    private void givenMember() {
        UmsMember m = new UmsMember();
        m.setId(MEMBER);
        m.setPoint(0L);
        m.setStatus(1L);
        m.setDelFlag(0);
        when(memberMapper.selectUmsMemberById(MEMBER)).thenReturn(m);
    }

    /** 直接调私有闸 —— 它是两条路径的公共承重点,先把契约钉死。 */
    private void gate(CmsTopic t, BigDecimal payable) {
        ReflectionTestUtils.invokeMethod(service, "assertCollectible", t, payable);
    }

    // ------------------------------------------------- 闸本身的契约

    /** 第三方主题 + 真实收款 ⇒ 拒。这是本闸的存在理由。 */
    @Test
    void thirdParty_paid_rejected() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> gate(topic(), new BigDecimal("100.00")));
        assertTrue(e.getMessage().contains("暂不支持在线支付"), "实际:" + e.getMessage());
    }

    /** 平台自营 ⇒ 放行。③-自营 是首轮内测标的(D12),拦了它整个首轮就没了。 */
    @Test
    void platformOwned_paid_passes() {
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(true);
        gate(topic(), new BigDecimal("100.00"));
    }

    /** 免费 ⇒ 放行。场景 #29:玩家发免费 ② 在过渡期照样能跑(不进资金链)。 */
    @Test
    void free_passes() {
        gate(topic(), BigDecimal.ZERO);
        gate(topic(), null);
        gate(topic(), new BigDecimal("-1"));
    }

    /** 独立活动(无主题真源)⇒ 放行。平台自办,卖自己的东西(2026-07-16 拍板)。 */
    @Test
    void noTopic_passes() {
        gate(null, new BigDecimal("100.00"));
    }

    // ------------------------------------------------- ★路径可达性:真的调进去

    /**
     * <b>★心脏之一</b>:活动挂第三方主题 + 付费票 ⇒ 必须被拒。
     * 这是今天在用的售票路径(生产实测 2 笔付费单走这条)。
     */
    @Test
    void activityCarryingThirdPartyTopic_rejected() {
        givenActivityCarrying(TOPIC);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic());
        givenTicket(new BigDecimal("100.00"));
        givenMember();

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.createRegistration(MEMBER, 2, ACTIVITY, TICKET, 0, "张三", "13800000000", null, null));
        assertTrue(e.getMessage().contains("暂不支持在线支付"),
                "票款归第三方的活动必须拒收 —— 平台代收再分账 = 二清。实际:" + e.getMessage());
    }

    /** 独立活动 + 付费 ⇒ 过闸(生产实测 12 笔付费单走这条,拦了就是打挂现网)。 */
    @Test
    void standaloneActivity_passes() {
        givenActivityCarrying(null);
        givenTicket(new BigDecimal("100.00"));
        givenMember();

        try {
            service.createRegistration(MEMBER, 2, ACTIVITY, TICKET, 0, "张三", "13800000000", null, null);
        } catch (Exception e) {
            assertTrue(!String.valueOf(e.getMessage()).contains("暂不支持在线支付"),
                    "独立活动 = 平台自办,不该被二清闸拦。实际:" + e.getMessage());
        }
    }

    /** 活动挂平台自营主题 + 付费 ⇒ 过闸。 */
    @Test
    void activityCarryingPlatformTopic_passes() {
        givenActivityCarrying(TOPIC);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic());
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(true);
        givenTicket(new BigDecimal("100.00"));
        givenMember();

        try {
            service.createRegistration(MEMBER, 2, ACTIVITY, TICKET, 0, "张三", "13800000000", null, null);
        } catch (Exception e) {
            assertTrue(!String.valueOf(e.getMessage()).contains("暂不支持在线支付"),
                    "③-自营 是首轮标的,不该被拦。实际:" + e.getMessage());
        }
    }

    @Test
    void firstPaymentWithMissingFeeSnapshotIsRejectedBeforeOrderInsert() {
        givenActivityCarrying(TOPIC);
        CmsTopic platformTopic = topic();
        platformTopic.setFeePlatformRate(null);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(platformTopic);
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(true);
        givenTicket(new BigDecimal("100.00"));
        givenMember();

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.createRegistration(MEMBER, 2, ACTIVITY, TICKET, 0,
                        "张三", "13800000000", null, null));

        assertTrue(error.getMessage().contains("费率快照"));
    }

    @Test
    void firstPaymentFreezesValidatedFeeOnRegistration() {
        givenActivityCarrying(TOPIC);
        CmsTopic platformTopic = topic();
        platformTopic.setFeePlatformRate(new BigDecimal("0.1000"));
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(platformTopic);
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(true);
        givenTicket(new BigDecimal("100.00"));
        givenMember();
        when(registrationMapper.insertCmsRegistration(org.mockito.ArgumentMatchers.any(CmsRegistration.class)))
                .thenReturn(1);
        when(ticketMapper.deductInventory(TICKET, 1L)).thenReturn(1);

        service.createRegistration(MEMBER, 2, ACTIVITY, TICKET, 0,
                "张三", "13800000000", null, null);

        ArgumentCaptor<CmsRegistration> inserted = ArgumentCaptor.forClass(CmsRegistration.class);
        verify(registrationMapper).insertCmsRegistration(inserted.capture());
        assertTrue(new BigDecimal("0.1000").compareTo(inserted.getValue().getFeePlatformRate()) == 0);
    }

    /** 免费票 ⇒ 过闸(payableAmount=0 不进资金链)。 */
    @Test
    void freeTicket_passes() {
        givenActivityCarrying(TOPIC);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic());
        givenTicket(BigDecimal.ZERO);
        givenMember();

        try {
            service.createRegistration(MEMBER, 2, ACTIVITY, TICKET, 0, "张三", "13800000000", null, null);
        } catch (Exception e) {
            assertTrue(!String.valueOf(e.getMessage()).contains("暂不支持在线支付"),
                    "免费报名不进资金链,不构成二清。实际:" + e.getMessage());
        }
    }

    /**
     * <b>★心脏之二</b>:自玩通行证 ⇒ 必须被拒。
     *
     * <p>这条是 {@link ExploreSellGateTest} 承认够不到的那条路径,但对二清闸它<b>必须</b>覆盖:
     * createSelfPlayPass:509 强制 selfPlayPrice > 0 ⇒ <b>② 恒收费</b>,而 ② 的 CONTENT 槽
     * 恒为第三方(玩家/俱乐部)⇒ 每一张自玩票都是二清。生产实测 21 笔付费单是 ownerType=1。
     */
    @Test
    void selfPlayPass_rejected() {
        CmsTopic t = topic();
        t.setSelfPlay(1);
        t.setSelfPlayPrice(new BigDecimal("39.90"));
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(t);
        givenMember();

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.createSelfPlayPass(MEMBER, TOPIC, "张三", "13800000000"));
        assertTrue(e.getMessage().contains("暂不支持在线支付"),
                "自玩票款归主理人(第三方)⇒ 平台代收即二清。实际:" + e.getMessage());
    }

    // ------------------------------------------------- ★多票整单路径(第四条,自己写的闸自己漏)

    /**
     * <b>★心脏之四</b>:多票下单必须按<b>整单</b>金额判,不能按行判。
     *
     * <p>这个洞是 code-review 从我自己刚写的闸里挖出来的:{@code createMultiTicketRegistrations}
     * 复用 createRegistration 建每一行,而闸在 createRegistration 内部判的是<b>行级</b> payable;
     * 整单金额是在建完之后({@code :497 first.setPayableAmount(order.payableAmount)})
     * 才覆盖到首单上的。
     *
     * <p><b>构造</b>:第一张票 0 元 + 第二张 100 元 ⇒ 每行过闸时 payable 都是 0(从票还被显式传
     * {@code BigDecimal.ZERO})⇒ 全部放行 ⇒ 首单事后被改成 100 元待支付。
     * <b>第三方主题照收 100 块,闸形同虚设。</b>
     */
    @Test
    void multiTicket_freeFirstPaidSecond_rejected() {
        givenActivityCarrying(TOPIC);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic());
        givenMember();

        OmsTicket free = new OmsTicket();
        free.setId(TICKET);
        free.setName("免费票");
        free.setOwnerId(ACTIVITY);
        free.setOwnerType(2L);
        free.setPrice(BigDecimal.ZERO);
        free.setDelFlag(0);
        free.setMode(1);
        free.setRemainingInventory(100L);
        free.setStartTime(new Date(System.currentTimeMillis() + 3600_000L));
        free.setEndTime(new Date(System.currentTimeMillis() + 86400_000L));
        when(ticketMapper.selectOmsTicketById(TICKET)).thenReturn(free);

        OmsTicket paid = new OmsTicket();
        paid.setId(10L);
        paid.setName("付费票");
        paid.setOwnerId(ACTIVITY);
        paid.setOwnerType(2L);
        paid.setPrice(new BigDecimal("100.00"));
        paid.setDelFlag(0);
        paid.setMode(1);
        paid.setRemainingInventory(100L);
        paid.setStartTime(new Date(System.currentTimeMillis() + 3600_000L));
        paid.setEndTime(new Date(System.currentTimeMillis() + 86400_000L));
        when(ticketMapper.selectOmsTicketById(10L)).thenReturn(paid);

        com.chengyinhub.business.domain.dto.RegistrationTicketItem i1 =
                new com.chengyinhub.business.domain.dto.RegistrationTicketItem();
        i1.setTicketId(TICKET);
        i1.setCount(1);
        com.chengyinhub.business.domain.dto.RegistrationTicketItem i2 =
                new com.chengyinhub.business.domain.dto.RegistrationTicketItem();
        i2.setTicketId(10L);
        i2.setCount(1);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.createMultiTicketRegistrations(MEMBER, 2, ACTIVITY, 0,
                        "张三", "13800000000", null, null, java.util.Arrays.asList(i1, i2)));
        assertTrue(e.getMessage().contains("暂不支持在线支付"),
                "整单要收 100 元就必须过闸 —— 按行判会被『首票免费』绕过。实际:" + e.getMessage());
    }

    // ------------------------------------------------- ★幂等重放路径(第三条,最隐蔽)

    /**
     * <b>★心脏之三</b>:幂等重放不得绕过闸。
     *
     * <p>{@code ApiRegistrationController:161-194} 命中同 requestId 的已有单时,
     * <b>直接重给支付参数、根本不走 createRegistration</b> ⇒ 前两道闸一道都碰不到。
     * 它还只判 {@code payment_status=0},<b>不判 payExpireTime 是否过期</b> ——
     * 半年前的待支付单今天照样能换到支付参数。
     *
     * <p>生产实测(2026-07-16):待支付且有应付的单 6 笔,其中带 request_id 的 <b>0 笔</b>
     * ⇒ 今天无单可利用。但「现在利用不了」不等于「不用堵」:平台自营单建成后若内容槽被
     * {@code reassignContentSlot} 改判成第三方,这条路立刻就活了。
     */
    @Test
    void replayExistingOrder_thirdParty_rejected() {
        givenActivityCarrying(TOPIC);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic());

        com.chengyinhub.business.domain.CmsRegistration exist =
                new com.chengyinhub.business.domain.CmsRegistration();
        exist.setOwnerType(2L);
        exist.setOwnerId(ACTIVITY);
        exist.setPayableAmount(new BigDecimal("100.00"));

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.assertCollectible(exist));
        assertTrue(e.getMessage().contains("暂不支持在线支付"),
                "幂等重放必须过同一道闸,否则存量待支付单成了绕过通道。实际:" + e.getMessage());
    }

    /** 幂等重放 + 自玩单(ownerType=1)⇒ 同样被拒。 */
    @Test
    void replayExistingOrder_selfPlay_rejected() {
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic());

        com.chengyinhub.business.domain.CmsRegistration exist =
                new com.chengyinhub.business.domain.CmsRegistration();
        exist.setOwnerType(1L);
        exist.setOwnerId(TOPIC);
        exist.setPayableAmount(new BigDecimal("39.90"));

        assertThrows(RuntimeException.class, () -> service.assertCollectible(exist));
    }

    /** 幂等重放 + 独立活动 ⇒ 放行(与主路径同口径)。 */
    @Test
    void replayExistingOrder_standalone_passes() {
        givenActivityCarrying(null);

        com.chengyinhub.business.domain.CmsRegistration exist =
                new com.chengyinhub.business.domain.CmsRegistration();
        exist.setOwnerType(2L);
        exist.setOwnerId(ACTIVITY);
        exist.setPayableAmount(new BigDecimal("100.00"));

        service.assertCollectible(exist);
    }

    /** 幂等重放 + 免费单 ⇒ 放行(不进资金链)。 */
    @Test
    void replayExistingOrder_free_passes() {
        com.chengyinhub.business.domain.CmsRegistration exist =
                new com.chengyinhub.business.domain.CmsRegistration();
        exist.setOwnerType(2L);
        exist.setOwnerId(ACTIVITY);
        exist.setPayableAmount(BigDecimal.ZERO);

        service.assertCollectible(exist);
    }

    /** 平台自营的自玩 ⇒ 过闸。 */
    @Test
    void selfPlayPass_platformOwned_passes() {
        CmsTopic t = topic();
        t.setSelfPlay(1);
        t.setSelfPlayPrice(new BigDecimal("39.90"));
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(t);
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(true);
        givenMember();

        try {
            service.createSelfPlayPass(MEMBER, TOPIC, "张三", "13800000000");
        } catch (Exception e) {
            assertTrue(!String.valueOf(e.getMessage()).contains("暂不支持在线支付"),
                    "平台自营的自玩不该被拦。实际:" + e.getMessage());
        }
    }
}
