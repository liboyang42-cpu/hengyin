package com.chengyinhub.business.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.util.Date;

/**
 * M7 公开落地对应的「可售修订」身份。施工 BE-13/MP-04 要求：展示内容必须对应可售修订，
 * 不能凭预览认定已实地核验。
 *
 * <p>三档事实分开，缺一档不冒充下一档：
 * <ul>
 *   <li>{@code SALE_FROZEN} —— 存在 {@code topic_sale_snapshot}(终价确认时冻结的 canonical-15)，最强证据；</li>
 *   <li>{@code PUBLISHED_CONFIG} —— 无售卖快照，但有已发布的路线/主题配置版本(
 *       {@code published_config_version} 或已发布主题运行时配置)，只说明配置已发布；</li>
 *   <li>{@code NONE} —— 两者皆无：可读，但不得标为「对应可售修订」。</li>
 * </ul>
 *
 * <p>本 VO 不返回 canonical_json 内容(含价格/条款明细)，只返回身份与时间。
 */
public class PublishRevisionVO {

    private String state;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long saleSnapshotId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date frozenAt;

    private Long publishedConfigVersion;

    public String getState() { return state; }

    public void setState(String state) { this.state = state; }

    public Long getSaleSnapshotId() { return saleSnapshotId; }

    public void setSaleSnapshotId(Long saleSnapshotId) { this.saleSnapshotId = saleSnapshotId; }

    public Date getFrozenAt() { return frozenAt; }

    public void setFrozenAt(Date frozenAt) { this.frozenAt = frozenAt; }

    public Long getPublishedConfigVersion() { return publishedConfigVersion; }

    public void setPublishedConfigVersion(Long publishedConfigVersion) {
        this.publishedConfigVersion = publishedConfigVersion;
    }
}
