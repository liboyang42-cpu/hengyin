package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.exception.ChapterFlowClientUpgradeRequiredException;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * F-L6-09：「客户端太旧」的拒绝统一回 426（小程序 request-client 的「版本需更新」分支），
 * 文案保持不变；普通参数错误不受影响（负控见 ApiTopicChapterFlowV2ControllerTest /
 * MerchantAftercareServiceImplTest）。
 */
class OldClientUpgradeRequiredContractTest
{
    @Test
    void legacySquareCommentAsksOldClientToUpgrade()
    {
        ApiCommentController controller = spy(new ApiCommentController());
        doReturn(19L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "legacyCommunityWritesEnabled", false);

        AjaxResult result = controller.addComment("3", "5", null, "评论", null, null);

        assertEquals(HttpStatus.UPGRADE_REQUIRED, result.get(AjaxResult.CODE_TAG));
        assertEquals("广场互动已升级，请更新客户端后重试", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void legacySquarePublishAsksOldClientToUpgrade()
    {
        ApiCreativeSquareController controller = spy(new ApiCreativeSquareController());
        doReturn(19L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "legacyCommunityWritesEnabled", false);

        AjaxResult result = controller.creativeSquareActoin(null, "内容", null, null, null, null, null,
                null, null, null, null, null);

        assertEquals(HttpStatus.UPGRADE_REQUIRED, result.get(AjaxResult.CODE_TAG));
        assertEquals("广场发布已升级，请更新客户端后重试", result.get(AjaxResult.MSG_TAG));
    }

    /** service 侧真的抛的是「需更新」子类，而不是普通章节流契约错误（否则 controller 的 426 分支永远走不到）。 */
    @Test
    void materializedBlocksRejectLegacyWriteWithUpgradeRequiredException()
    {
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class);
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setBlocksJson("[{\"type\":\"text\"}]");
        when(chapterMapper.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter));
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);

        ChapterFlowClientUpgradeRequiredException e = assertThrows(ChapterFlowClientUpgradeRequiredException.class,
                () -> ReflectionTestUtils.invokeMethod(com.chengyinhub.business.service.support.TopicGraphTestWiring.guard(service), "assertLegacyChapterFlowWritable", 7L));

        assertEquals("请更新小程序后编辑该主题", e.getMessage());
    }
}
