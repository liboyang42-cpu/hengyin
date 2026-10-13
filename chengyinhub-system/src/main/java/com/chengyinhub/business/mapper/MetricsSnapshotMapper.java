package com.chengyinhub.business.mapper;

import java.util.Date;
import com.chengyinhub.business.domain.MetricsSnapshot;

/** D12 指标快照 Mapper。 */
public interface MetricsSnapshotMapper {
    int upsertSnapshot(MetricsSnapshot snapshot);

    Date selectLatestSuccessfulSampledAt();
}
