package com.chengyinhub.business.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aopalliance.intercept.MethodInterceptor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.*;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only observer for the two existing M1 storage suites. No production/M1 type dependency.
 * It records attempted mapper calls AND actual JDBC statement executions. It never changes a request,
 * result, transaction, lock or fixture row. Snapshot SELECTs run only outside business transactions. */
@Intercepts({
    @Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}),
    @Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class}),
    @Signature(type=StatementHandler.class,method="prepare",args={Connection.class,Integer.class}),
    @Signature(type=StatementHandler.class,method="query",args={Statement.class,ResultHandler.class}),
    @Signature(type=StatementHandler.class,method="update",args={Statement.class})
})
public final class Wp09M1EquivalenceTrace implements Interceptor {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final InheritableThreadLocal<Capture> CURRENT=new InheritableThreadLocal<>();
    private static final AtomicReference<Capture> RUNNING=new AtomicReference<>();
    private static final Map<String,AtomicLong> ORDINALS=new ConcurrentHashMap<>();
    private static final ThreadLocal<Deque<Long>> CALLS=ThreadLocal.withInitial(ArrayDeque::new);
    private static final Set<String> CONTROLLER_METHODS=Set.of("unlockHint","answer","preference","submitPreference");

    public static Capture begin(String suite,String name,JdbcTemplate jdbc) throws Exception {
        String sha=System.getenv("SOURCE_SHA");
        if(sha==null||!sha.matches("[0-9a-f]{40}"))throw new IllegalStateException("Equivalence needs the controlled SOURCE_SHA");
        if(!Set.of("PlayHintUnlockMySqlIT","PlayLeadAdmissionFailureMySqlIT").contains(suite.substring(suite.lastIndexOf('.')+1)))
            throw new IllegalArgumentException("Only the two M1 fixture owners are observed");
        Capture capture=new Capture(suite,name,sha,jdbc);
        if(!RUNNING.compareAndSet(null,capture))throw new IllegalStateException("Equivalence cases must be sequential; intra-case workers are supported");
        CURRENT.set(capture);
        try {capture.before=capture.snapshot();capture.timestampCodec=capture.timestampCodec();return capture;}
        catch(Exception|Error failed) {CURRENT.remove();RUNNING.compareAndSet(capture,null);throw failed;}
    }
    private static Capture active() {
        Capture capture=CURRENT.get();
        if(capture==null&&RUNNING.get()!=null)throw new IllegalStateException("Unpropagated equivalence worker context");
        if(capture!=null&&RUNNING.get()!=capture)throw new IllegalStateException("Late equivalence worker");
        return capture;
    }
    public static MethodInterceptor controller(long actor) {
        return invocation -> {
            Capture capture=active();
            if(capture==null||!CONTROLLER_METHODS.contains(invocation.getMethod().getName()))return invocation.proceed();
            if(TransactionSynchronizationManager.isActualTransactionActive())
                throw new IllegalStateException("Controller observer must wrap the original transaction advice");
            long call=capture.calls.incrementAndGet();CALLS.get().push(call);capture.inFlight.incrementAndGet();
            Map<String,Object> frame=map("call",call,"method",invocation.getMethod().getName(),"actor",scalar(actor),
                    "arguments",freeze(Arrays.asList(invocation.getArguments())));
            synchronized(capture.frames) {capture.frames.add(frame);}
            try {
                if(!capture.race)frame.put("before",capture.snapshot());
                capture.event("call.begin",map("input",map("method",frame.get("method"),"actor",frame.get("actor"),"arguments",frame.get("arguments"))));
                try {
                    Object response=invocation.proceed();frame.put("outcome",map("kind","return","value",freeze(response)));return response;
                } catch(Throwable failed) {frame.put("outcome",map("kind","throw","exception",failure(failed)));throw failed;}
                finally {
                    frame.put("argumentsAfter",freeze(Arrays.asList(invocation.getArguments())));
                    capture.event("call.end",map("outcome",frame.get("outcome")));
                    if(!capture.race)frame.put("after",capture.snapshot());
                }
            } finally {CALLS.get().pop();capture.inFlight.decrementAndGet();}
        };
    }

