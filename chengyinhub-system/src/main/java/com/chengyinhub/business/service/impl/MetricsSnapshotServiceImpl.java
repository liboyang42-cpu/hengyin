package com.chengyinhub.business.service.impl;

import java.util.Date;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.domain.MetricsSnapshot;
import com.chengyinhub.business.mapper.MetricsSnapshotMapper;
import com.chengyinhub.business.metrics.MetricsSnapshotPolicy;
import com.chengyinhub.business.service.IMetricsSnapshotService;
import com.chengyinhub.common.utils.StringUtils;

/** D12 指标快照落库服务。 */
@Service
public class MetricsSnapshotServiceImpl implements IMetricsSnapshotService {

    @Autowired
    private MetricsSnapshotMapper metricsSnapshotMapper;

    @Override
    public int upsertSnapshots(List<MetricsSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return 0;
        }
        int affected = 0;
        for (MetricsSnapshot snapshot : snapshots) {
            validate(snapshot);
            affected += metricsSnapshotMapper.upsertSnapshot(snapshot);
        }
        return affected;
    }

    @Override
    public Date selectLatestSuccessfulSampledAt() {
        return metricsSnapshotMapper.selectLatestSuccessfulSampledAt();
    }

    private static void validate(MetricsSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("metrics snapshot is null");
        }
        if (StringUtils.isEmpty(snapshot.getMetricKey()) || StringUtils.isEmpty(snapshot.getSource())
                || StringUtils.isEmpty(snapshot.getStatus()) || snapshot.getWindowStart() == null
                || snapshot.getWindowEnd() == null || snapshot.getValue() == null || snapshot.getSampledAt() == null) {
            throw new IllegalArgumentException("metrics snapshot missing required fields");
        }
        if (!"OK".equals(snapshot.getStatus()) && !"FAIL".equals(snapshot.getStatus())) {
            throw new IllegalArgumentException("metrics snapshot status invalid");
        }
        if (!MetricsSnapshotPolicy.isAllowedMetricKey(snapshot.getMetricKey())
                || !MetricsSnapshotPolicy.isAllowedSource(snapshot.getSource())
                || !MetricsSnapshotPolicy.isAllowedThresholdVersion(snapshot.getThresholdVersion())) {
            throw new IllegalArgumentException("metrics snapshot label invalid");
        }
        if (!snapshot.getWindowStart().before(snapshot.getWindowEnd())) {
            throw new IllegalArgumentException("metrics snapshot window invalid");
        }
    }
}
