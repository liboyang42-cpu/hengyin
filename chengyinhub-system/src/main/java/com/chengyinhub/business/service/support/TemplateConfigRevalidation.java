package com.chengyinhub.business.service.support;

import com.chengyinhub.common.constant.ValidationMethod;
import com.chengyinhub.common.utils.StringUtils;

/**
 * 模板过审前的 schema 复核:对已落库的配置重跑发布时那两道校验
 * ({@link SensorChallengePolicy} 传感器/滤镜配置 + {@link AdvancedGameConfigValidator} 高级玩法配置)。
 *
 * <p>只校验、不改写:审核只能对快照里那份内容表态,不能在审核口偷偷规范化。
 * 与 TemplatePublishServiceImpl#normalizeAndValidateSensorFields 同一判定分支。
 */
public final class TemplateConfigRevalidation {

    private TemplateConfigRevalidation() {}

    /** @throws IllegalArgumentException 配置不再通过校验(文案即校验器原文) */
    public static void requireValid(Integer validationMethod, String sensorType, String sensorConfig,
                                    String advancedConfigJson) {
        if (SensorChallengePolicy.isAppSensor(validationMethod)) {
            SensorChallengePolicy.validateTemplateConfig(sensorType, sensorConfig);
        } else if (SensorChallengePolicy.isPhotoFilter(validationMethod, sensorType)) {
            SensorChallengePolicy.validatePhotoFilterConfig(sensorConfig);
        } else if (validationMethod != null && validationMethod == ValidationMethod.PHOTO
                && (StringUtils.isNotBlank(sensorType) || StringUtils.isNotBlank(sensorConfig))) {
            throw new IllegalArgumentException("拍照滤镜类型无效");
        }
        if (StringUtils.isNotBlank(advancedConfigJson)) {
            new AdvancedGameConfigValidator().validateAndNormalize(advancedConfigJson);
        }
    }
}
