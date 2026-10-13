package com.chengyinhub.business.domain.dto;

import java.util.ArrayList;
import java.util.List;

/** 商家线索批量导入结果；失败明细始终携带 Excel 物理行号和原因。 */
public class MerchantLeadImportResult {
    private int totalCount;
    private int successCount;
    private final List<Failure> failures = new ArrayList<>();

    public int getTotalCount() { return totalCount; }
    public void setTotalCount(int totalCount) { this.totalCount = totalCount; }
    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int successCount) { this.successCount = successCount; }
    public int getFailureCount() { return failures.size(); }
    public List<Failure> getFailures() { return failures; }

    public void addFailure(int rowNumber, String reason) {
        failures.add(new Failure(rowNumber, reason));
    }

    public static class Failure {
        private final int rowNumber;
        private final String reason;

        public Failure(int rowNumber, String reason) {
            this.rowNumber = rowNumber;
            this.reason = reason;
        }

        public int getRowNumber() { return rowNumber; }
        public String getReason() { return reason; }
    }
}
