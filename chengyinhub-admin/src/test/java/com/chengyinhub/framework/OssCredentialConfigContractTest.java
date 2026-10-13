package com.chengyinhub.framework;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OssCredentialConfigContractTest
{
    @Test
    void ossCredentialsAreOnlyReadFromRuntimeEnvironment() throws Exception
    {
        Path source = Paths.get(System.getProperty("user.dir"), "../chengyinhub-common/src/main/java/com/chengyinhub/common/utils/file/FileOssUploadUtils.java");
        String java = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

        assertTrue(java.contains("CHENGYIN_OSS_ACCESS_KEY_ID"));
        assertTrue(java.contains("CHENGYIN_OSS_ACCESS_KEY_SECRET"));
        assertFalse(Pattern.compile("accessKey\\s*=\\s*\\\"").matcher(java).find());
        assertFalse(Pattern.compile("keySecret\\s*=\\s*\\\"").matcher(java).find());
    }
}
