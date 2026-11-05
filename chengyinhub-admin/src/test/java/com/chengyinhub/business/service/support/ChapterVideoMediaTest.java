package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.common.exception.ServiceException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChapterVideoMediaTest {
    @Test void videoUsesExistingOwnerAndReviewFenceAndCannotBeReplacedWithAudio() {
        AppMediaAssetMapper mapper = mock(AppMediaAssetMapper.class);
        AppMediaReferenceGuard guard = new AppMediaReferenceGuard();
        ReflectionTestUtils.setField(guard, "mapper", mapper);
        when(mapper.select(5L)).thenReturn(Map.of("id",5L,"memberId",7L,"ownerType","APP",
                "mediaType","VIDEO","reviewState","HOLD","objectKey","upload/0123456789abcdef0123456789abcdef.mp4"));
        assertEquals("media-asset:5", guard.requireAttachment(7L,"media-asset:5",false));
        assertThrows(ServiceException.class, () -> guard.requireAttachment(8L,"media-asset:5",false));
        assertThrows(ServiceException.class, () -> guard.requireAttachment(7L,"media-asset:5",true));
        assertDoesNotThrow(() -> guard.requireMediaType("media-asset:5","VIDEO"));
        assertThrows(ServiceException.class, () -> guard.requireMediaType("media-asset:5","IMAGE"));
        assertThrows(ServiceException.class, () -> guard.requireMediaType("media-asset:404","VIDEO"));
    }
    @Test void onlyOpeningOrEndingMayStoreAPosterAndVideoPair() {
        TopicContentWriteGuard guard = new TopicContentWriteGuard(null,null,null,null,null);
        ChapterDTO chapter = new ChapterDTO();
        chapter.setVideoUrl("media-asset:5"); chapter.setVideoPosterUrl("media-asset:6");
        assertThrows(IllegalStateException.class, () -> guard.assertChapterShapeForTopic(chapter,10L));
        chapter.setOpening(true);
        assertDoesNotThrow(() -> guard.assertChapterShapeForTopic(chapter,10L));
        chapter.setVideoPosterUrl("");
        assertThrows(IllegalStateException.class, () -> guard.assertChapterShapeForTopic(chapter,10L));
    }
}
