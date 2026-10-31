package com.chengyinhub.business.it;

import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.service.impl.AppMediaAssetService;
import com.chengyinhub.business.service.support.AppMediaPublicationGuard;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.HashMap;
import java.util.Map;

/** Real registry SQL admission; signer/provider/upload acceptance is outside these IT fixtures. */
final class MediaPublicationMySqlFixture {
    private MediaPublicationMySqlFixture() { }
    static AppMediaPublicationGuard guard(SqlSessionTemplate sql, CmsMemberTemplateMapper templates) {
        AppMediaReferenceGuard references = new AppMediaReferenceGuard();
        AppMediaAssetMapper mapper = sql.getMapper(AppMediaAssetMapper.class);
        ReflectionTestUtils.setField(references, "mapper", mapper);
        AppMediaAssetService assets = new AppMediaAssetService(mapper,
                new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                        .getBeanProvider(com.chengyinhub.business.service.IAuditTaskService.class));
        ReflectionTestUtils.setField(references, "assets", assets);
        AppMediaPublicationGuard publication = new AppMediaPublicationGuard();
        ReflectionTestUtils.setField(publication, "references", references);
        ReflectionTestUtils.setField(publication, "templates", templates);
        ReflectionTestUtils.setField(publication, "libraries", sql.getMapper(com.chengyinhub.business.mapper.CmsTemplateLibraryMapper.class));
        return publication;
    }
    static String approvedImage(SqlSessionTemplate sql, Long owner) { return image(sql, owner, true); }
    static String heldImage(SqlSessionTemplate sql, Long owner) { return image(sql, owner, false); }
    private static String image(SqlSessionTemplate sql, Long owner, boolean approved) {
        Map<String, Object> asset = new HashMap<>();
        asset.put("memberId", owner);
        asset.put("objectKey", "upload/" + java.util.UUID.randomUUID().toString().replace("-", "") + ".jpg");
        asset.put("mediaType", "IMAGE");
        AppMediaAssetMapper mapper = sql.getMapper(AppMediaAssetMapper.class);
        int inserted = approved ? mapper.insertApprovedImage(asset) : mapper.insert(asset);
        org.junit.jupiter.api.Assertions.assertEquals(1, inserted);
        return "media-asset:" + asset.get("id");
    }
}
