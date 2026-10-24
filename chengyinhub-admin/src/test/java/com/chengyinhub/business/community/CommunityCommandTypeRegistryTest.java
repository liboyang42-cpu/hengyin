package com.chengyinhub.business.community;

import com.chengyinhub.business.domain.community.CommunityCommandType;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C:U-037:community_post_command_receipt.command_type 不再是自由字符串。
 * 服务层每个写入/回放字面量、Mapper 里按 command_type 过滤的字面量都必须在登记表里。
 */
class CommunityCommandTypeRegistryTest {

    private static final String SERVICE =
            "../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CommunityPostServiceImpl.java";

    @Test
    void registryAcceptsTheThreeShapesAndRejectsTheRest() {
        assertTrue(CommunityCommandType.isKnown("POST_PUBLISH"));
        assertTrue(CommunityCommandType.isKnown("POST_ACTION_LIKE"));
        assertTrue(CommunityCommandType.isKnown("POST_UNACTION_SEE_LESS_TOPIC"));
        assertTrue(CommunityCommandType.isKnown("POST_ENFORCE:0123456789abcdef0123456789abcdef"));
        assertFalse(CommunityCommandType.isKnown(null));
        assertFalse(CommunityCommandType.isKnown(""));
        assertFalse(CommunityCommandType.isKnown("post_publish"));
        assertFalse(CommunityCommandType.isKnown("POST_PUBLISHED"));
        assertFalse(CommunityCommandType.isKnown("POST_ENFORCE:XYZ"));
        assertFalse(CommunityCommandType.isKnown("POST_ENFORCE:0123456789ABCDEF0123456789ABCDEF"));
        assertFalse(CommunityCommandType.isKnown("UNKNOWN_ENFORCE:0123456789abcdef0123456789abcdef"));
    }

    @Test
    void everyServiceLiteralIsRegistered() throws Exception {
        String src = Files.readString(Paths.get(SERVICE), StandardCharsets.UTF_8);
        Set<String> literals = capture(src,
                "(?:isCommandReplay|saveCommandReceipt|trySaveCommandReceipt)\\([^;]*?\"([A-Z_]+)\"");
        assertTrue(literals.size() >= 10, "解析到的字面量太少: " + literals);
        for (String literal : literals) {
            assertTrue(CommunityCommandType.isKnown(literal), "未登记的命令类型字面量: " + literal);
        }
        assertEquals(CommunityCommandType.FINGERPRINTED_PREFIXES,
                capture(src, "fingerprintedCommand\\(\"([A-Z_]+)\""));
        assertTrue(src.contains("\"POST_ACTION_\" + action") && src.contains("\"POST_UNACTION_\" + action"));
        assertTrue(src.contains("if (!CommunityCommandType.isKnown(commandType))"), "写入口必须过登记表");
    }

    @Test
    void mapperCommandTypeFiltersAreRegistered() throws Exception {
        String xml;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("mapper/business/CommunityPostMapper.xml")) {
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Set<String> literals = capture(xml, "command_type='([A-Z_]+)'");
        assertFalse(literals.isEmpty());
        for (String literal : literals) {
            assertTrue(CommunityCommandType.FIXED.contains(literal), "Mapper 过滤了未登记的命令类型: " + literal);
        }
    }

    private static Set<String> capture(String text, String regex) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
