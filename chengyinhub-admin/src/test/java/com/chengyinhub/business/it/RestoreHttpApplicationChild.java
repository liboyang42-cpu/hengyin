package com.chengyinhub.business.it;

import com.chengyinhub.RuoYiApplication;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService;
import com.chengyinhub.business.service.support.CommittedBusinessNotice;
import com.chengyinhub.business.service.support.CommittedOfficialNoticeService;
import com.chengyinhub.common.config.RestoreDispatchGuard;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.framework.web.service.AppLoginService;
import com.chengyinhub.quartz.domain.SysJob;
import com.chengyinhub.quartz.service.ISysJobService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.*;

/** Only compiled test classes are mounted alongside the original immutable executable JAR.
 * The real Spring application/security/MVC/transactions/Quartz/mapper beans remain in control.
 * Test endpoints prepare one fixed fixture or read it; they never synthesize a business ACK.
 */
public final class RestoreHttpApplicationChild {
    static final String PROFILE = "restore-http-application-only";
    static final Path PRIVATE = Path.of("/restore-http-private");
    static final ObjectMapper JSON = new ObjectMapper();
    static final String BOOT = UUID.randomUUID().toString();
    static JsonNode config;
    static ConfigurableApplicationContext application;

    public static void main(String[] args) throws Exception {
        require(args.length == 0 && "true".equals(System.getenv("CHENGYIN_CI_RESTORE_HTTP_STARTUP")), "test admission");
        require(Runtime.version().feature() == 21 && Files.isDirectory(PRIVATE) && !Files.isSymbolicLink(PRIVATE), "test mount/JDK");
        config = JSON.readTree(Files.readAllBytes(PRIVATE.resolve("config.json")));
        require(config.path("schema").asText().matches("cy_e2e_auth_[0-9a-f]{32}"), "owned schema");
        require(config.path("sourceSha").asText().matches("[0-9a-f]{40}")
                && config.path("sourceSha").asText().equals(System.getenv("GITHUB_SHA")), "full source");
        require(RestoreHttpApplicationChild.class.getProtectionDomain().getCodeSource().getLocation().toString()
                .startsWith("file:/restore-http-testclasses"), "not a production entry");
        require(sha(Files.readAllBytes(Path.of("/application.jar"))).equals(config.path("jarSha256").asText()), "same original JAR");
        String mode = System.getenv("CHENGYIN_RESTORE_MODE");
        require(Objects.equals(mode, config.path("restoreMode").asText()), "real process environment differs");
        require(Set.of("true", "invalid", "", "false").contains(mode), "fixed four boot modes");
        require(RestoreDispatchGuard.isRestoreMode() == !"false".equals(mode), "real static guard state");
        SpringApplication app = new SpringApplication(RuoYiApplication.class, FixtureEndpoints.class, Observations.class);
        app.setAdditionalProfiles(PROFILE);
        application = app.run("--spring.config.location=file:/fixture/application.properties",
                "--server.address=127.0.0.1", "--server.port=8080", "--spring.messages.basename=i18n/messages",
                "--spring.datasource.druid.master.url=" + config.path("jdbcUrl").asText(),
                "--spring.datasource.druid.master.username=startup_probe",
                "--spring.datasource.druid.master.password=synthetic-local-only",
                "--spring.data.redis.host=" + config.path("redisHost").asText(),
                "--token.secret=" + config.path("tokenSecret").asText(),
                "--coupon.qr-secret=" + config.path("tokenSecret").asText(),
                "--chengyin.jobs.enabled=true", "--spring.quartz.auto-startup=true",
                "--spring.quartz.wait-for-jobs-to-complete-on-shutdown=true",
                "--withdrawal.fast-withdraw.enabled=true",
                "--wx.pay.appid=restore-http-app", "--wx.pay.merchant-id=restore-http-mch");
        var factories=application.getBeansOfType(org.apache.ibatis.session.SqlSessionFactory.class);
        require(!factories.isEmpty(),"real MyBatis factories required");
        factories.values().forEach(factory -> factory.getConfiguration().addInterceptor(new MapperObservations()));
        JdbcTemplate db = application.getBean(JdbcTemplate.class);
        require(config.path("serverUuid").asText().equals(db.queryForObject("SELECT @@server_uuid", String.class))
                && config.path("schema").asText().equals(db.queryForObject("SELECT DATABASE()", String.class)), "actual restored datasource");
        require(application.getEnvironment().getProperty("chengyin.jobs.enabled", Boolean.class, false)
                && application.getEnvironment().getProperty("spring.quartz.wait-for-jobs-to-complete-on-shutdown", Boolean.class, false)
                && application.getBean(org.quartz.Scheduler.class).isStarted(), "real scheduler enabled and shutdown draining");
        var realScheduler = application.getBean(org.quartz.Scheduler.class);
        var quartzFactories = application.getBeansOfType(org.springframework.scheduling.quartz.SchedulerFactoryBean.class);
        require(quartzFactories.values().stream().anyMatch(factory -> factory.getScheduler() == realScheduler
                && Boolean.TRUE.equals(new org.springframework.beans.DirectFieldAccessor(factory)
                        .getPropertyValue("waitForJobsToCompleteOnShutdown"))), "actual Quartz factory must wait on shutdown");
        if (config.path("seed").asBoolean()) {
            require("false".equals(mode) && !Files.exists(PRIVATE.resolve("identities.json")), "seed once in isolated source transport fixture");
            seed(db);
        } else require(Files.isRegularFile(PRIVATE.resolve("identities.json")), "original identities required; no reseed");
        var ready = JSON.createObjectNode().put("sourceSha", config.path("sourceSha").asText())
                .put("jarSha256", config.path("jarSha256").asText()).put("bootId", BOOT)
                .put("javaPid", ProcessHandle.current().pid()).put("serverUuid", config.path("serverUuid").asText())
                .put("schema", config.path("schema").asText()).put("restoreMode", mode)
                .put("guardHeld", RestoreDispatchGuard.isRestoreMode()).put("schedulerStarted", true)
                .put("port", ((ServletWebServerApplicationContext) application).getWebServer().getPort());
        write(PRIVATE.resolve("ready.json"), ready);
        application.registerShutdownHook();
    }

