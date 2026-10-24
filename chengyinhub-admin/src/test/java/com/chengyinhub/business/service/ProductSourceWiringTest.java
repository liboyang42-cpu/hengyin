package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.PurchaseKind;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * P0-02 产品真源的**接线**测试 —— 专补「算得对但没人调用」这类空档。
 *
 * <p>为什么单开一类:{@code TopicProductTypeTest} 测的是 resolveProductType 算得对不对,
 * {@code ProductTypeMapperSqlTest} 测的是列写得进去 —— **两个都绿,也不能证明有人真的往里写**。
 * 评审用 mutation 实测过这个空档:
 * <ul>
 *   <li>删掉 {@code CmsTopicServiceImpl} 里 {@code topic.setProductType(productType)} 那一行
 *       ⇒ 编译通过、692 个测试全绿。产品真源恒为 NULL,没有任何测试发现。</li>
 *   <li>注释掉 {@code CmsRegistrationServiceImpl} 的两处 {@code setPurchaseKind}
 *       ⇒ 同样全绿。购买类型恒为 NULL。</li>
 * </ul>
 * saveTickets 那条由编译器兜底(签名新增了参数),但这三处编译器管不着。
 *
 * <p>做法:不去跑那两个巨型 service(依赖太多),而是断言**接线契约本身** ——
 * 派生函数的行为 + 写入点的存在性。派生函数一旦被改错,或写入点被删,这里就红。
 *
 * ⚠️ 负控已实测:
 *  - 删 CmsTopicServiceImpl:topic.setProductType(productType) ⇒ topicCreateWritesProductType 红
 *  - 注释掉两处 setPurchaseKind ⇒ registrationWritesPurchaseKind 红
 *  - PurchaseKind.derive 的 ticketId==null 分支改掉 ⇒ derive* 系列红
 */
class ProductSourceWiringTest {

    /** 源码里必须存在的写入点。用源码断言而不是行为断言,是因为这两个 service 的依赖多到无法单测组装。 */
    private String sourceOf(String relPath) throws Exception {
        return WiringSource.read(relPath);
    }

    /**
     * 建题主链路必须把 productType 写进 topic。
     * 删掉那一行编译照过、测试照绿 —— 所以只能靠这条源码断言守。
     */
    @Test
    void topicCreateWritesProductType() throws Exception {
        String src = sourceOf("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java");
        assertEquals(1, countOccurrences(src, "topic.setProductType(productType)"),
                "建题链路必须恰有一处 topic.setProductType(productType) —— 没有它 cms_topic.product_type 恒 NULL,"
                        + "产品真源等于没建;而删掉它编译器不会吭声、别的测试也不会红");
        // [B3b] 编辑主题(updateTopicByOwner → rebuildTopicContent)是第二条会写 product_type 的路径,
        // 它同样必须走 resolveProductType(dto) 派生 —— 编辑时另起一套推断,就是把真源劈成两份。
        assertEquals(1, countOccurrences(src, "patch.setProductType(productType)"),
                "编辑主题的重建链路也必须写 product_type,否则改完模式库里还是旧值");
        assertEquals(2, countOccurrences(src, "resolveProductType(dto)"),
                "productType 的唯一来源必须是 resolveProductType(dto):建题一处、编辑重建一处,"
                        + "多出来的第三处就是有人又自己推了一遍");
    }

    /** 创建主题会逐字段落章节；权益门槛不能只由小程序校验。 */
    @Test
    void topicCreateValidatesNonPositivePerkThresholdAtTheSharedRule() throws Exception {
        String src = sourceOf("chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/TopicContentWriteGuard.java");
        String writer = sourceOf("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicContentGraphAuthoringServiceImpl.java");
        String facade = sourceOf("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java");
        assertEquals(2, countOccurrences(facade, "contentGraphAuthoringService.saveTopicChapters("),
                "创建与编辑两条主链均须委托真实内容图owner");
        assertEquals(1, countOccurrences(writer, "contentWriteGuard.assertChapterShapeForTopic(chapterDTO, topicId)"),
                "V1章节写口必须进入共享内容规则");
        assertEquals(1, countOccurrences(writer, "contentWriteGuard.assertChapterShapeForTopic(chapter, topicId)"),
                "V2块编译写口必须进入共享内容规则");
        assertEquals(1, countOccurrences(src, "assertChapterShape(chapter, direct)"),
                "形状入口必须转给原共享形状算法");
        assertEquals(1, countOccurrences(src,
                        "ChapterRecruitmentRules.assertPerkMinValueForTerms(termsMode, c.getPerkMinValue())"),
                "主题发布必须在落库前校验权益门槛正数及其与条款档位的匹配，不能只靠小程序输入框");
    }

