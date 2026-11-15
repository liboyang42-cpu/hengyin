package com.chengyinhub.business.domain.dto.seed;

import java.util.List;

/** Result of a bulk import preview (dryRun) or commit. Nothing is written unless committed is true. */
public class SeedImportReport {
    private boolean dryRun;
    private boolean committed;
    private int total;
    private int newCount;
    private int duplicateCount;
    private int errorCount;
    private List<SeedImportRow> rows;

    public static SeedImportReport of(List<SeedImportRow> rows, boolean dryRun) {
        SeedImportReport report = new SeedImportReport();
        report.dryRun = dryRun; report.rows = rows; report.total = rows.size();
        for (SeedImportRow row : rows) {
            if (SeedImportRow.ERROR.equals(row.getOutcome())) report.errorCount++;
            else if (SeedImportRow.DUPLICATE.equals(row.getOutcome())) report.duplicateCount++;
            else report.newCount++;
        }
        return report;
    }

    public boolean isDryRun() { return dryRun; }
    public boolean isCommitted() { return committed; }
    public void setCommitted(boolean committed) { this.committed = committed; }
    public int getTotal() { return total; }
    public int getNewCount() { return newCount; }
    public int getDuplicateCount() { return duplicateCount; }
    public int getErrorCount() { return errorCount; }
    public List<SeedImportRow> getRows() { return rows; }
}
