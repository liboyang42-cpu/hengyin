package com.chengyinhub.business.domain.dto.seed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One row of a bulk import, as shown in the preview: what was read, what happens to it and why. */
public class SeedImportRow {
    public static final String NEW = "NEW";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String ERROR = "ERROR";

    private int rowNumber;
    private Map<String, String> values = new LinkedHashMap<>();
    private String outcome = NEW;
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private Long duplicateOfId;
    private String duplicateOfName;
    private Long createdId;

    public void error(String message) { errors.add(message); outcome = ERROR; }
    public void warn(String message) { warnings.add(message); }
    public void duplicateOf(Long id, String name) {
        if (ERROR.equals(outcome)) return;
        outcome = DUPLICATE; duplicateOfId = id; duplicateOfName = name;
    }
    public boolean isNew() { return NEW.equals(outcome); }

    public int getRowNumber() { return rowNumber; }
    public void setRowNumber(int rowNumber) { this.rowNumber = rowNumber; }
    public Map<String, String> getValues() { return values; }
    public void setValues(Map<String, String> values) { this.values = values; }
    public String getOutcome() { return outcome; }
    public List<String> getErrors() { return errors; }
    public List<String> getWarnings() { return warnings; }
    public Long getDuplicateOfId() { return duplicateOfId; }
    public String getDuplicateOfName() { return duplicateOfName; }
    public Long getCreatedId() { return createdId; }
    public void setCreatedId(Long createdId) { this.createdId = createdId; }
}