    /** 报名落库两处都必须固化 purchase_kind(主链路购票 + 自玩通行证)。 */
    @Test
    void registrationWritesPurchaseKind() throws Exception {
        String src = sourceOf("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsRegistrationServiceImpl.java");
        assertEquals(1, countOccurrences(src, "setPurchaseKind(PurchaseKind.derive("),
                "购票主链路必须推导并固化 purchase_kind");
        assertEquals(1, countOccurrences(src, "setPurchaseKind(PurchaseKind.SELF_PLAY_PASS)"),
                "自玩通行证链路必须固化为 SELF_PLAY_PASS —— 它是唯一无 ticketId 的产品");
    }

    /**
     * **derive 必须喂主题真源 product_type,不能喂票的 mode**。
     *
     * <p>初版喂的是 ticket.getMode() —— 拿派生列反推真源。写票口不止 saveTickets 一个:
     * PricingService 开售时插的「有人带」票曾硬编码 mode=1、自办活动票直接吃前端传值 ⇒
     * 一个 product_type=2 的主题能长出 mode=1 的票 ⇒ derive 给出 GUIDED_TICKET,而 resolveMode
     * 读真源给出自由定向 ⇒ **同一笔订单:玩法=自由定向,账=有人带票**,退款与结算按错档走。
     * 而 purchase_kind 是下单即固化、事后不重算的,错值永久。
     */
    @Test
    void purchaseKindDerivesFromProductTypeNotTicketMode() throws Exception {
        String src = sourceOf("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsRegistrationServiceImpl.java");
        // 2 处:① :setPurchaseKind 落库固化;② [P0-13] ③ 限购闸的行级判据。
        // 两处都喂 productType(主题真源)⇒ 同一表达式、同一口径,不可能分叉。
        // 数量写死是刻意的:再加一处就必须来这儿改一次,逼着人确认新调用点也喂的是真源。
        assertEquals(2, countOccurrences(src, "PurchaseKind.derive(ticketId, productType)"),
                "必须喂主题真源 productType");
        // ③ 限购闸的整单层:多票路径手上是 it.getTicketId(),主题真源经 resolveSellerTopic 解析成
        // orderProductType —— 同样是 product_type,不是票的 mode。
        assertEquals(1, countOccurrences(src, "PurchaseKind.derive(item.getTicketId(), orderProductType)"),
                "③ 整单限购闸必须喂主题真源 orderProductType");
        assertEquals(0, countOccurrences(src, "PurchaseKind.derive(ticketId, ticket.getMode())"),
                "不得拿票的 mode(派生列)当真源喂 derive");
        // 兜住上面三条数不到的形状:任何 derive 调用都不许把 getMode() 喂进去。
        assertEquals(0, countOccurrences(src, "derive(ticketId, ticket.getMode()"),
                "不得拿票的 mode(派生列)当真源喂 derive");
    }

    /** 开售时插的「有人带」票也必须跟主题真源走 —— saveTickets 只覆盖创建路径,够不着这里。 */
    @Test
    void pricingServiceTicketModeFollowsProductType() throws Exception {
        String src = sourceOf("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PricingServiceImpl.java");
        assertEquals(0, countOccurrences(src, "t.setMode(1)"),
                "PricingService 不得硬编码 mode=1 —— 那会在 product_type=2 的主题上当场造出混票");
        assertEquals(1, countOccurrences(src, "ProductType.toTicketMode(topic.getProductType())"),
                "必须由主题 product_type 派生");
    }

    /**
     * 领队全团结算必须走核销收口,不许自己手写三步。
     *
     * <p>它原本是第二条绕过 verifyAndSettle 的路,且同时踩了三个洞:读判而非条件置位(并发下与
     * 单张商家核销双重入账)、无事务(置位先提交、入账再抛就补不回来)、台账探针漏 registrationType(1)
     * (核销行永远不插)。评审抓出来时我的 javadoc 还管 verifyAndSettle 叫「核销收口」。
     */
    @Test
    void clubLeadSettleGoesThroughVerifyAndSettle() throws Exception {
        String src = sourceOf("chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiClubLeadController.java");
        assertEquals(1, countOccurrences(src, "cmsRegistrationService.verifyAndSettle("),
                "领队全团结算必须走 verifyAndSettle");
        assertEquals(0, countOccurrences(src, "reg.setVerificationStatus(1)"),
                "不得自己置核销位 —— 那是读判不是条件置位,并发下会与单张核销双重入账");
        assertEquals(0, countOccurrences(src, "settlementEntryService.recordPaidRegistration("),
                "不得在收口外自己入账 —— 无事务,置位先提交后它一抛就永远补不回来");
    }

