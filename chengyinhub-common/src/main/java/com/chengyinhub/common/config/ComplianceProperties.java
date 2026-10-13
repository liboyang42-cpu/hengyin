package com.chengyinhub.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 合规留痕文档版本映射(配置化)。
 * 发布新版法律文件时改 application.yml 即可,无需改代码,避免版本号漂移导致留痕失真。
 * application.yml 格式:
 * compliance.doc-versions.privacy_policy=v2.1
 * compliance.doc-versions.user_service_agreement=v2.1
 * 版本号真源见发布候选包「17_法律文件总目录与发布说明」。
 *
 * @author chengyinhub
 */
@Component
@ConfigurationProperties(prefix = "compliance")
public class ComplianceProperties
{
    /** docType -> 当前生效版本号 */
    private Map<String, String> docVersions = new HashMap<>();

    public Map<String, String> getDocVersions()
    {
        return docVersions;
    }

    public void setDocVersions(Map<String, String> docVersions)
    {
        this.docVersions = docVersions;
    }

    /** 取某 docType 的生效版本号;未配置返回 null(视为非法 docType)。 */
    public String versionOf(String docType)
    {
        return docType == null ? null : docVersions.get(docType);
    }

    /** 只读视图,防止外部改动。 */
    public Map<String, String> asUnmodifiable()
    {
        return Collections.unmodifiableMap(docVersions);
    }
}
