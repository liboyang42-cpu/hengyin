package com.chengyinhub.business.service.support;

import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.service.impl.AppMediaAssetService;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.mockito.Mockito.*;

/** Explicit immutable reviewed assets for unit fixtures, never an application bypass. */
public final class MediaPublicationTestFixtures {
    private MediaPublicationTestFixtures() {}
    public static AppMediaPublicationGuard template(Long owner, Long... approvedAssetIds) {
        AppMediaAssetMapper assets = mock(AppMediaAssetMapper.class);
        for (Long id : approvedAssetIds) {
            when(assets.select(id)).thenReturn(Map.of("id", id, "memberId", owner, "ownerType", "APP",
                    "reviewState", "APPROVED", "mediaType", id == 2L ? "AUDIO" : "IMAGE"));
        }
        AppMediaReferenceGuard references = new AppMediaReferenceGuard();
        ReflectionTestUtils.setField(references, "mapper", assets);
        ReflectionTestUtils.setField(references, "assets", mock(AppMediaAssetService.class));
        AppMediaPublicationGuard publication = new AppMediaPublicationGuard();
        ReflectionTestUtils.setField(publication,"references",references);
        ReflectionTestUtils.setField(publication,"libraries",mock(com.chengyinhub.business.mapper.CmsTemplateLibraryMapper.class));
        return publication;
    }
}
