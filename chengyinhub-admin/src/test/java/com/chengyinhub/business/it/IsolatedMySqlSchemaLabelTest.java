package com.chengyinhub.business.it;

import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Offline naming contract only; never opens a database or claims engine execution. */
class IsolatedMySqlSchemaLabelTest {
    @Test void everyLiteralScratchSuiteFitsWithTheFullUuidSuffix() throws Exception {
        Path root=Paths.get("src/test/java");
        if(!Files.isDirectory(root))root=Paths.get("chengyinhub-admin/src/test/java");
        assertTrue(Files.isDirectory(root));
        Pattern call=Pattern.compile("\\.open\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");
        Map<String,String> env=new HashMap<>();
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_BASE","cy_module_city");
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX","_"+UUID.randomUUID().toString().replace("-",""));
        int checked=0;
        try(Stream<Path> files=Files.walk(root)) {
            for(Path file:(Iterable<Path>)files.filter(p->p.toString().endsWith(".java"))::iterator) {
                String source=new String(Files.readAllBytes(file),StandardCharsets.UTF_8);
                if(!source.contains("import com.chengyinhub.business.it.support.IsolatedMySqlSchema;"))continue;
                Matcher matcher=call.matcher(source);
                while(matcher.find()) {
                    String suite=matcher.group(1);
                    String name=assertDoesNotThrow(()->MysqlIntegrationTestConfig.from(env,suite).databaseName(),file+":"+suite);
                    assertTrue(name.length()<=64);
                    assertTrue(name.endsWith(env.get("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX")));
                    checked++;
                }
            }
        }
        assertTrue(checked>0,"No scratch schema labels were checked");
    }
}
