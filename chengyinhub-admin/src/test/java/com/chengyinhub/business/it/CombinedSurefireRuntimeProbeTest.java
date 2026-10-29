package com.chengyinhub.business.it;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Provider bootstrap only. No Spring context, business owner, database, Redis or provider HTTP. */
public class CombinedSurefireRuntimeProbeTest {
    @Test
    void bootsActualProjectPlatformRuntime() throws Exception {
        assertEquals(21,Runtime.version().feature());
        String command=System.getProperty("sun.java.command","");
        assertTrue(command.contains("surefirebooter-") || command.startsWith("org.apache.maven.surefire.booter.ForkedBooter"));
        assertFalse(command.contains("org.codehaus.plexus.classworlds.launcher.Launcher"));
        assertNotNull(System.getProperty("surefire.test.class.path"));
        var loader=Thread.currentThread().getContextClassLoader();
        var launcher=Class.forName("org.junit.platform.launcher.core.LauncherFactory",false,loader);
        var engine=Class.forName("org.junit.jupiter.engine.JupiterTestEngine",false,loader);
        assertNotNull(launcher);assertNotNull(engine);
        String phase=System.getProperty("combined.provider.phase");
        if(phase==null)return; // Ordinary suite still performs the real fork/class-loading assertions above.
        assertTrue(phase.equals("ONLINE") || phase.equals("OFFLINE"));
        String source=System.getenv("SOURCE_SHA");assertNotNull(source);assertTrue(source.matches("[0-9a-f]{40}"));
        Path basedir=Path.of(System.getProperty("user.dir")).toRealPath();
        assertEquals("chengyinhub-admin",basedir.getFileName().toString());assertTrue(Files.isRegularFile(basedir.resolve("pom.xml")));
        Path directory=basedir.resolve("target/provider-runtime");
        assertFalse(Files.isSymbolicLink(directory));Files.createDirectories(directory,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        assertEquals(directory,directory.toRealPath());
        assertEquals(Files.getOwner(basedir), Files.getOwner(directory));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        var runtime=new LinkedHashMap<String,Object>();
        runtime.put("launcher",jar(launcher,"org/junit/platform/junit-platform-launcher","junit-platform-launcher"));
        runtime.put("engine",jar(engine,"org/junit/jupiter/junit-jupiter-engine","junit-jupiter-engine"));
        runtime.put("platformEngine",jar(Class.forName("org.junit.platform.engine.TestEngine",false,loader),
                "org/junit/platform/junit-platform-engine","junit-platform-engine"));
        runtime.put("api",jar(Test.class,"org/junit/jupiter/junit-jupiter-api","junit-jupiter-api"));
        var mockito=Class.forName("org.mockito.Mockito",false,loader);
        runtime.put("mockito",jar(mockito,"org/mockito/mockito-core","mockito-core"));
        var agents=java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(value->value.startsWith("-javaagent:")).toList();
        assertEquals(1,agents.size());
        assertEquals(Path.of(mockito.getProtectionDomain().getCodeSource().getLocation().toURI()),
                Path.of(agents.getFirst().substring("-javaagent:".length())));
        var proof=new LinkedHashMap<String,Object>();
        proof.put("sourceSha",source);proof.put("phase",phase);proof.put("javaFeature",Runtime.version().feature());
        String run=System.getenv("GITHUB_RUN_ID"),attempt=System.getenv("GITHUB_RUN_ATTEMPT");
        assertNotNull(run);assertNotNull(attempt);assertTrue(run.matches("[1-9][0-9]*"));assertTrue(attempt.matches("[1-9][0-9]*"));
        proof.put("runId",run);proof.put("attempt",attempt);
        proof.put("pid",ProcessHandle.current().pid());proof.put("parentPid",ProcessHandle.current().parent().orElseThrow().pid());
        proof.put("startedAt",ProcessHandle.current().info().startInstant().orElseThrow().toString());
        proof.put("forkedBooter",true);proof.put("commandSha256",sha(command.getBytes(StandardCharsets.UTF_8)));
        try(var input=CombinedSurefireRuntimeProbeTest.class.getResourceAsStream("CombinedSurefireRuntimeProbeTest.class")) {
            assertNotNull(input);proof.put("testClassSha256",sha(input.readAllBytes()));
        }
        proof.put("runtime",runtime);proof.put("businessExecuted",false);
        proof.put("testClasspath", captureTestClasspath(directory, phase,
                System.getProperty("surefire.test.class.path"), basedir.getParent(), Path.of("/cache/repository")));
        Path file=directory.resolve(phase.toLowerCase(java.util.Locale.ROOT)+".json");
        Files.createFile(file,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(file,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(proof),StandardOpenOption.WRITE);
    }

    static Map<String,Object> captureTestClasspath(Path directory, String phase, String classpath,
                                                   Path reactorRoot, Path cacheRoot) throws Exception {
        assertTrue("ONLINE".equals(phase) || "OFFLINE".equals(phase));
        assertNotNull(classpath);
        byte[] bytes = classpath.getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length > 0 && bytes.length < 1024 * 1024, "Bounded original test classpath required");
        assertTrue(classpath.indexOf('\n') < 0 && classpath.indexOf('\r') < 0 && classpath.indexOf('\0') < 0);
        assertTrue(reactorRoot.isAbsolute() && reactorRoot.equals(reactorRoot.toRealPath()));
        assertTrue(cacheRoot.isAbsolute() && cacheRoot.equals(cacheRoot.toRealPath()));
        assertEquals(reactorRoot.resolve("chengyinhub-admin/target/provider-runtime"), directory);
        assertTrue(Files.isDirectory(directory) && directory.equals(directory.toRealPath()));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
        assertEquals(Files.getOwner(reactorRoot), Files.getOwner(directory));
        // Surefire 3.5.6 Classpath.writeToSystemProperty appends one separator per element.
        // Keep those original bytes in the proof; decode exactly its final formatter separator.
        String separator = java.io.File.pathSeparator;
        assertTrue(classpath.endsWith(separator), "Pinned Surefire classpath terminator required");
        String[] entries = classpath.substring(0, classpath.length() - separator.length())
                .split(java.util.regex.Pattern.quote(separator), -1);
        var observed = new java.util.HashSet<Path>();
        for (String entry : entries) {
            assertFalse(entry.isEmpty(), "No implicit current-directory entry");
            Path path = Path.of(entry);
            assertTrue(path.isAbsolute() && path.equals(path.normalize()) && path.equals(path.toRealPath()));
            assertTrue(Files.isRegularFile(path) || Files.isDirectory(path));
            boolean owned = path.startsWith(cacheRoot);
            for (String module : java.util.List.of("chengyinhub-admin", "chengyinhub-framework", "chengyinhub-system",
                    "chengyinhub-quartz", "chengyinhub-generator", "chengyinhub-common"))
                owned |= path.startsWith(reactorRoot.resolve(module).resolve("target"));
            assertTrue(owned, "Only original cache and reactor target classpath entries");
            observed.add(path);
        }
        assertTrue(observed.contains(reactorRoot.resolve("chengyinhub-admin/target/classes"))
                && observed.contains(reactorRoot.resolve("chengyinhub-admin/target/test-classes")));
        String name = phase.toLowerCase(java.util.Locale.ROOT) + ".classpath";
        Path file = directory.resolve(name);
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(file, bytes, StandardOpenOption.WRITE);
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        assertTrue(java.util.Arrays.equals(bytes, Files.readAllBytes(file)), "Exact original UTF-8 bytes must round trip");
        return Map.of("fileName", name, "sha256", sha(bytes), "byteLength", bytes.length, "entryCount", entries.length);
    }

    private static Map<String,String> jar(Class<?> type,String relative,String artifact) throws Exception {
        var uri=type.getProtectionDomain().getCodeSource().getLocation().toURI();assertEquals("file",uri.getScheme());
        Path file=Path.of(uri);assertTrue(Files.isRegularFile(file));assertFalse(Files.isSymbolicLink(file));
        Path base=Path.of("/cache/repository").resolve(relative);
        assertEquals(base,file.getParent().getParent());
        String version=file.getParent().getFileName().toString();assertTrue(version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"));
        assertEquals(artifact+"-"+version+".jar",file.getFileName().toString());assertEquals(file,file.toRealPath());
        try(var input=Files.newInputStream(file)) {
            var digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[16384];int count;
            while((count=input.read(buffer))>=0)digest.update(buffer,0,count);
            return Map.of("version",version,"sha256",HexFormat.of().formatHex(digest.digest()));
        }
    }
    private static String sha(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
