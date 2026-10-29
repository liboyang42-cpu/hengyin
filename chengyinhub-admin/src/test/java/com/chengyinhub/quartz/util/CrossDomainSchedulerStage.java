package com.chengyinhub.quartz.util;

import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Only a parent adapter for the EXISTING SchedulerFenceProcessProbe command protocol. */
public final class CrossDomainSchedulerStage implements AutoCloseable {
    private final DataSource source;
    private final JdbcTemplate db;
    private final String schema, original, url, user, password;
    private final List<Child> children = new ArrayList<>();
    private Child first, second;
    private String canonical;

    public CrossDomainSchedulerStage(DataSource source, String schema, String original, String url, String user, String password) {
        assertTrue(schema.matches("cy_wp14_[a-f0-9]{32}")); assertTrue(original.matches("[a-f0-9]{32}"));
        this.source=source; this.db=new JdbcTemplate(source); this.db.setQueryTimeout(3);
        this.schema=schema; this.original=original; this.url=url; this.user=user; this.password=password;
    }
    public void holdOriginalWorkAndLoseLeadership() throws Exception {
        first=child("wp14Task"); second=child("wp14Alias"); canonical=first.canonical;
        assertNotEquals(first.process.pid(),second.process.pid()); assertEquals(canonical,second.canonical);
        first.command("LEAD"); assertEquals("LEADER true",first.await("LEADER "));
        second.command("LEAD"); assertEquals("LEADER false",second.await("LEADER "));
        first.command("RUN original BLOCK_RECEIPT"); first.await("ENTER original");
        Long originalWork=owner("task",canonical); assertNotNull(originalWork);
        killOwnedConnection("leader","all");
        second.command("LEAD"); assertEquals("LEADER true",second.await("LEADER "));
        assertEquals(originalWork,owner("task",canonical));
        second.command("RUN denied RECEIPT"); assertEquals("RESULT denied REJECTED",second.await("RESULT denied "));
        assertEquals(0,effects()); assertEquals(0,attempts());
    }
    public void finishLateWorkerAndKillOnlyOwnedSuccessor() throws Exception {
        killOwnedConnection("task",canonical);
        second.command("RUN successor RECEIPT"); assertEquals("RESULT successor APPLIED",second.await("RESULT successor "));
        assertEquals(1,effects()); assertEquals(1,attempts());
        first.command("RELEASE original"); first.await("RELEASED original");
        assertTrue(first.await("RESULT original ").startsWith("RESULT original UNKNOWN"));
        assertEquals(2,attempts()); assertEquals(1,effects());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM wp14_probe_receipt WHERE original_id=?",Integer.class,original));
        first.close();
        // Additional physical process-loss witness. This is our authenticated READY child only, never Surefire.
        Long leader=owner("leader","all"); assertNotNull(leader);
        assertEquals(schema,db.queryForObject("SELECT DB FROM information_schema.processlist WHERE ID=?",String.class,leader));
        second.killExpected();
        released("leader","all"); released("task",canonical);
        Child third=child("wp14Task"); assertEquals(canonical,third.canonical);
        third.command("LEAD"); assertEquals("LEADER true",third.await("LEADER "));
        third.command("RUN afterdeath RECEIPT"); assertEquals("RESULT afterdeath APPLIED",third.await("RESULT afterdeath "));
        assertEquals(3,attempts(),"a scheduler does not retract/redefine the original domain operation");
        assertEquals(1,effects(),"the original durable fixture receipt, not a new epoch, fences the effect");
    }
    public int effects() { return db.queryForObject("SELECT effects FROM wp14_probe WHERE original_id=?",Integer.class,original); }
    public int attempts() { return db.queryForObject("SELECT attempts FROM wp14_probe WHERE original_id=?",Integer.class,original); }
    private Child child(String alias) throws Exception { Child child=new Child(alias);children.add(child);return child; }
    private Long owner(String kind,String target) throws Exception {
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement("SELECT IS_USED_LOCK(?)")) {
            p.setQueryTimeout(3);p.setString(1,MySqlSessionLock.key(kind,schema,target));
            try(ResultSet row=p.executeQuery()){assertTrue(row.next());long id=row.getLong(1);return row.wasNull()?null:id;}
        }
    }
    private void killOwnedConnection(String kind,String target) throws Exception {
        Long id=owner(kind,target);assertNotNull(id);assertTrue(id>0);
        assertEquals(schema,db.queryForObject("SELECT DB FROM information_schema.processlist WHERE ID=?",String.class,id));
        try(Connection c=DriverManager.getConnection(url,user,password);Statement s=c.createStatement()) {s.setQueryTimeout(3);s.execute("KILL CONNECTION "+id);}
        released(kind,target);
    }
    private void released(String kind,String target) throws Exception {
        // Actual server acquisition barrier: KILL returning alone does not establish release.
        try(MySqlSessionLock.Lease lease=MySqlSessionLock.acquire(source,kind,target,3)) {assertNotNull(lease);}
        assertNull(owner(kind,target));
    }
    public static List<String> childArguments(String scope) {
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));assertNotNull(cp);assertFalse(cp.isBlank());
        List<String> arguments=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",cp,SchedulerFenceProcessProbe.class.getName()));
        // Bound this test child explicitly; heap is still only part of the shared container budget.
        if("CrossDomainCombinedLoadMySqlIT".equals(scope))arguments.add(1,"-Xmx64m");
        return List.copyOf(arguments);
    }

    private final class Child implements AutoCloseable {
        final Process process;
        final long ownedPid;
        final PrintWriter commands;
        final Thread reader;
        final BlockingQueue<String> events=new ArrayBlockingQueue<>(256);
        final List<String> pending=new ArrayList<>();
        String canonical;
        boolean authenticated,expectedKill,closed;
        Child(String alias) throws Exception {
            ProcessBuilder launch=new ProcessBuilder(childArguments(System.getenv("CHENGYIN_HTTP_E2E_TESTS")));
            launch.redirectErrorStream(true);launch.environment().clear();
            launch.environment().putAll(Map.of("RUNNER_ENVIRONMENT","github-hosted","WP14_URL",url,"WP14_SCHEMA",schema,"WP14_ORIGINAL",original,
                    "WP14_ALIAS",alias,"CHENGYIN_MYBATIS_IT_USER",user,"CHENGYIN_MYBATIS_IT_PASSWORD",password));
            if ("CrossDomainCombinedLoadMySqlIT".equals(System.getenv("CHENGYIN_HTTP_E2E_TESTS"))) {
                launch.environment().put("WP14_MEMORY_READBACK", "1");
                for (String key : List.of("SOURCE_SHA", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT")) {
                    String value = System.getenv(key);
                    if (value == null) throw new IllegalStateException("CONTROLLED_MEMORY_SOURCE_BINDING_REQUIRED");
                    launch.environment().put(key, value);
                }
            }
            process=launch.start();ownedPid=process.pid();assertNotEquals(ProcessHandle.current().pid(),ownedPid);
            commands=new PrintWriter(process.getOutputStream(),true,StandardCharsets.UTF_8);
            reader=new Thread(()->{
                try(BufferedReader out=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))) {
                    for(String line;(line=out.readLine())!=null;) {
                        if (line.startsWith("WP14 MEMORY ")) {
                            SchedulerFenceProcessProbe.recordMemoryLine(forwardedMemoryLine(line.substring(12),
                                    System.getenv("SOURCE_SHA"), System.getenv("GITHUB_RUN_ID"), System.getenv("GITHUB_RUN_ATTEMPT"), ownedPid));
                        } else if(line.startsWith("WP14 ")&&!events.offer(line.substring(5)))
                            throw new IllegalStateException("Owned probe event budget exceeded");
                    }
                }catch(Exception e){events.clear();events.offer("FATAL OwnedProbeReader");}
            },"t19-owned-probe-"+ownedPid);reader.setDaemon(true);reader.start();
            try {
                String[] ready=await("READY ").split(" ");assertEquals(ownedPid,Long.parseLong(ready[1]));
                canonical=new String(Base64.getDecoder().decode(ready[2]),StandardCharsets.UTF_8);
                assertEquals(SchedulerFenceProcessProbe.coordinatorBytecodeHash(),ready[3]);authenticated=true;
            }catch(Exception|AssertionError failure){try{close();}catch(Exception|AssertionError cleanup){failure.addSuppressed(cleanup);}throw failure;}
        }
        void command(String command){assertTrue(authenticated);commands.println(command);assertFalse(commands.checkError());}
        String await(String prefix) throws Exception {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(true) {
                for(int i=0;i<pending.size();i++)if(pending.get(i).startsWith(prefix))return pending.remove(i);
                long left=end-System.nanoTime();assertTrue(left>0,"missing owned probe event: "+prefix);
                String next=events.poll(left,TimeUnit.NANOSECONDS);assertNotNull(next,"missing owned probe event: "+prefix);
                assertFalse(next.startsWith("FATAL "),next);assertTrue(pending.size()<256);pending.add(next);
            }
        }
        void killExpected() throws Exception {
            assertTrue(authenticated);assertEquals(ownedPid,process.pid());assertTrue(process.isAlive());
            expectedKill=true;process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS));
            reader.join(3000);assertFalse(reader.isAlive());assertNotEquals(0,process.exitValue());
        }
        @Override public void close() throws Exception {
            if(closed)return;boolean forced=false;
            try {
                if(process.isAlive())commands.println("STOP");
                if(!process.waitFor(10,TimeUnit.SECONDS)){forced=true;process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS));}
            }finally{
                if(process.isAlive()){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}
                commands.close();reader.join(3000);
            }
            assertFalse(process.isAlive());assertFalse(reader.isAlive());assertFalse(forced,"cleanup required an unexpected forced stop");
            if(!expectedKill)assertEquals(0,process.exitValue());closed=true;
        }
    }
    private static String forwardedMemoryLine(String line, String source, String run, String attempt, long pid) {
        if (line == null || line.length() > 4096) throw new IllegalArgumentException("CONTROLLED_MEMORY_PROTOCOL_REQUIRED");
        String[] fields = line.split(" ", -1);
        if (fields.length < 18 || !Objects.equals(source, fields[0]) || !Objects.equals(run, fields[1])
                || !Objects.equals(attempt, fields[2]) || !"SCHEDULER_PROBE".equals(fields[3])
                || !List.of("READY", "LEADER", "ARMED", "RELEASED", "ENTER", "EFFECT", "RESULT", "STOPPED", "TASK_SESSION_KILLED", "FATAL").contains(fields[4]))
            throw new IllegalArgumentException("CONTROLLED_MEMORY_PROTOCOL_REQUIRED");
        for (int i = 5; i < fields.length; i++) {
            if (!fields[i].matches("-?[0-9]{1,19}")) throw new IllegalArgumentException("CONTROLLED_MEMORY_PROTOCOL_REQUIRED");
            Long.parseLong(fields[i]);
        }
        if (Long.parseLong(fields[5]) != pid || pid <= 0) throw new IllegalArgumentException("CONTROLLED_MEMORY_PROTOCOL_REQUIRED");
        int pools = Integer.parseInt(fields[16]);
        if (pools < -1 || pools > 16) throw new IllegalArgumentException("CONTROLLED_MEMORY_PROTOCOL_REQUIRED");
        int cursor = 17 + Math.max(0, pools) * 5;
        if (cursor >= fields.length) throw new IllegalArgumentException("CONTROLLED_MEMORY_PROTOCOL_REQUIRED");
        int buffers = Integer.parseInt(fields[cursor]);
        if (buffers < -1 || buffers > 4 || fields.length != cursor + 1 + Math.max(0, buffers) * 4)
            throw new IllegalArgumentException("CONTROLLED_MEMORY_PROTOCOL_REQUIRED");
        return line;
    }
    @Override public void close() throws Exception {
        List<Throwable> errors=new ArrayList<>();
        for(Child child:children)try{child.close();}catch(Throwable e){errors.add(e);}
        try{released("leader","all");if(canonical!=null)released("task",canonical);}catch(Throwable e){errors.add(e);}
        if(!errors.isEmpty()){Exception failure=new Exception("owned scheduler stage cleanup failed");errors.forEach(failure::addSuppressed);throw failure;}
    }
}
