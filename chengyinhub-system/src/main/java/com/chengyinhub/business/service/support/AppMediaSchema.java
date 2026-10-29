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
    /** 官方模板自带图(服务端静态目录,见 migration_20260822/20260825/20261009):任何采用或应用模板的人都可直接用;也不是克隆/第三方处理输入。 */
    private static final java.util.regex.Pattern OFFICIAL_TEMPLATE_IMAGE = java.util.regex.Pattern.compile(
            "https://www\\.chengyinhub\\.com/prod-api/profile/(?:template-covers|topic-covers|merchant-game-covers)/[a-z0-9-]+\\.(?:webp|jpg|jpeg|png)");
    public static boolean isOfficialTemplateImage(String reference) {
        return reference != null && OFFICIAL_TEMPLATE_IMAGE.matcher(reference).matches();
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
            "configSnapshotJson", "moduleConfigSnapshotJson", "storyJson", "imagesJson", "blocksJson", "journeyStoryJson",
            // 官方地点相册(roam_poi.gallery_json):JSON 字符串数组,逐张按 gallery 走审核事实,未登记的图不外发
            "galleryJson");
    public static final Set<String> MULTI_MEDIA_FIELDS = Set.of("caseImages", "certImages", "images", "imgArr", "gallery", "pics", "casePics", "albumPics");
}