    static void seed(JdbcTemplate db) throws Exception {
        require(db.queryForObject("SELECT COUNT(*) FROM cms_registration", Integer.class) == 0
                && db.queryForObject("SELECT COUNT(*) FROM game_command_receipt", Integer.class) == 0, "fresh owned fixture");
        ObjectNode ids = JSON.createObjectNode();
        for (int i=0;i<2;i++) {
            UmsMember m = new UmsMember(); m.setPhone(i==0?"13800000891":"13800000892");
            m.setBalance(new java.math.BigDecimal(i==0?"100.00":"0.00"));
            m.setNickname("隔离恢复HTTP"+i); m.setStatus(1L);m.setDelFlag(0);m.setWxOpenId("restore-http-"+UUID.randomUUID());
            require(application.getBean(UmsMemberMapper.class).insertUmsMember(m)==1, "member insert");
            HttpServletRequest request=(HttpServletRequest)Proxy.newProxyInstance(RestoreHttpApplicationChild.class.getClassLoader(),
                    new Class<?>[]{HttpServletRequest.class}, (p,method,values)-> {
                        if(method.getName().equals("getRemoteAddr"))return "127.0.0.1";
                        if(method.getName().equals("getHeader"))return "User-Agent".equals(values[0])?"Owned restore HTTP fixture":null;
                        if(method.getReturnType()==boolean.class)return false;
                        if(method.getReturnType()==int.class)return 0;
                        if(method.getReturnType()==long.class)return 0L;
                        return null;
                    });
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            String token;
            try {token=application.getBean(AppLoginService.class).loginByMember(m,"phone",m.getPhone(),null);}
            finally {RequestContextHolder.resetRequestAttributes();}
            ids.putObject(i==0?"a":"b").put("memberId",m.getId()).put("token",token);
        }
        long actor=ids.path("a").path("memberId").asLong();
        long club=insert(db,"INSERT INTO club(member_id,name,del_flag) VALUES(?,'restore-http',0)",actor);
        long topic=insert(db,"INSERT INTO cms_topic(member_id,name,del_flag) VALUES(?,'restore-http',0)",actor);
        long first=insert(db,"INSERT INTO cms_topic_chapter(topic_id,name,sort_id) VALUES(?,'first',1)",topic);
        long second=insert(db,"INSERT INTO cms_topic_chapter(topic_id,name,sort_id) VALUES(?,'second',2)",topic);
        long activity=insert(db,"INSERT INTO cms_activity(topic_id,member_id,club_id,del_flag) VALUES(?,?,?,0)",topic,actor,club);
        db.update("INSERT INTO club_lead_state(activity_id,leader_member_id,current_chapter_id,status,create_time) VALUES(?,?,?,2,NOW())",activity,actor,first);
        ids.putObject("chapter").put("activityId",activity).put("expectedChapterId",first).put("targetChapterId",second)
                .put("commandId","restore-http-"+UUID.randomUUID());
        // Explicit historical unpaid fixture: no fund_fact/intent is fabricated from current channel defaults.
        // Its controlled provider fact is introduced ONLY after normal complete-facts readback has ended.
        String no="RH"+UUID.randomUUID().toString().replace("-", "").substring(0,24);
        long registration=insert(db,"INSERT INTO cms_registration(registration_no,owner_type,owner_id,ticket_id,ticket_price,member_id,"
                +"original_amount,total_amount,payable_amount,wechat_payment_amount,payment_status,registration_status,pay_expire_time) "
                +"VALUES(?,1,?,1,1.00,?,1.00,1.00,1.00,1.00,1,1,DATE_ADD(NOW(),INTERVAL 1 DAY))",no,topic,actor);
        ids.put("registrationId",registration).put("registrationNo",no).put("noticeKey","restore-http:"+config.path("scope").asText())
                .put("transactionId","RH_TX_"+config.path("scope").asText())
                .put("successAt",Instant.now().toString());
        ISysJobService jobs=application.getBean(ISysJobService.class);
        ObjectNode jobIds=ids.putObject("jobs");
        for(String target:List.of("beforeStartNotifyTask.run()","committedOfficialNoticeTask.deliverPending()")) {
            List<Long> existing=db.queryForList("SELECT job_id FROM sys_job WHERE invoke_target=?",Long.class,target);
            require(existing.size()<=1,"duplicate original task definitions");
            Long id;
            if(existing.isEmpty()) {
                SysJob job=new SysJob();job.setJobName("restore-http-"+jobIds.size());job.setJobGroup("DEFAULT");
                job.setInvokeTarget(target);job.setCronExpression("0 0 0 1 1 ? 2099");job.setStatus("1");
                job.setConcurrent("1");job.setMisfirePolicy("3");jobs.insertJob(job);id=job.getJobId();
            } else id=existing.getFirst();
            jobIds.put(target,id);
        }
        write(PRIVATE.resolve("identities.json"),ids);
    }