    /**
     * 复制主题(模板 /use、save-as)必须把 productType 抄过去。
     *
     * <p>{@code copyTopicBasicInfo} 是**逐字段手抄清单**(不是 copyProperties),加字段不会自动带。
     * 漏了的后果不是「副本少个字段」,而是**存量 NULL 集合永不收敛**:模板复制持续新生
     * product_type=NULL 的主题 ⇒ resolveMode 的双读 fallback 永远删不掉。
     *
     * <p>同一个陷阱在这个类里有两处({@code copyChaptersAndNodes} 与 {@code copyTopicBasicInfo}),
     * 本轮初版只补了章节那处、漏了主题这处 —— 所以两处都得有断言守着。
     */
    @Test
    void copyTopicCarriesProductType() throws Exception {
        String src = sourceOf("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicContentCopyServiceImpl.java");
        assertEquals(1, countOccurrences(src, "newTopic.setProductType(original.getProductType())"),
                "copyTopicBasicInfo 必须手抄 productType —— 它是手抄清单,加字段不会自动带;"
                        + "漏了会让模板复制持续新生 product_type=NULL,NULL 集合永不收敛");
    }

    /** 复制章节同样要带上全部承接与供给条款字段(同一个手抄陷阱的另一处)。 */
    @Test
    void copyChaptersCarryConfigFields() throws Exception {
        String src = sourceOf("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicContentCopyServiceImpl.java");
        for (String f : new String[]{"setCategory(originalChapter.getCategory())",
                "setCategoryId(originalChapter.getCategoryId())",
                "setRecruitEnabled(originalChapter.getRecruitEnabled())",
                "setPerkMinValue(originalChapter.getPerkMinValue())",
                "setMaxMerchant(originalChapter.getMaxMerchant() == null ? 0 : originalChapter.getMaxMerchant())",
                "setRequired(originalChapter.getRequired())",
                "setTermsMode(sourceRevshare ? TermsMode.PERK : originalChapter.getTermsMode())",
                "setMaxPerHeadFee(sourceRevshare ? null : originalChapter.getMaxPerHeadFee())"}) {
            assertEquals(1, countOccurrences(src, "newChapter." + f),
                    "copyChaptersAndNodes 漏抄 " + f + " ⇒ 复制出来的主题静默丢章节承接或供给条款配置");
        }
    }

    /** saveTickets 的 mode 覆盖必须在 copyProperties **之后**(之前会被 DTO 值盖回去)。 */
    @Test
    void ticketModeOverrideComesAfterCopyProperties() throws Exception {
        String src = sourceOf("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java");
        int copyAt = src.indexOf("BeanUtils.copyProperties(ticketDTO, ticket)");
        int overrideAt = src.indexOf("ticket.setMode(ProductType.toTicketMode(productType))");
        assertEquals(true, copyAt > 0 && overrideAt > 0, "两处都得在");
        assertEquals(true, overrideAt > copyAt,
                "setMode 必须在 copyProperties 之后,否则 DTO 里的 mode 会把派生值盖回去");
    }

    // ------------------------------------------------ P0-28 批1:费率进库 + 锁价快照

    /**
     * 锁价(lifecycle 2→3)必须把当时的费率冻进主题。
     *
     * <p>mock 测不到这条:{@code FeeSnapshotTest} 证明的是「有快照时读快照」,
     * 但「锁价时到底有没有写快照」编译器不管、mock 也管不着 —— 不写的话快照恒为空、
     * 永远回落 live 值,整个批1 白做,而且 13 个测试照样全绿。
     */
    @Test
    void confirmPriceFreezesFeeSnapshot() throws Exception {
        String src = sourceOf("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PricingServiceImpl.java");
        for (String setter : new String[]{"upd.setFeePlatformRate(", "upd.setFeeGuaranteeBaseRate(",
                "upd.setFeeGuaranteeStepRate(", "upd.setFeeGuaranteeMaxMerchant("}) {
            assertEquals(1, countOccurrences(src, setter),
                    "confirmPrice 锁价时必须冻结费率快照,缺 " + setter + " ⇒ 该维度永远回落 live 值 = 发版即资损没堵住");
        }
    }

    /**
     * 结算路径必须读快照,不能读 live。
     *
     * <p>用 live = 调一次费率追溯改写所有已售未结订单 → 平台多抽 → 发起人 margin&lt;0 →
     * 守恒断言拒结 → 款项悬空转人工。
     */
    @Test
    void settlementReadsFeeSnapshotNotLive() throws Exception {
        String src = sourceOf("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java");
        assertEquals(1, countOccurrences(src, "pricingService.platformRateOf(topic)"),
                "结算必须按主题快照取平台率");
        assertEquals(1, countOccurrences(src, "pricingService.initiatorGuaranteeRateOf(topic,"),
                "结算必须按主题快照取保底率");
        assertEquals(0, countOccurrences(src, "pricingService.platformRate()"),
                "结算路径不得读 live 平台率 —— 那是「此刻该按多少定价」,不是「这单当初按多少卖的」");
        assertEquals(0, countOccurrences(src, "pricingService.initiatorGuaranteeRate("),
                "同上(注意 initiatorGuaranteeRateOf 带 Of 后缀,不该被这条误伤)");
    }