    @Override public Object plugin(Object target) {return Plugin.wrap(target,this);}
    @Override public void setProperties(Properties properties) { }
    @Override public Object intercept(Invocation invocation) throws Throwable {
        Capture capture=active();if(capture==null)return invocation.proceed();
        MappedStatement statement;BoundSql bound;String kind;
        if(invocation.getTarget() instanceof Executor) {
            statement=(MappedStatement)invocation.getArgs()[0];bound=statement.getBoundSql(invocation.getArgs()[1]);kind="mapper";
        } else {
            StatementHandler handler=(StatementHandler)invocation.getTarget();bound=handler.getBoundSql();kind="db";
            Object target=SystemMetaObject.forObject(handler).getValue("delegate.mappedStatement");
            if(!(target instanceof MappedStatement))throw new IllegalStateException("Cannot identify actual mapped JDBC statement");
            statement=(MappedStatement)target;
        }
        String operation=invocation.getMethod().getName();
        String tx=capture.transaction(statement.getConfiguration().getEnvironment().getDataSource());
        Map<String,Object> data=map("statement",statement.getId(),"command",statement.getSqlCommandType().name(),
                "operation",operation,"sql",bound.getSql(),"parameters",parameters(statement,bound),
                "useCache",statement.isUseCache(),"flushCache",statement.isFlushCacheRequired(),"transaction",tx);
        if("prepare".equals(operation))data.put("connectionHandle",System.identityHashCode(invocation.getArgs()[0]));
        long span=capture.spans.incrementAndGet();data.put("span",span);
        capture.event(kind+".begin",data);
        try {
            Object result=invocation.proceed();
            Map<String,Object> done=new LinkedHashMap<>(data);
            done.put("result",result instanceof Number?scalar(result):result instanceof List<?>?map("rows",((List<?>)result).size()):map("type",result==null?null:result.getClass().getName()));
            // JDBC implementation/pooled wrapper class names are raw diagnostics, not response data.
            if("prepare".equals(operation))done.put("result",map("prepared",result!=null));
            capture.event(kind+".end",done);return result;
        } catch(Throwable failed) {Map<String,Object> done=new LinkedHashMap<>(data);done.put("exception",failure(failed));capture.event(kind+".end",done);throw failed;}
    }
    private static List<Object> parameters(MappedStatement statement,BoundSql bound) {
        List<Object> result=new ArrayList<>();Object input=bound.getParameterObject();
        for(ParameterMapping parameter:bound.getParameterMappings()) {
            String name=parameter.getProperty();Object value;
            if(bound.hasAdditionalParameter(name))value=bound.getAdditionalParameter(name);
            else if(input==null)value=null;
            else if(statement.getConfiguration().getTypeHandlerRegistry().hasTypeHandler(input.getClass()))value=input;
            else value=statement.getConfiguration().newMetaObject(input).getValue(name);
            result.add(map("property",name,"javaType",parameter.getJavaType().getName(),"jdbcType",parameter.getJdbcType()==null?null:parameter.getJdbcType().name(),
                    "mode",parameter.getMode().name(),"value",freeze(value)));
        }
        return result;
    }
    private static Object scalar(Object value) {
        if(value==null||value instanceof String||value instanceof Boolean)return value;
        if(value instanceof Number)return map("kind","number","javaType",value.getClass().getName(),"value",value instanceof BigDecimal?((BigDecimal)value).toPlainString():value.toString());
        if(value instanceof java.util.Date)return map("kind","time","javaType",value.getClass().getName(),"epochMillis",((java.util.Date)value).getTime(),"value",value.toString());
        if(value instanceof byte[])return map("kind","bytes","value",Base64.getEncoder().encodeToString((byte[])value));
        if(value instanceof Enum<?>)return map("kind","enum","javaType",value.getClass().getName(),"value",((Enum<?>)value).name());
        throw new IllegalArgumentException("Unsupported evidence scalar: "+value.getClass().getName());
    }
    private static Object freeze(Object value) {
        if(value instanceof Map<?,?> source) {Map<String,Object> copy=new TreeMap<>();source.forEach((k,v)->copy.put(String.valueOf(k),freeze(v)));return copy;}
        if(value instanceof Collection<?> source) {List<Object> copy=new ArrayList<>();for(Object item:source)copy.add(freeze(item));return copy;}
        if(value instanceof Object[] array)return freeze(Arrays.asList(array));
        // Freeze only the existing preference request's declared input fields, without invoking getters.
        if(value!=null&&value.getClass().getName().equals("com.chengyinhub.business.domain.dto.PreferenceSubmitRequest")) {
            Map<String,Object> fields=new TreeMap<>();
            for(String name:List.of("activityId","topicId","choices","reuseTagCode","routeActionId","expectedRouteVersion")) {
                try {Field field=value.getClass().getDeclaredField(name);field.setAccessible(true);fields.put(name,freeze(field.get(value)));}
                catch(ReflectiveOperationException failed) {throw new IllegalStateException("Cannot freeze original preference input",failed);}
            }
            return map("kind","originalPreferenceInput","javaType",value.getClass().getName(),"fields",fields);
        }
        // No general entity getter traversal or M1-only record dependency.
        return scalar(value);
    }
    private static Map<String,Object> failure(Throwable original) {
        Throwable failure=original;
        while((failure instanceof InvocationTargetException||failure instanceof UndeclaredThrowableException)&&failure.getCause()!=null)failure=failure.getCause();
        Map<String,Object> result=map("class",failure.getClass().getName(),"message",failure.getMessage());
        for(String getter:List.of("getCode","getData","getDetailMessage")) {
            try {result.put(getter,freeze(failure.getClass().getMethod(getter).invoke(failure)));}
            catch(NoSuchMethodException absent) { /* Non-ServiceException has no such public contract. */ }
            catch(ReflectiveOperationException failed) {throw new IllegalStateException("Cannot observe exception contract",failed);}
        }
        if(failure instanceof SQLException sql) {result.put("sqlState",sql.getSQLState());result.put("vendorCode",sql.getErrorCode());}
        if(failure.getCause()!=null&&failure.getCause()!=failure)result.put("cause",failure(failure.getCause()));
        return result;
    }
    private static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;
    }

    public static final class Capture {
        private final String suite,name,sourceSha;
        private final JdbcTemplate jdbc;
        private final boolean race;
        private final long ordinal;
        private final long started=System.currentTimeMillis();
        private final AtomicLong sequence=new AtomicLong(),calls=new AtomicLong(),spans=new AtomicLong();
        private final AtomicInteger inFlight=new AtomicInteger();
        private final List<Object> events=Collections.synchronizedList(new ArrayList<>());
        private final List<Map<String,Object>> frames=Collections.synchronizedList(new ArrayList<>());
        private final IdentityHashMap<Object,String> transactions=new IdentityHashMap<>();
        private Map<String,Object> before;
        private Map<String,Object> timestampCodec;
        private Capture(String suite,String name,String sourceSha,JdbcTemplate jdbc) {
            this.suite=suite;this.name=name;this.sourceSha=sourceSha;this.jdbc=jdbc;
            this.ordinal=ORDINALS.computeIfAbsent(suite,k->new AtomicLong()).incrementAndGet();
            this.race=Set.of("twoConnectionsSerializeOriginalRegistrationAndDebitOnce","balanceLostAfterPrecheckRollsBackHintLedgerButRetainsOtherCommittedSpend").contains(name);
        }
        private void event(String kind,Map<String,Object> data) {
            Map<String,Object> event=new LinkedHashMap<>(data);
            event.put("seq",sequence.incrementAndGet());event.put("kind",kind);
            event.put("call",CALLS.get().isEmpty()?0L:CALLS.get().peek());
            event.put("thread",Thread.currentThread().getName()+":"+Thread.currentThread().threadId());
            event.put("jvmMillis",System.currentTimeMillis());
            event.put("actualTransaction",TransactionSynchronizationManager.isActualTransactionActive());
            event.put("readOnly",TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            event.put("isolation",TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            events.add(event);
        }
        private synchronized String transaction(DataSource source) {
            if(!TransactionSynchronizationManager.isActualTransactionActive())return "NONE";
            Object holder=TransactionSynchronizationManager.getResource(source);
            if(holder==null)throw new IllegalStateException("Actual transaction has no original data-source resource");
            String existing=transactions.get(holder);if(existing!=null)return existing;
            String label="tx"+(transactions.size()+1);transactions.put(holder,label);
            long logical=CALLS.get().isEmpty()?0L:CALLS.get().peek();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    event("tx.complete",map("transaction",label,"completion",status,"logicalCall",logical));
                }
            });
            return label;
        }
        private Map<String,Object> timestampCodec() throws Exception {
            if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Timestamp probe must be outside business transactions");
            // Read-only probe of the exact setTimestamp/getTimestamp DATETIME codec. No fixture row is written.
            long anchor=java.time.Instant.parse("2000-01-02T03:04:05.123Z").toEpochMilli();
            try(Connection connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection();
                PreparedStatement statement=connection.prepareStatement("SELECT CAST(? AS DATETIME(3)) AS stored_time")) {
                statement.setTimestamp(1,new Timestamp(anchor));
                try(ResultSet rows=statement.executeQuery()) {
                    if(!rows.next())throw new IllegalStateException("No timestamp codec evidence");
                    return map("anchorEpochMillis",anchor,"roundtripEpochMillis",rows.getTimestamp(1).getTime(),
                            "databaseText",rows.getString(1),"jvmTimezone",TimeZone.getDefault().getID());
                }
            }
        }
        private Map<String,Object> snapshot() throws Exception {
            if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Snapshots must not read inside business transactions");
            DataSource source=Objects.requireNonNull(jdbc.getDataSource());
            try(Connection connection=source.getConnection()) {
                String schema=connection.getCatalog();
                if(schema==null||!schema.matches("chengyin_mybatis_c_(hint_unlock|lead_admission_failure)_[a-z0-9_]{1,20}"))
                    throw new IllegalStateException("Evidence is limited to the original isolated fixture schemas");
                Map<String,Object> tables=new TreeMap<>();List<String> names=new ArrayList<>();
                try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY table_name")) {
                    while(rows.next())names.add(rows.getString(1));
                }
                for(String table:names) {
                    if(!table.matches("[a-z0-9_]+"))throw new IllegalStateException("Unexpected fixture table name");
                    try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT * FROM `"+table+"`")) {
                        ResultSetMetaData meta=rows.getMetaData();List<Object> columns=new ArrayList<>(),values=new ArrayList<>();
                        for(int i=1;i<=meta.getColumnCount();i++)columns.add(map("name",meta.getColumnLabel(i),"jdbcType",meta.getColumnType(i),"typeName",meta.getColumnTypeName(i),"precision",meta.getPrecision(i),"scale",meta.getScale(i),"nullable",meta.isNullable(i)));
                        while(rows.next()) {
                            Map<String,Object> row=new TreeMap<>();
                            for(int i=1;i<=meta.getColumnCount();i++) {
                                Object value;
                                if(meta.getColumnType(i)==Types.TIMESTAMP) {
                                    Timestamp time=rows.getTimestamp(i);value=time==null?null:map("kind","time","javaType",Timestamp.class.getName(),"epochMillis",time.getTime(),"value",rows.getString(i));
                                } else value=scalar(rows.getObject(i));
                                row.put(meta.getColumnLabel(i),value);
                            }
                            values.add(row);
                        }
                        tables.put(table,map("columns",columns,"rows",values));
                    }
                }
                return map("schema",schema,"tables",tables);
            }
        }
        public void finish(Map<String,Object> originalObservations) throws Exception {
            try {
                if(CURRENT.get()!=this||RUNNING.get()!=this||inFlight.get()!=0)throw new IllegalStateException("Incomplete/late equivalence call context");
                Map<String,Object> after=snapshot();long ended=System.currentTimeMillis();
                Map<String,Object> environment=jdbc.queryForMap("SELECT VERSION() AS mysql_version,@@session.time_zone AS session_timezone,@@system_time_zone AS system_timezone,@@session.transaction_isolation AS default_isolation,@@sql_mode AS sql_mode,@@character_set_connection AS character_set_connection,@@collation_connection AS collation_connection");
                environment.put("javaVersion",System.getProperty("java.version"));
                environment.put("javaVendor",System.getProperty("java.vendor"));
                List<Object> ordered=new ArrayList<>(events);ordered.sort(Comparator.comparingLong(v->((Number)((Map<?,?>)v).get("seq")).longValue()));
                List<Map<String,Object>> orderedFrames=new ArrayList<>(frames);orderedFrames.sort(Comparator.comparingLong(v->((Number)v.get("call")).longValue()));
                Map<String,Object> document=map("format","C-M1-EQUIVALENCE-1","source_sha",sourceSha,"suite",suite,"case",name,"caseOrdinal",ordinal,"comparison_mode",race?"transaction-partial-order":"full-order", "jvmStartMillis",started,"jvmEndMillis",ended,
                        "environment",environment,"timestampCodec",timestampCodec,"initial",before,"calls",orderedFrames,"rawTrace",ordered,"final",after,"originalObservations",freeze(originalObservations),"complete",true);
                Path directory=Path.of("target","surefire-reports","wp09-m1-equivalence");Files.createDirectories(directory);
                Path destination=directory.resolve(suite.substring(suite.lastIndexOf('.')+1)+"--"+name+".json");
                Files.writeString(destination,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document),StandardOpenOption.CREATE_NEW);
            } finally {CURRENT.remove();CALLS.remove();RUNNING.compareAndSet(this,null);}
        }
    }
}
