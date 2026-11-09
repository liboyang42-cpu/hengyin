package com.chengyinhub.business.service;

import com.chengyinhub.business.service.safety.SensitiveWordMatcher;

/**
 * Round5D 自建敏感内容库。叠加在微信机审之前:命中直接拒,不依赖微信是否可用;没命中再交给微信。
 * 结果只带分类,命中词不回给调用方、不进日志。
 */
public interface ISensitiveContentLibrary {

    /** 对外统一的拒绝文案,与微信 risky 的既有提示一致,不暴露命中词。 */
    String VIOLATION_MESSAGE = "内容包含违规信息，请修改后重试";
    String IMAGE_VIOLATION_MESSAGE = "图片未通过内容安全检查，请更换后重试";

    /** 文本命中(含 LOG 试运行词);未命中返回 null。不记日志,调用方按需 {@link #recordHit}。 */
    SensitiveWordMatcher.Hit matchText(String text);

    /** 文本命中且需要拦截时返回分类并记命中(LOG 词只记命中不拦);否则 null。 */
    String blockingCategory(String text, String contentType, Long contentId);

    /** 图片字节命中已知违规图(SHA-256 完全相同或 dHash 近似)时返回分类,否则 null。 */
    String matchImage(byte[] bytes);

    void recordHit(String source, String contentType, Long contentId, String category, String action);

    /** 管理端改动后立即重载本实例;其它实例靠版本指纹轮询在 30 秒内跟上。 */
    void reloadNow();

    /** 当前生效词库的版本指纹(存量复查据此判断词库有没有变)。 */
    String currentVersion();

    boolean hasImageEntries();
}
