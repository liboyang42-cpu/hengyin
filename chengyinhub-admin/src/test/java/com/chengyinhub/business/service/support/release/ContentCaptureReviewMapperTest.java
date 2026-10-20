package com.chengyinhub.business.service.support.release;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
/** Local SQL only. Does not certify MySQL locking, triggers, collation or concurrency. */
class ContentCaptureReviewMapperTest extends ContentCaptureReviewContract {
    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:review_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        for(String name:new String[]{"20261002_content_capture_snapshot","20261003_content_capture_review_binding"}) {
            Path path=Paths.get("sql/migration_"+name+".sql");if(!Files.exists(path))path=Paths.get("chengyinhub-admin").resolve(path);
            String sql=new String(Files.readAllBytes(path),StandardCharsets.UTF_8);int start=sql.indexOf("CREATE TABLE");
            new JdbcTemplate(source).execute(sql.substring(start,sql.indexOf(';',start)+1).replace(" CHARACTER SET ascii COLLATE ascii_bin",""));
        }
        initialize(source);
    }
}
