package com.chengyinhub.business.city.draft;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.business.CityBoardDraftController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class CityBoardDraftControllerTest {
    @TempDir Path directory;
    @Test void controllerIsDefaultOffAndHasNoPublishOrRuntimeEndpoint() {
        ConditionalOnProperty condition = CityBoardDraftController.class.getAnnotation(ConditionalOnProperty.class);
        assertFalse(condition.matchIfMissing()); assertEquals("true", condition.havingValue());
        assertArrayEquals(new String[] { "questify.city-board-drafts.enabled" }, condition.name());
        Set<String> methods = new HashSet<>();
        for (java.lang.reflect.Method method : CityBoardDraftController.class.getDeclaredMethods()) if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) methods.add(method.getName());
        assertEquals(new HashSet<>(Arrays.asList("catalog", "reopen", "validate", "save", "preview")), methods);
    }
    @Test void actualAjaxResultWrapsSaveAndExactConflictCode() throws Exception {
        org.h2.jdbcx.JdbcDataSource source = CityBoardDraftPersistenceTest.database(directory.resolve("controller").toString());
        CityBoardDraftPersistenceTest.initialize(source);
        CityBoardDraftPersistenceTest.TestAuthority authority = new CityBoardDraftPersistenceTest.TestAuthority();
        CityBoardDraftController controller = new CityBoardDraftController(CityBoardDraftPersistenceTest.service(source, authority));
        AjaxResult result = controller.save(CityBoardDraftPersistenceTest.command("create", 0, null)); assertEquals(200, result.get("code"));
        AjaxResult conflict = controller.save(CityBoardDraftPersistenceTest.command("stale", 0, null));
        assertEquals(409, conflict.get("code")); assertEquals("CITY_DRAFT_CONFLICT", conflict.get("errorCode"));
        authority.context = new CityBoardDraftAuthority.Context("merchant", Collections.singleton("test-region"), false);
        AjaxResult denied = controller.reopen("test-board"); assertEquals(403, denied.get("code")); assertEquals("PLATFORM_EDITOR_REQUIRED", denied.get("errorCode"));
    }
}
