package com.chengyinhub.business.service.support;

import java.util.Set;

/** Exact media names from domain models, QuestionOptionMedia and AdvancedGameConfigValidator. */
public final class AppMediaSchema {
    private AppMediaSchema() {}
    /** Existing player subpackage assets only; these are never provider-processing inputs. */
    private static final Set<String> BUNDLED_MERCHANT_GAME_AUDIO = Set.of(
            "/audio/merchant-games/rhythm-one.mp3", "/audio/merchant-games/rhythm-two.mp3",
            "/audio/merchant-games/rhythm-three.mp3", "/pages/play/audio/merchant-games/rhythm-one.mp3",
            "/pages/play/audio/merchant-games/rhythm-two.mp3", "/pages/play/audio/merchant-games/rhythm-three.mp3");
    public static boolean isBundledMerchantGameAudio(String reference) {
        return reference != null && BUNDLED_MERCHANT_GAME_AUDIO.contains(reference);
    }
    public static final Set<String> MEDIA_FIELDS = Set.of("logo", "cover", "caseImages", "certImages", "avatar",
            "clubLogo", "images", "imgUrl", "imgArr", "medalImg", "storyImg", "audioUrl", "questionImg",
            "questionAudio", "finishMedalImg", "coverImage", "gallery", "pics", "casePics", "routePreviewImg",
            "sportCover", "picUrl", "pic", "albumPics", "coverImg", "npcAvatar", "avatarUrl", "coverUrl",
            "iconUrl", "previewImg", "imageUrl", "imageUrls", "modelUrl", "overlayUrl", "markerUrl",
            "sourceUrl", "cutoutUrl", "frames", "img", "frameUrl", "fbImgs", "lastFeedbackImages", "lastImageUrl", "audio", "image", "authorAvatar", "memberAvatar", "operatorAvatar", "ownerAvatar", "targetAvatar",
            "senderAvatar", "productPic", "skuPic", "nodeImgUrl", "topicImgUrl", "topicImgArr", "brandLogo", "voiceSample");
    public static final Set<String> MEDIA_JSON_FIELDS = Set.of("questionOptionMediaJson", "advancedConfigJson",
            "gameModuleConfigJson", "themeRuntimeConfigJson", "publishedThemeRuntimeConfigJson",
            "configSnapshotJson", "moduleConfigSnapshotJson", "storyJson", "imagesJson", "blocksJson", "journeyStoryJson");
    public static final Set<String> MULTI_MEDIA_FIELDS = Set.of("caseImages", "certImages", "images", "imgArr", "gallery", "pics", "casePics", "albumPics");
}
