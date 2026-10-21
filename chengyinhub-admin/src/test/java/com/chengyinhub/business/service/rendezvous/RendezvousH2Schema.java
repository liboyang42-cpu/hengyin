package com.chengyinhub.business.service.rendezvous;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** H2 2.4 test dialect only. The MySQL candidate files are not rewritten on disk.
 * H2 2.4 folds {@code IN (...)} inside a generated column into a constant set bound to the
 * creating session, then the next connection sees that database as closed. A simple CASE
 * keeps the same "active actor or null" rule. Passing H2 is not MySQL acceptance. */
final class RendezvousH2Schema {
    private RendezvousH2Schema() {}

    static String consent(String mysql) {
        return strip(mysql).replace(
                "(CASE WHEN state IN ('WAITING','RESERVED','ACCEPTED') THEN actor_id ELSE NULL END)",
                "(CASE state WHEN 'WAITING' THEN actor_id WHEN 'RESERVED' THEN actor_id WHEN 'ACCEPTED' THEN actor_id ELSE NULL END)");
    }

    static String protocol(String mysql) { return strip(mysql); }

    static void apply(Connection connection, String sql) {
        ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
    }

    static String read(Path path) throws java.io.IOException {
        return Files.readString(path);
    }

    private static String strip(String mysql) {
        return mysql.replace(" COLLATE utf8mb4_bin", "")
                .replace(" STORED", "")
                .replace(" GENERATED ALWAYS AS", " AS")
                .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "")
                .replace(" ENGINE=InnoDB", "");
    }
}
