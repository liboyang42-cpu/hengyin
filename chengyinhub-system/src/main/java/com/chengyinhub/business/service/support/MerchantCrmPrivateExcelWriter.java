package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.vo.MerchantCrmExportRowVO;
import com.chengyinhub.common.config.RuoYiConfig;
import com.chengyinhub.common.utils.poi.ExcelUtil;

import java.io.File;
import java.io.IOException;
import java.util.regex.Pattern;

/** ExcelUtil 适配器：输出到公开 /profile/** 映射之外的私有目录。 */
public class MerchantCrmPrivateExcelWriter extends ExcelUtil<MerchantCrmExportRowVO> {
    private static final Pattern STORAGE_KEY = Pattern.compile("[0-9a-f-]{36}\\.xlsx");
    private final String storageKey;

    public MerchantCrmPrivateExcelWriter(String storageKey) {
        super(MerchantCrmExportRowVO.class);
        requireStorageKey(storageKey);
        this.storageKey = storageKey;
    }

    @Override
    public String encodingFilename(String ignored) {
        return storageKey;
    }

    @Override
    public String getAbsoluteFile(String ignored) {
        return storageFile(storageKey).getAbsolutePath();
    }

    public static File storageFile(String storageKey) {
        requireStorageKey(storageKey);
        File root = storageRoot();
        try {
            File file = new File(root, storageKey).getCanonicalFile();
            if (!file.getPath().startsWith(root.getPath() + File.separator)) {
                throw new IllegalArgumentException("导出存储键越界");
            }
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("CRM私有导出路径不可用", e);
        }
    }

    public static File[] storageFiles() {
        File[] files = storageRoot().listFiles((dir, name) -> STORAGE_KEY.matcher(name).matches());
        return files == null ? new File[0] : files;
    }

    private static File storageRoot() {
        String profile = RuoYiConfig.getProfile();
        if (profile == null || profile.trim().isEmpty()) throw new IllegalStateException("未配置文件根目录");
        try {
            File root = new File(profile + "-private", "merchant-crm-export").getCanonicalFile();
            if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("无法创建CRM私有导出目录");
            return root;
        } catch (IOException e) {
            throw new IllegalStateException("CRM私有导出路径不可用", e);
        }
    }

    private static void requireStorageKey(String value) {
        if (value == null || !STORAGE_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException("导出存储键无效");
        }
    }
}
