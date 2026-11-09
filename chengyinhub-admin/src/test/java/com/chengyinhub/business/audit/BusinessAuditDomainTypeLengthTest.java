package com.chengyinhub.business.audit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * qx-c1 #10: {@code business_audit_event.domain_type} is VARCHAR(16)
 * (migration_business_audit_event_20260823.sql). A longer literal makes the audit insert fail at runtime and rolls back
 * the business write it belongs to (VideoBountyServiceImpl wrote "VIDEO_BOUNTY_SUBMISSION", 23 chars). Every writer
 * goes through {@code BusinessAuditEvent.setDomainType}, so every literal or constant passed there must fit.
 */
class BusinessAuditDomainTypeLengthTest {
    private static final int DOMAIN_TYPE_MAX = 16;
    private static final Pattern LITERAL = Pattern.compile("setDomainType\\(\\s*\"([^\"]*)\"");
    private static final Pattern TERNARY = Pattern.compile("setDomainType\\([^;]*?\\?\\s*\"([^\"]*)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern CONSTANT_ARG = Pattern.compile("setDomainType\\(\\s*([A-Z][A-Z0-9_]*)\\s*\\)");
    private static final Pattern WRITE_AUDIT_ARG = Pattern.compile("writeVerificationAudit\\(\\s*\"([^\"]*)\"");

    @Test
    void everyAuditDomainTypeWrittenByProductionCodeFitsTheColumn() throws IOException {
        List<String> offenders = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Path root : new Path[]{Paths.get("..", "chengyinhub-system", "src", "main", "java"),
                Paths.get("..", "chengyinhub-framework", "src", "main", "java"), Paths.get("src", "main", "java")}) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    List<String> values = new ArrayList<>();
                    collect(LITERAL.matcher(text), values, 1);
                    Matcher ternary = TERNARY.matcher(text);
                    while (ternary.find()) { values.add(ternary.group(1)); values.add(ternary.group(2)); }
                    collect(WRITE_AUDIT_ARG.matcher(text), values, 1);
                    Matcher constant = CONSTANT_ARG.matcher(text);
                    while (constant.find()) {
                        Matcher declared = Pattern.compile("\\b" + constant.group(1) + "\\s*=\\s*\"([^\"]*)\"").matcher(text);
                        if (declared.find()) values.add(declared.group(1));
                        else offenders.add(file.getFileName() + ": unresolved constant " + constant.group(1));
                    }
                    for (String value : values) {
                        seen.add(value);
                        if (value.length() > DOMAIN_TYPE_MAX) offenders.add(file.getFileName() + ": " + value);
                    }
                }
            }
        }
        assertTrue(seen.contains("MERCHANT") && seen.contains("VIDEO_BOUNTY"), "scanner must see the real writers: " + seen);
        assertEquals(List.of(), offenders, "business_audit_event.domain_type is VARCHAR(16)");
    }

    private static void collect(Matcher matcher, List<String> values, int group) {
        while (matcher.find()) values.add(matcher.group(group));
    }
}
