package com.chengyinhub.business.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** C restore fixture transport only. External to BOTH MySQL domains; never an application ledger. */
public final class RestoreProviderJournal {
    private final Path path;
    private final String scope;
    private final String cohort;
    public RestoreProviderJournal() throws Exception {
        require(Runtime.version().feature()==21 && "Linux".equals(System.getProperty("os.name")),"fixed hosted JDK21/Linux child");
        path = Path.of(System.getenv("CHENGYIN_RESTORE_PROVIDER_JOURNAL")).toRealPath();
        scope = System.getenv("CHENGYIN_RESTORE_SCOPE");
        cohort=System.getenv("CHENGYIN_RESTORE_COHORT");
        require(scope != null && scope.matches("[a-f0-9]{32}"), "owned provider namespace");
        require(Set.of("withdrawal-complete","withdrawal-gap","refund-complete","refund-gap","voice-complete","voice-gap").contains(cohort),"fixed provider cohort namespace");
        require(Files.isRegularFile(path) && Files.size(path) <= 1048576, "bounded owned provider store");
    }
    public synchronized void append(String domain, String name, String operation, String state, Map<String,Object> identity) throws Exception {
        require(Set.of("WITHDRAWAL", "REFUND", "VOICE").contains(domain), "fixed restore domain");
        require(Set.of("SUBMIT", "RESULT", "QUERY", "HISTORICAL_ACCEPTANCE").contains(operation), "fixed provider operation");
        var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        String previous = lines.isEmpty() ? "0".repeat(64) : JSON.parseObject(lines.getLast()).getString("sha256");
        var row = new LinkedHashMap<String,Object>();
        row.put("sequence", lines.size()+1); row.put("previous", previous); row.put("scope", scope);
        row.put("cohort",cohort);
        row.put("at", Instant.now().toString()); row.put("domain", domain); row.put("case", name);
        row.put("operation", operation); row.put("state", state); row.put("identity", identity);
        String payload = JSON.toJSONString(row, JSONWriter.Feature.WriteMapNullValue);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        byte[] line = (JSON.toJSONString(Map.of("payload",payload,"sha256",hash))+"\n").getBytes(StandardCharsets.UTF_8);
        try (var file = FileChannel.open(path, StandardOpenOption.APPEND, StandardOpenOption.WRITE); var lock=file.lock()) {
            var bytes=ByteBuffer.wrap(line); while(bytes.hasRemaining()) file.write(bytes); file.force(true);
        }
    }
    public Map<String,Object> original(String domain, String name) throws Exception {
        Map<String,Object> first=null;
        for(String line:Files.readAllLines(path,StandardCharsets.UTF_8)) {
            var row=JSON.parseObject(JSON.parseObject(line).getString("payload"));
            require(scope.equals(row.getString("scope"))&&cohort.equals(row.getString("cohort")),"provider namespace changed");
            if(domain.equals(row.getString("domain")) && name.equals(row.getString("case"))) {
                var identity=row.getJSONObject("identity");
                if(first==null) first=new LinkedHashMap<>(identity);
                else require(JSON.toJSONString(first,JSONWriter.Feature.SortMapEntriesByKeys).equals(
                        JSON.toJSONString(identity,JSONWriter.Feature.SortMapEntriesByKeys)), "original provider identity changed");
            }
        }
        require(first!=null,"provider original missing; never infer NOT_SENT"); return first;
    }
    public String state(String domain,String name) throws Exception {
        String state=null;
        for(String line:Files.readAllLines(path,StandardCharsets.UTF_8)) {
            var row=JSON.parseObject(JSON.parseObject(line).getString("payload"));
            require(scope.equals(row.getString("scope"))&&cohort.equals(row.getString("cohort")),"provider namespace changed");
            if(domain.equals(row.getString("domain")) && name.equals(row.getString("case"))) state=row.getString("state");
        }
        return state;
    }
    public static void evidence(Map<String,Object> value) throws Exception {
        value.put("pid",ProcessHandle.current().pid());
        value.put("startedAt",ProcessHandle.current().info().startInstant().orElseThrow().toString());
        value.put("boot",System.getenv("CHENGYIN_RESTORE_BOOT"));
        Path file=Path.of(System.getenv("CHENGYIN_RESTORE_OWNER_EVIDENCE"));
        Files.createFile(file,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        Files.writeString(file,JSON.toJSONString(value,JSONWriter.Feature.WriteMapNullValue),StandardOpenOption.WRITE);
    }
    public static void require(boolean ok,String reason) { if(!ok) throw new IllegalStateException(reason); }
}
