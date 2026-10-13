package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.service.impl.PricingServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequiredChapterSupplyGateTest
{
    @Test
    void freeExploreCannotConfirmPriceWithMissingRequiredChapterSupply()
    {
        PricingServiceImpl service = new PricingServiceImpl();
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        MerchantChapterOfferMapper offerMapper = mock(MerchantChapterOfferMapper.class);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "offerMapper", offerMapper);

        CmsTopic topic = new CmsTopic();
        topic.setId(8L);
        topic.setLifecycle(2);
        topic.setProductType(ProductType.FREE_EXPLORE);
        when(topicMapper.selectCmsTopicByIdForUpdate(8L)).thenReturn(topic);
        when(offerMapper.countRequiredChaptersWithoutLiveOffer(8L)).thenReturn(1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.confirmPrice(8L, null, BigDecimal.TEN, null, null, null));
        assertTrue(error.getMessage().contains("必选章"));
    }

    @Test
    void missingSupplySqlChecksEveryRequiredChapterAndBothLiveAxes() throws Exception
    {
        String xml = new String(Files.readAllBytes(Paths.get(System.getProperty("user.dir"),
                "../chengyinhub-system/src/main/resources/mapper/business/MerchantChapterOfferMapper.xml")),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ").toLowerCase();

        assertTrue(xml.contains("countrequiredchapterswithoutliveoffer"));
        assertTrue(xml.contains("c.required = 1"));
        assertTrue(xml.contains("not exists"));
        assertTrue(xml.contains("o.status = 1"));
        assertTrue(xml.contains("o.valid_end is null or o.valid_end &gt; now()"));
    }
}