    @RestController
    @Profile(PROFILE)
    @RequestMapping("/__restore_http")
    @PreAuthorize("@ss.hasPermi('monitor:job:query')")
    public static class FixtureEndpoints {
        final JdbcTemplate db;final CommittedOfficialNoticeService notices;final CommittedBusinessNotice wake;
        final PlatformTransactionManager tx;final WechatVerifiedPaymentFactService facts;final ICmsRegistrationService registrations;
        public FixtureEndpoints(JdbcTemplate db,CommittedOfficialNoticeService notices,CommittedBusinessNotice wake,
                PlatformTransactionManager tx,WechatVerifiedPaymentFactService facts,ICmsRegistrationService registrations) {
            this.db=db;this.notices=notices;this.wake=wake;this.tx=tx;this.facts=facts;this.registrations=registrations;
        }
        @GetMapping("/evidence") public AjaxResult evidence() throws Exception {
            JsonNode ids=identities();
            return AjaxResult.success(Map.of("bootId",BOOT,"held",RestoreDispatchGuard.isRestoreMode(),
                    "serverUuid",db.queryForObject("SELECT @@server_uuid",String.class),
                    "schema",db.queryForObject("SELECT DATABASE()",String.class),"events",events(),
                    "verifiedFacts",db.queryForList("SELECT id,fact_hash FROM wx_verified_payment_fact WHERE business_out_trade_no=?",ids.path("registrationNo").asText()),
                    "inbox",db.queryForList("SELECT id,biz_id,state FROM wx_payment_callback_inbox WHERE biz_id=? AND biz_type=1",ids.path("registrationId").asLong())));
        }
        @PostMapping("/notice") public AjaxResult notice() throws Exception {
            require(RestoreDispatchGuard.isRestoreMode(),"fixture writes only while held");
            JsonNode ids=identities();
            List<Long> existing=db.queryForList("SELECT id FROM committed_official_notice_wx WHERE event_key=?",Long.class,ids.path("noticeKey").asText());
            require(existing.size()<=1,"one original durable notice");
            Long id=existing.isEmpty()?new TransactionTemplate(tx).execute(s -> notices.enqueueOfficial(ids.path("noticeKey").asText(),
                    ids.path("a").path("memberId").asLong(),"隔离恢复夹具","固定原通知内容")):existing.getFirst();
            // Actual persisted intent first, original legacy wake next; Blocked must escape its actual owner.
            wake.dispatchInNewTransaction(ids.path("noticeKey").asText(),id,()-> {
                providerAttempt("legacy-notice-supplier");throw new IllegalStateException("CONTROLLED_PROVIDER_NO_SEND");
            });
            throw new AssertionError("restore wake must throw original Blocked");
        }
        @PostMapping("/verified-callback") public AjaxResult callback() throws Exception {
            require(RestoreDispatchGuard.isRestoreMode(),"late fixture facts never normal-boot admission");
            JsonNode ids=identities();String no=ids.path("registrationNo").asText(), transaction=ids.path("transactionId").asText();
            WechatVerifiedPaymentFact fact=new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,
                    "restore-http-mch","restore-http-app",no,no,no,transaction,transaction,"JSAPI","CNY",100,100,
                    Instant.parse(ids.path("successAt").asText()),null,"SUCCESS");
            // Controlled external evidence survives restore independently. No SDK success or signature is claimed.
            Path provider=PRIVATE.resolve("provider-facts.jsonl");
            if(!Files.exists(provider))append("provider-facts.jsonl",Map.of("kind","CONTROLLED_HISTORICAL_PAYMENT_SUCCESS",
                    "sourceSha",config.path("sourceSha").asText(),"identityHash",fact.factHash()));
            else require(JSON.readTree(Files.readString(provider).strip()).path("identityHash").asText().equals(fact.factHash()),"same original provider fact");
            var reference=facts.recordVerified(fact);
            Map<String,String> result=new HashMap<>();
            result.put("out_trade_no",no);result.put("wx_out_trade_no",no);result.put("transaction_id",transaction);
            result.put("mchid",fact.mchid());result.put("appid",fact.appid());result.put("trade_type","JSAPI");
            result.put("currency","CNY");result.put("total","100");result.put("trade_state","SUCCESS");
            result.put("verified_fact_id",Long.toString(reference.id()));result.put("verified_fact_hash",reference.hash());
            String original=registrations.handleWechatPayCallback(result);
            return AjaxResult.success(Map.of("originalOutcome",original,"factId",reference.id(),"factHash",reference.hash()));
        }
    }

    /** Observes original entry/exception without short-circuiting its guard or making a response. */
    @Aspect @Order(Ordered.HIGHEST_PRECEDENCE+50) @Profile(PROFILE)
    public static class Observations {
        @Around("execution(* com.chengyinhub.business.service.impl.CashWithdrawServiceImpl.withdraw(..)) || "
                +"execution(* com.chengyinhub.business.service.impl.RefundServiceImpl.saveBackOfficeRefund(..)) || "
                +"execution(* com.chengyinhub.business.service.support.CommittedBusinessNotice.dispatchInNewTransaction(..)) || "
                +"execution(* com.chengyinhub.business.task.BeforeStartNotifyTask.run(..)) || "
                +"execution(* com.chengyinhub.business.task.CommittedOfficialNoticeTask.deliverPending(..)) || "
                +"execution(* com.chengyinhub.business.service.support.CommittedOfficialNoticeService.reconcilePending(..)) || "
                +"execution(* com.chengyinhub.business.service.impl.TeamGroupServiceImpl.notifyBeforeStartBatch(..))")
        public Object original(ProceedingJoinPoint call) throws Throwable {
            String symbol=call.getSignature().getDeclaringType().getSimpleName()+"."+call.getSignature().getName();
            append("events.jsonl",Map.of("bootId",BOOT,"symbol",symbol,"stage","ENTER","held",RestoreDispatchGuard.isRestoreMode()));
            try {
                Object value=call.proceed();
                append("events.jsonl",Map.of("bootId",BOOT,"symbol",symbol,"stage","RETURN","held",RestoreDispatchGuard.isRestoreMode()));
                return value;
            } catch(Throwable failure) {
                append("events.jsonl",Map.of("bootId",BOOT,"symbol",symbol,"stage","THROW", "held",RestoreDispatchGuard.isRestoreMode(),
                        "errorType",failure.getClass().getName(),"blocked",failure instanceof RestoreDispatchGuard.Blocked));
                throw failure;
            }
        }
        // Second, test-only transport fence. Invoking it is a failure, never evidence of the production guard.
        @Around("execution(public * com.chengyinhub.business.service.impl.WechatPaymentSdkGateway.*(..)) && "
                +"!execution(* *.init(..)) && !execution(* *.isReady(..)) && !execution(* *.isAppReady(..))")
        public Object paymentTransport(ProceedingJoinPoint call) throws Throwable {
            String method=call.getSignature().getName();
            // The sole controlled transport is the original, test-owned complete withdrawal. All other
            // SDK operations fail before provider entry. This never overrides an owner restore guard.
            if(config.path("seed").asBoolean() && !RestoreDispatchGuard.isRestoreMode()
                    && call.getArgs().length==1 && call.getArgs()[0] instanceof CashWithdrawTransferIntent original) {
                require(original.amountFen()==3000 && original.mchid().equals("restore-http-mch")
                        && original.appid().equals("restore-http-app"),"fixed provider wire identity");
                String hash=CashWithdrawTransferIntent.fromRequest(original.withdrawalId(),original.mchid(),original.appid(),
                        original.outBatchNo(),original.outDetailNo(),original.amountYuan(),original.openid(),original.effectiveRemark()).requestHash();
                if(method.equals("transferToBalance")) {
                    require(!Files.exists(PRIVATE.resolve("withdrawal-provider.json")),"no second provider submit");
                    write(PRIVATE.resolve("withdrawal-provider.json"),Map.of("wireHash",hash,"withdrawalId",original.withdrawalId(),
                            "amountFen",original.amountFen(),"outBatchNo",original.outBatchNo(),"outDetailNo",original.outDetailNo(),
                            "requestVersion",original.requestVersion(),"requestHash",original.requestHash(),"state","SUCCESS","submits",1));
                    return Map.of("ok","true","batchId","fixture-batch-"+original.withdrawalId(),
                            "batchStatus","ACCEPTED","requestHash",original.requestHash(),"notSent","false");
                }
                if(method.equals("queryTransferResult")) {
                    JsonNode observed=JSON.readTree(Files.readAllBytes(PRIVATE.resolve("withdrawal-provider.json")));
                    require(hash.equals(observed.path("wireHash").asText()) && observed.path("submits").asInt()==1,"query original external identity");
                    Map<String,String> result=new HashMap<>();
                    result.put("result","SUCCESS");result.put("detailStatus","SUCCESS");result.put("batchStatus","FINISHED");
                    result.put("batchId","fixture-batch-"+original.withdrawalId());result.put("detailId","fixture-detail-"+original.withdrawalId());
                    result.put("mchid",original.mchid());result.put("appid",original.appid());result.put("outBatchNo",original.outBatchNo());
                    result.put("outDetailNo",original.outDetailNo());result.put("openid",original.openid());result.put("transferAmount",Long.toString(original.amountFen()));
                    return result;
                }
            }
            providerAttempt(method);throw new IllegalStateException("CONTROLLED_PROVIDER_NO_SEND");
        }
        @Around("execution(* com.chengyinhub.business.notify.WxSubscribeChannel.send(..))")
        public Object noticeTransport(ProceedingJoinPoint call) throws Exception {
            providerAttempt(call.getSignature().getName());throw new IllegalStateException("CONTROLLED_PROVIDER_NO_SEND");
        }
    }
    /** Additive test observer on the real factory; no mapper result/SQL/transaction is replaced. */
    @org.apache.ibatis.plugin.Intercepts({
        @org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,method="query",args={
            org.apache.ibatis.mapping.MappedStatement.class,Object.class,org.apache.ibatis.session.RowBounds.class,org.apache.ibatis.session.ResultHandler.class}),
        @org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,method="query",args={
            org.apache.ibatis.mapping.MappedStatement.class,Object.class,org.apache.ibatis.session.RowBounds.class,org.apache.ibatis.session.ResultHandler.class,
            org.apache.ibatis.cache.CacheKey.class,org.apache.ibatis.mapping.BoundSql.class}),
        @org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,method="update",args={
            org.apache.ibatis.mapping.MappedStatement.class,Object.class})})
    public static class MapperObservations implements org.apache.ibatis.plugin.Interceptor {
        public Object intercept(org.apache.ibatis.plugin.Invocation call) throws Throwable {
            var statement=(org.apache.ibatis.mapping.MappedStatement)call.getArgs()[0];
            append("events.jsonl",Map.of("bootId",BOOT,"symbol",statement.getId(),"stage","SQL","held",RestoreDispatchGuard.isRestoreMode()));
            return call.proceed();
        }
    }
    static JsonNode identities() throws Exception {return JSON.readTree(Files.readAllBytes(PRIVATE.resolve("identities.json")));}
    static List<JsonNode> events() throws Exception {
        Path path=PRIVATE.resolve("events.jsonl");if(!Files.exists(path))return List.of();
        List<JsonNode> rows=new ArrayList<>();for(String line:Files.readAllLines(path))rows.add(JSON.readTree(line));return rows;
    }
    static void providerAttempt(String symbol) {
        try {append("provider-attempts.jsonl",Map.of("bootId",BOOT,"symbol",symbol));}
        catch(Exception failure){throw new IllegalStateException("private provider evidence unavailable",failure);}
    }
    static synchronized void append(String name,Object value) throws Exception {
        Path path=PRIVATE.resolve(name);require(Set.of("events.jsonl","provider-facts.jsonl","provider-attempts.jsonl").contains(name),"fixed journal");
        try(FileChannel file=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND)) {
            Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
            require(file.size()<8L*1024*1024,"bounded private observation journal");
            ByteBuffer bytes=ByteBuffer.wrap((JSON.writeValueAsString(value)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            while(bytes.hasRemaining())file.write(bytes);file.force(true);
        }
    }
    static long insert(JdbcTemplate db,String sql,Object... values) {
        GeneratedKeyHolder keys=new GeneratedKeyHolder();db.update(connection->{PreparedStatement statement=connection.prepareStatement(sql,new String[]{"id"});
            for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);return statement;},keys);return Objects.requireNonNull(keys.getKey()).longValue();
    }
    static String sha(byte[] value)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    static void write(Path file,Object value)throws Exception {
        require(!Files.exists(file),"exclusive evidence");
        try(FileChannel channel=FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
            ByteBuffer bytes=ByteBuffer.wrap(JSON.writeValueAsBytes(value));
            while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
        }
    }
    static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
