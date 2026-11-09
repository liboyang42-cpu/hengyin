package com.chengyinhub.framework.config;

import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.spi.MySqlWallProvider;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * qx-c1 #1: the app datasource runs Druid wall (multi-statement off). Its MySQL parser rejects
 * {@code SELECT ... FOR UPDATE OF alias}, so the old {@code WorkshopMapper.lockLicenseForDraft} made theme {@code /use}
 * fail for themes that reference node templates. Raw-datasource MySQL ITs cannot see this, so the actual mapper XML
 * statements are checked against the app's wall here.
 */
class DruidWallForUpdateOfContractTest {
    private static final Pattern FOR_UPDATE_OF = Pattern.compile("(?i)for\\s+update\\s+of\\b");

    private static MySqlWallProvider appWall() {
        WallConfig config = new WallConfig(MySqlWallProvider.DEFAULT_CONFIG_DIR);
        config.setMultiStatementAllow(false);
        return new MySqlWallProvider(config);
    }

    @Test
    void wallRejectsForUpdateOfSoTheContractIsMeaningful() {
        var old = appWall().check("select o.*,cast(m.member_id as signed) as draft_owner from cms_member_template m "
                + "left join workshop_install i on i.draft_id=m.id left join workshop_draft_lineage l on l.draft_id=m.id "
                + "inner join workshop_order o on o.order_no=coalesce(i.order_no,l.order_no) where m.id=? for update of o");
        assertFalse(old.getViolations().isEmpty(), "negative control: wall must still reject FOR UPDATE OF");
    }

    @Test
    void actualWorkshopLicenseLockStatementsPassTheWall() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/business/WorkshopMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        MySqlWallProvider wall = appWall();
        for (String id : List.of("lockLicenseOrderForDraft", "licenseInstallationForDraft")) {
            String sql = configuration.getMappedStatement("com.chengyinhub.business.mapper.WorkshopMapper." + id, false)
                    .getBoundSql(Map.of("draftId", 21L)).getSql();
            assertTrue(wall.check(sql).getViolations().isEmpty(), id + " " + wall.check(sql).getViolations());
        }
        String lock = configuration.getMappedStatement("com.chengyinhub.business.mapper.WorkshopMapper.lockLicenseOrderForDraft", false)
                .getBoundSql(Map.of("draftId", 21L)).getSql().replaceAll("\\s+", " ").toLowerCase();
        assertTrue(lock.startsWith("select * from workshop_order where order_no=("), "only the order row is the locking target");
        assertTrue(lock.endsWith(") for update"), lock);
    }

    @Test
    void noMapperXmlUsesForUpdateOf() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : new Path[]{Paths.get("..", "chengyinhub-system", "src", "main", "resources", "mapper"),
                Paths.get("..", "chengyinhub-quartz", "src", "main", "resources", "mapper"),
                Paths.get("..", "chengyinhub-generator", "src", "main", "resources", "mapper"),
                Paths.get("src", "main", "resources", "mapper")}) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".xml"))::iterator) {
                    String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n");
                    for (int i = 0; i < lines.length; i++)
                        if (FOR_UPDATE_OF.matcher(lines[i]).find()) offenders.add(file.getFileName() + ":" + (i + 1));
                }
            }
        }
        assertEquals(List.of(), offenders, "Druid wall rejects FOR UPDATE OF; lock the target row in its own select");
    }
}
