package com.chengyinhub.business.service.ai.impl;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import com.chengyinhub.business.service.ai.PhotoCheckVisionService;
import com.chengyinhub.business.service.impl.AiCreatorServiceImpl;
import com.chengyinhub.common.config.AiModelConfig;
import com.chengyinhub.framework.config.RedisConfig;
import static org.junit.jupiter.api.Assertions.*;

/** Real Lua with the production FastJson2 serializer, isolated exact keys only. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_AI_RESERVATION_REDIS_IT",matches="1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(40)
class AiQuotaReservationRedisIT {
    private LettuceConnectionFactory factory;
    private RedisTemplate<Object,Object> production;
    private StringRedisTemplate raw;
    private final Set<String> owned = Collections.synchronizedSet(new HashSet<>());
    private Long member;

    private String required(String name) {
        String value=System.getenv(name);
        assertNotNull(value,"explicit task environment required: "+name);
        return value;
    }
    @BeforeAll void connect() {
        String host=required("CHENGYIN_AI_REDIS_IT_HOST");
        assertEquals("127.0.0.1",host);
        int port=Integer.parseInt(required("CHENGYIN_AI_REDIS_IT_PORT"));
        int database=Integer.parseInt(required("CHENGYIN_AI_REDIS_IT_DATABASE"));
        assertTrue(port>0 && port<=65535 && database>=0 && database<=15);
        RedisStandaloneConfiguration config=new RedisStandaloneConfiguration(host,port);
        config.setDatabase(database);
        String password=System.getenv("CHENGYIN_AI_REDIS_IT_PASSWORD");
        if(password!=null && !password.isEmpty()) config.setPassword(password);
        factory=new LettuceConnectionFactory(config);factory.afterPropertiesSet();
        production=new RedisConfig().redisTemplate(factory);
        raw=new StringRedisTemplate(factory);
        assertEquals("PONG",raw.execute((org.springframework.data.redis.core.RedisCallback<String>) connection->connection.ping()));
    }
    @BeforeEach void uniqueMember() {
        member=1000000000000L+Math.floorMod(UUID.randomUUID().getMostSignificantBits(),8000000000000L);
    }
    @AfterEach void cleanup() {
        for(String key:new ArrayList<>(owned)) raw.delete(key);
        owned.clear();
    }
    @AfterAll void disconnect() { if(factory!=null) factory.destroy(); }

    private void claim(List<Object> keys) {
        synchronized(owned) {
            for(Object value:keys) {
                String key=String.valueOf(value);
                if(!owned.contains(key)) {
                    assertFalse(Boolean.TRUE.equals(raw.hasKey(key)),"refusing pre-existing key "+key);
                    owned.add(key);
                }
            }
        }
    }
    private RedisTemplate<Object,Object> recording(final boolean lostReply,final boolean applied,final boolean nullReply) {
        AtomicBoolean first=new AtomicBoolean(true);
        return new RedisTemplate<Object,Object>() {
            @Override public <T> T execute(RedisScript<T> script,List<Object> keys,Object...args) {
                claim(keys);
                if(args.length==3 && first.getAndSet(false) && (lostReply || nullReply)) {
                    T value=null;
                    if(applied) value=production.execute(script,keys,args);
                    if(nullReply) return null;
                    throw new IllegalStateException("simulated client lost reply after applied="+applied);
                }
                return production.execute(script,keys,args);
            }
        };
    }
    private AiGatewayServiceImpl gateway(RedisTemplate<Object,Object> redis) {
        AiGatewayServiceImpl service=new AiGatewayServiceImpl();
        AiModelConfig config=new AiModelConfig();
        ReflectionTestUtils.setField(config,"dailyLimit",20);
        ReflectionTestUtils.setField(config,"npcDailyLimit",100);
        ReflectionTestUtils.setField(service,"photoDailyLimit",30);
        ReflectionTestUtils.setField(service,"aiModelConfig",config);
        ReflectionTestUtils.setField(service,"redisTemplate",redis);
        return service;
    }
    private List<Object> keys(String prefix) {
        String key=prefix+member+":"+UUID.randomUUID().toString().replace("-","");
        List<Object> keys=Arrays.<Object>asList(key,key+":generation",key+":nonce");
        claim(keys);return keys;
    }
    @SuppressWarnings("unchecked") private RedisScript<Long> script(Object source,String name) {
        return (RedisScript<Long>)ReflectionTestUtils.getField(source,name);
    }
    private Long reserve(Object source,List<Object> keys,int capped) {
        return production.execute(script(source,"RESERVE_QUOTA_SCRIPT"),keys,
                Integer.valueOf(86400),Integer.valueOf(capped==1?3:20),Integer.valueOf(capped));
    }
    private Long refund(Object source,List<Object> keys) {
        return production.execute(script(source,"REFUND_QUOTA_SCRIPT"),keys);
    }
    private String key(String token) { return token.substring(0,token.indexOf('|')); }

    @Test void actualGatewayCompensatesAppliedLostReplyOnlyOnce() {
        AiGatewayServiceImpl service=gateway(recording(true,true,false));
        assertThrows(IllegalStateException.class,()->service.consumeQuota(member,"theme_draft"));
        String counter=owned.stream().filter(k->k.startsWith("ai:gen:limit:gen:")&&!k.endsWith(":generation")).findFirst().get();
        assertEquals("0",raw.opsForValue().get(counter));
        String token=service.consumeQuota(member,"theme_draft");
        assertEquals("1",raw.opsForValue().get(key(token)));
        service.refundQuota(member,"theme_draft",token);
        service.refundQuota(member,"theme_draft",token);
        assertEquals("0",raw.opsForValue().get(key(token)));
    }
    @Test void notAppliedLostReplyCancelsLateReservationWithoutCreatingCount() {
        AiGatewayServiceImpl service=gateway(recording(true,false,false));
        assertThrows(IllegalStateException.class,()->service.consumeQuota(member,"photo_check"));
        String receipt=owned.stream().filter(k->k.startsWith("ai:gen:reservation:")).findFirst().get();
        String counter=owned.stream().filter(k->k.startsWith("ai:gen:limit:photo:")&&!k.endsWith(":generation")).findFirst().get();
        assertFalse(Boolean.TRUE.equals(raw.hasKey(counter)));
        assertEquals(Long.valueOf(-1L),reserve(service,Arrays.<Object>asList(counter,counter+":generation",receipt),0));
        assertFalse(Boolean.TRUE.equals(raw.hasKey(counter)));
    }
    @Test void photoUnknownReservationReplyStopsBeforeModelAfterActualCompensation() {
        AiGatewayServiceImpl service=gateway(recording(false,true,true));
        PhotoCheckVisionService photo=new PhotoCheckVisionService();
        ReflectionTestUtils.setField(photo,"aiGatewayService",service);
        IllegalStateException failure=assertThrows(IllegalStateException.class,
                ()->ReflectionTestUtils.invokeMethod(photo,"chargeQuota",member,(String)null));
        assertEquals("AI quota reservation unavailable",failure.getMessage());
        String counter=owned.stream().filter(k->k.startsWith("ai:gen:limit:photo:")&&!k.endsWith(":generation")).findFirst().get();
        assertEquals("0",raw.opsForValue().get(counter));
    }
    @Test void gatewayOldGenerationCannotRefundRecreatedCounter() { generationBoundary(new AiGatewayServiceImpl(),0); }
    @Test void playerOldGenerationCannotRefundRecreatedCounter() { generationBoundary(new AiCreatorServiceImpl(),1); }
    private void generationBoundary(Object service,int capped) {
        List<Object> a=keys("ai:quota:it:");
        assertEquals(Long.valueOf(1),reserve(service,a,capped));
        raw.delete(String.valueOf(a.get(0)));
        List<Object> b=Arrays.<Object>asList(a.get(0),a.get(1),a.get(2)+"-b");claim(b);
        assertEquals(Long.valueOf(1),reserve(service,b,capped));
        assertEquals(Long.valueOf(0),refund(service,a));
        assertEquals("1",raw.opsForValue().get(String.valueOf(a.get(0))));
        assertEquals(Long.valueOf(1),refund(service,b));
        assertEquals("0",raw.opsForValue().get(String.valueOf(a.get(0))));
    }
    @Test void generationLossConservativelyProtectsNewReservations() {
        Object service=new AiGatewayServiceImpl();List<Object>a=keys("ai:quota:it:");
        reserve(service,a,0);raw.delete(String.valueOf(a.get(1)));
        List<Object>b=Arrays.<Object>asList(a.get(0),a.get(1),a.get(2)+"-b");claim(b);reserve(service,b,0);
        assertEquals(Long.valueOf(0),refund(service,a));
        assertEquals("2",raw.opsForValue().get(String.valueOf(a.get(0))));
    }
    @Test void missingOldDayDoesNotRecreateAndReplayDoesNotTouchLaterCounter() {
        Object service=new AiGatewayServiceImpl();List<Object>a=keys("ai:quota:old:");reserve(service,a,0);
        raw.delete(String.valueOf(a.get(0)));
        assertEquals(Long.valueOf(0),refund(service,a));
        assertFalse(Boolean.TRUE.equals(raw.hasKey(String.valueOf(a.get(0)))));
        raw.opsForValue().set(String.valueOf(a.get(0)),"7");
        assertEquals(Long.valueOf(0),refund(service,a));
        assertEquals("7",raw.opsForValue().get(String.valueOf(a.get(0))));
    }
    @Test void invalidCounterRetainsReceiptAndZeroNeverGoesNegative() {
        Object service=new AiCreatorServiceImpl();List<Object>a=keys("ai:quota:it:");reserve(service,a,1);
        String receipt=raw.opsForValue().get(String.valueOf(a.get(2)));
        raw.opsForValue().set(String.valueOf(a.get(0)),"invalid");
        assertThrows(RuntimeException.class,()->refund(service,a));
        assertEquals(receipt,raw.opsForValue().get(String.valueOf(a.get(2))));
        raw.opsForValue().set(String.valueOf(a.get(0)),"0");
        assertEquals(Long.valueOf(0),refund(service,a));
        assertEquals("0",raw.opsForValue().get(String.valueOf(a.get(0))));
    }
    @Test void productionSerializerUsesIntegerTtlAndRejectsLongBeforeWriting() {
        Object service=new AiGatewayServiceImpl();List<Object>a=keys("ai:quota:it:");
        assertThrows(RuntimeException.class,()->production.execute(script(service,"RESERVE_QUOTA_SCRIPT"),a,
                Long.valueOf(86400),Integer.valueOf(20),Integer.valueOf(0)));
        assertFalse(Boolean.TRUE.equals(raw.hasKey(String.valueOf(a.get(0)))));
        assertEquals(Long.valueOf(1),reserve(service,a,0));
        long ttl=raw.getExpire(String.valueOf(a.get(0)),TimeUnit.SECONDS);
        assertTrue(ttl>0 && ttl<=86400);
        long counterTtl=raw.getExpire(String.valueOf(a.get(0)),TimeUnit.MILLISECONDS);
        assertTrue(raw.getExpire(String.valueOf(a.get(1)),TimeUnit.MILLISECONDS)<=counterTtl);
    }
    @Test void positiveCounterTtlDoesNotRenewAndOrdinaryOverLimitStillCounts() {
        Object service=new AiGatewayServiceImpl();List<Object>a=keys("ai:quota:it:");
        raw.opsForValue().set(String.valueOf(a.get(0)),"20",60,TimeUnit.SECONDS);
        assertEquals(Long.valueOf(21),reserve(service,a,0));
        assertTrue(raw.getExpire(String.valueOf(a.get(0)),TimeUnit.SECONDS)<=60);
    }
    @Test void playerConcurrentReservationsCapThreeAndNonceReplayDoesNotIncrement() throws Exception {
        Object service=new AiCreatorServiceImpl();List<Object>a=keys("ai:quota:it:");
        ExecutorService pool=Executors.newFixedThreadPool(4);List<Future<Long>> futures=new ArrayList<>();
        List<List<Object>> requests=new ArrayList<>();
        try {
            for(int i=0;i<4;i++) {
                List<Object> k=Arrays.<Object>asList(a.get(0),a.get(1),a.get(2)+"-"+i);claim(k);requests.add(k);
                futures.add(pool.submit(()->reserve(service,k,1)));
            }
            List<Long> results=new ArrayList<>();for(Future<Long> f:futures)results.add(f.get(10,TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(results,-2L));assertEquals("3",raw.opsForValue().get(String.valueOf(a.get(0))));
            int success=results.indexOf(1L);assertTrue(success>=0);
            assertEquals(Long.valueOf(1L),reserve(service,requests.get(success),1));
            assertEquals("3",raw.opsForValue().get(String.valueOf(a.get(0))));
        } finally {pool.shutdownNow();}
    }
    @Test void concurrentRefundsDebitOnlyOnce() throws Exception {
        Object service=new AiGatewayServiceImpl();List<Object>a=keys("ai:quota:it:");reserve(service,a,0);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<Long>x=pool.submit(()->refund(service,a));Future<Long>y=pool.submit(()->refund(service,a));
            assertEquals(1L,x.get(10,TimeUnit.SECONDS)+y.get(10,TimeUnit.SECONDS));
            assertEquals("0",raw.opsForValue().get(String.valueOf(a.get(0))));
        } finally {pool.shutdownNow();}
    }
    @Test void actualPlayerServiceCompensatesAppliedLostReply() {
        AiCreatorServiceImpl service=new AiCreatorServiceImpl();
        ReflectionTestUtils.setField(service,"redisTemplate",recording(true,true,false));
        assertThrows(RuntimeException.class,()->ReflectionTestUtils.invokeMethod(service,"reservePlayerThemeDraftQuota",member));
        String counter=owned.stream().filter(k->!k.contains("reservation")&&!k.endsWith(":generation")).findFirst().get();
        assertEquals("0",raw.opsForValue().get(counter));
        Object reservation=ReflectionTestUtils.invokeMethod(service,"reservePlayerThemeDraftQuota",member);
        assertNotNull(reservation);
        assertEquals("1",raw.opsForValue().get(counter));
        ReflectionTestUtils.invokeMethod(service,"releasePlayerThemeDraftQuota",reservation);
        assertEquals("0",raw.opsForValue().get(counter));
    }
    @Test void actualRefundUsesOriginalDayAndRejectsOtherMemberOrPool() {
        AiGatewayServiceImpl service=gateway(recording(false,false,false));
        String old="ai:gen:limit:gen:"+member+":20200101";
        String nonce=UUID.randomUUID().toString().replace("-","");
        List<Object> a=Arrays.<Object>asList(old,old+":generation","ai:gen:reservation:"+old+":"+nonce);
        claim(a);reserve(service,a,0);
        String today=service.consumeQuota(member,"theme_draft");
        String oldToken=old+"|"+nonce;
        service.refundQuota(member+1,"theme_draft",oldToken);
        service.refundQuota(member,"photo_check",oldToken);
        assertEquals("1",raw.opsForValue().get(old));
        service.refundQuota(member,"theme_draft",oldToken);
        service.refundQuota(member,"theme_draft",oldToken);
        assertEquals("0",raw.opsForValue().get(old));
        assertEquals("1",raw.opsForValue().get(key(today)));
    }
    @Test void invalidCounterRejectsReserveBeforeReceiptOrCounterChanges() {
        Object service=new AiGatewayServiceImpl();List<Object>a=keys("ai:quota:it:");
        raw.opsForValue().set(String.valueOf(a.get(0)),"1L");
        assertThrows(RuntimeException.class,()->reserve(service,a,0));
        assertEquals("1L",raw.opsForValue().get(String.valueOf(a.get(0))));
        assertFalse(Boolean.TRUE.equals(raw.hasKey(String.valueOf(a.get(1)))));
        assertFalse(Boolean.TRUE.equals(raw.hasKey(String.valueOf(a.get(2)))));
    }

}