    /** 平台率的硬编码常量必须退役 —— 只剩「查不到规则行」时的兜底,不再是业务真源。 */
    @Test
    void platformRateConstantRetired() throws Exception {
        String src = sourceOf("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PricingServiceImpl.java");
        // ⚠️ 断言必须带上类型前缀:裸写 "PLATFORM_RATE" 会被 "FALLBACK_PLATFORM_RATE" 当子串命中,
        // 那样这条就恒红、且红得毫无信息量(我第一版正是这么写的)。
        assertEquals(0, countOccurrences(src, "BigDecimal PLATFORM_RATE"),
                "旧的业务真源常量 PLATFORM_RATE 必须消失(FALLBACK_PLATFORM_RATE 是兜底,不算)");
        assertEquals(1, countOccurrences(src, "settlementRuleService.getActiveRule("),
                "费率必须来自 settlement_rule —— 这就是「调价不用发版」的全部内容");
        assertEquals(1, countOccurrences(src, "FALLBACK_PLATFORM_RATE = new BigDecimal(\"0.10\")"),
                "兜底值应保留且等于原硬编码值,保证查不到规则时行为不变");
    }

    /** 主理人财务页也读快照 —— 否则页面显示的钱和结算实际算的钱对不上。 */
    @Test
    void clubFinanceReadsSnapshot() throws Exception {
        String src = sourceOf("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CoopInviteServiceImpl.java");
        assertEquals(1, countOccurrences(src, "pricingService.platformRateOf(t)"),
                "主理人财务页必须按该主题的快照算,否则显示与结算对不上");
        assertEquals(0, countOccurrences(src, "pricingService.platformRate()"),
                "不得用 live 值(原来还是循环外取一次、对所有主题一视同仁)");
    }

    private int countOccurrences(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    // ------------------------------------------------ PurchaseKind.derive 的口径

    /** 无 ticketId ⇒ 自玩通行证(createSelfPlayPass 不落 ticketId,这是它唯一的识别特征)。 */
    @Test
    void derive_noTicket_isSelfPlayPass() {
        assertEquals(PurchaseKind.SELF_PLAY_PASS, PurchaseKind.derive(null, ProductType.ORIENTEERING));
        assertEquals(PurchaseKind.SELF_PLAY_PASS, PurchaseKind.derive(null, ProductType.FREE_EXPLORE));
        assertEquals(PurchaseKind.SELF_PLAY_PASS, PurchaseKind.derive(null, null));
    }

    /** 有票 + 经典定向 ⇒ ① 有人带票。 */
    @Test
    void derive_ticketOnOrienteering_isGuidedTicket() {
        assertEquals(PurchaseKind.GUIDED_TICKET, PurchaseKind.derive(7L, ProductType.ORIENTEERING));
    }

    /** 有票 + 自由定向 ⇒ ③ 自由探索票。 */
    @Test
    void derive_ticketOnFreeExplore_isExplorePass() {
        assertEquals(PurchaseKind.EXPLORE_PASS, PurchaseKind.derive(7L, ProductType.FREE_EXPLORE));
    }

    /** 有票 + 主题未回填 ⇒ 按经典定向兜底(与 resolveMode 的历史默认同口径,别自作主张判成 ③)。 */
    @Test
    void derive_ticketWithUnknownProductType_fallsBackToGuided() {
        assertEquals(PurchaseKind.GUIDED_TICKET, PurchaseKind.derive(7L, null));
    }

    // ------------------------------------------------ ProductType 的口径

    /** 派生是恒等映射 —— 值域刻意与 oms_ticket.mode 同值,回填才不用换算。 */
    @Test
    void productTypeToTicketMode_isIdentity() {
        assertEquals(Integer.valueOf(1), ProductType.toTicketMode(ProductType.ORIENTEERING));
        assertEquals(Integer.valueOf(2), ProductType.toTicketMode(ProductType.FREE_EXPLORE));
        assertEquals(null, ProductType.toTicketMode(null));
    }

    /** 只认 1/2;NULL 与野值都不合法(校验入参用,别拿它判存量)。 */
    @Test
    void productTypeIsValid_rejectsNullAndGarbage() {
        assertEquals(true, ProductType.isValid(1));
        assertEquals(true, ProductType.isValid(2));
        assertEquals(false, ProductType.isValid(null));
        assertEquals(false, ProductType.isValid(0));
        assertEquals(false, ProductType.isValid(99));
    }
}
