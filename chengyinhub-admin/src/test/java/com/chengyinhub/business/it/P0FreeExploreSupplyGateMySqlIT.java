package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.PricingServiceImpl;
import com.chengyinhub.business.service.support.PureContentExploreService;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AC1 · P0 自由探索「需补供给 / 锁价」卡的后端半边:
 * {@code MerchantChapterOfferMapper.countRequiredChaptersWithoutLiveOffer} 在真 MySQL 上判定
 * "每个必选章至少 1 家生效供给",{@link PricingServiceImpl#confirmPrice} 据此在写任何价格/票之前拒绝。
 *
 * <p>覆盖:无供给、失效(status=0)、过期(valid_end 已过)、只覆盖可选章 —— 一律拦;补齐生效供给后放行到下一道闸。
 * 不覆盖:锁价成功后的票价/库存写入(FreeExplorePriceLockAcceptanceTest,Mockito)与真 HTTP(chain_merchant_club)。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class P0FreeExploreSupplyGateMySqlIT {
    private static final String SUITE = "p0_free_supply";
    private static final long TOPIC = 801L;
    private static final String BLOCK = "每个必选章至少需要 1 家生效商家供给,暂不能锁价开售";

    private JdbcTemplate db;
    private MerchantChapterOfferMapper offers;
    private PricingServiceImpl pricing;
    private CmsTopicMapper topics;
    private OmsTicketMapper tickets;

    @BeforeAll
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = P0AcceptanceSchema.dataSource(SUITE);
        db = new JdbcTemplate(dataSource);
        P0AcceptanceSchema.baseTables(db, "cms_topic_chapter", "merchant_chapter_offer");
        SqlSessionTemplate sql = P0AcceptanceSchema.sql(dataSource, "MerchantChapterOfferMapper");
        offers = sql.getMapper(MerchantChapterOfferMapper.class);
        pricing = new PricingServiceImpl();
        topics = mock(CmsTopicMapper.class);
        tickets = mock(OmsTicketMapper.class);
        ReflectionTestUtils.setField(pricing, "offerMapper", offers);
        ReflectionTestUtils.setField(pricing, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(pricing, "omsTicketMapper", tickets);
        PureContentExploreService pureContent = mock(PureContentExploreService.class);
        ReflectionTestUtils.setField(pricing, "pureContentExploreService", pureContent);
    }

    @BeforeEach
    void clear() {
        db.update("delete from merchant_chapter_offer");
        db.update("delete from cms_topic_chapter");
        reset(topics, tickets);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setLifecycle(2);
        topic.setProductType(ProductType.FREE_EXPLORE);
        when(topics.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
        db.update("insert into cms_topic_chapter(id,topic_id,required) values (11,?,1),(12,?,1),(13,?,0)", TOPIC, TOPIC, TOPIC);
    }

    @AfterAll
    void dropSchema() {
        if (db != null) {
            P0AcceptanceSchema.drop(db, SUITE);
        }
    }

    @Test
    void requiredChapterWithoutLiveSupplyBlocksPriceLockBeforeAnyWrite() {
        assertEquals(2, offers.countRequiredChaptersWithoutLiveOffer(TOPIC), "两个必选章都没有供给");
        assertBlocked();

        offer(11, 1, "now() + interval 1 day");
        offer(12, 0, "null");                       // 商家已退出(失效)
        offer(13, 1, "null");                       // 只覆盖可选章,不算
        assertEquals(1, offers.countRequiredChaptersWithoutLiveOffer(TOPIC));
        assertBlocked();

        db.update("update merchant_chapter_offer set status=1, valid_end=now() - interval 1 minute where chapter_id=12");
        assertEquals(1, offers.countRequiredChaptersWithoutLiveOffer(TOPIC), "过期供给不算生效");
        assertBlocked();
    }

    @Test
    void liveSupplyOnEveryRequiredChapterPassesTheSupplyGate() {
        offer(11, 1, "null");
        offer(12, 1, "now() + interval 1 day");
        assertEquals(0, offers.countRequiredChaptersWithoutLiveOffer(TOPIC));
        // 供给闸放行后才轮到票价底价等后续闸;这里只证明拒绝原因不再是供给。
        IllegalStateException next = assertThrows(IllegalStateException.class,
                () -> pricing.confirmPrice(TOPIC, null, null, null, null, null));
        assertEquals("请至少填写一种票价", next.getMessage());
        verify(topics, never()).updateCmsTopic(any());
        verifyNoInteractions(tickets);
    }

    private void assertBlocked() {
        IllegalStateException blocked = assertThrows(IllegalStateException.class,
                () -> pricing.confirmPrice(TOPIC, null, new BigDecimal("59.00"), null, null, null));
        assertEquals(BLOCK, blocked.getMessage());
        verify(topics, never()).updateCmsTopic(any());
        verifyNoInteractions(tickets);
    }

    private void offer(long chapter, int status, String validEnd) {
        db.update("insert into merchant_chapter_offer(topic_id,chapter_id,merchant_id,quota_total,terms_mode,status,valid_end)"
                + " values (?,?,9001,10,'TRAFFIC',?," + validEnd + ")", TOPIC, chapter, status);
    }
}
