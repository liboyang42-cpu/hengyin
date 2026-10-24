package com.chengyinhub.common.observability;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.chengyinhub.common.exception.ServiceException;

/**
 * O-11 关键路径失败事件：每次失败只发一行可 grep 的结构化日志，并累加进程内计数（由 admin 的
 * CriticalPathMetrics 以 Actuator 指标 chengyin.critical.path.failures 暴露）。
 *
 * <p>行格式固定：{@code [CY-CRITICAL] code=<码> kind=<FAULT|REJECTED> op=<子操作> cause=<异常类名> root=<根因类名> origin=<项目类#方法:行号或unknown>}。
 * 只含枚举码、固定字面量、异常类名与项目代码位置，<b>不含</b>异常 message、会员/订单 ID、openid、金额等任何业务数据。
 *
 * <p>kind：本仓库业务拒绝习惯抛 IllegalArgumentException / IllegalStateException / ServiceException / 裸
 * RuntimeException，这些记为 REJECTED（INFO）；其余（数据访问、事务、连接池、Redis、NPE 等）记为 FAULT（ERROR），
 * 告警只看 FAULT。
 *
 * <p>本类任何情况下都不向调用方抛异常——观测失败不能反过来改变业务结果。
 */
public final class CriticalPathEvents
{
    public static final String MARKER = "[CY-CRITICAL]";

    /** 独立 logger 名，便于按 logger 单独路由；仍在 com.chengyinhub 层级下，沿用现有日志级别配置 */
    private static final Logger log = LoggerFactory.getLogger("com.chengyinhub.critical");

    private static final Pattern OP = Pattern.compile("[a-z][a-z0-9_]{0,31}");

    private static final Pattern ORIGIN_CLASS = Pattern.compile("com\\.chengyinhub\\.[A-Za-z_$][A-Za-z0-9_$.]{0,191}");
    private static final Pattern ORIGIN_METHOD = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]{0,127}|<init>|<clinit>");

    public enum Kind
    {
        FAULT, REJECTED
    }

    private static final Map<CriticalPathCode, LongAdder[]> COUNTS = new EnumMap<>(CriticalPathCode.class);

    static
    {
        for (CriticalPathCode code : CriticalPathCode.values())
        {
            COUNTS.put(code, new LongAdder[] { new LongAdder(), new LongAdder() });
        }
    }

    /** 已报告过的异常实例（按对象身份，弱引用），保证同一异常沿嵌套边界外抛只报一次 */
    private static final Set<Throwable> REPORTED = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<Throwable, Boolean>()));

    private CriticalPathEvents()
    {
    }

    /**
     * 报告一次关键路径失败。
     *
     * @return true = 本次发出了事件；false = 该异常（或其 cause 链上的异常）已报告过，或参数非法
     */
    public static boolean failure(CriticalPathCode code, String op, Throwable failure)
    {
        try
        {
            if (code == null || failure == null || alreadyReported(failure))
            {
                return false;
            }
            REPORTED.add(failure);
            Kind kind = classify(failure);
            COUNTS.get(code)[kind.ordinal()].increment();
            String safeOp = op != null && OP.matcher(op).matches() ? op : "invalid";
            String cause = failure.getClass().getSimpleName();
            Throwable rootFailure = rootCause(failure);
            String root = rootFailure.getClass().getSimpleName();
            String origin = safeOrigin(rootFailure);
            if (kind == Kind.FAULT)
            {
                log.error("{} code={} kind={} op={} cause={} root={} origin={}", MARKER, code.name(), kind, safeOp, cause, root, origin);
            }
            else
            {
                log.info("{} code={} kind={} op={} cause={} root={} origin={}", MARKER, code.name(), kind, safeOp, cause, root, origin);
            }
            return true;
        }
        catch (RuntimeException | LinkageError ignored)
        {
            return false;
        }
    }

    /** 进程内累计次数（自启动起，单调递增） */
    public static long count(CriticalPathCode code, Kind kind)
    {
        return COUNTS.get(code)[kind.ordinal()].sum();
    }

    public static Kind classify(Throwable failure)
    {
        Class<?> type = failure.getClass();
        if (failure instanceof IllegalArgumentException || failure instanceof IllegalStateException
                || failure instanceof ServiceException || type == RuntimeException.class)
        {
            return Kind.REJECTED;
        }
        return Kind.FAULT;
    }

    private static boolean alreadyReported(Throwable failure)
    {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++)
        {
            if (REPORTED.contains(current))
            {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 只读根异常前 64 帧；不输出文件名、message 或堆栈对象。 */
    private static String safeOrigin(Throwable failure)
    {
        try
        {
            StackTraceElement[] frames = failure.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 64); i++)
            {
                StackTraceElement frame = frames[i];
                String type = frame.getClassName();
                if (type.startsWith("com.chengyinhub."))
                {
                    String method = frame.getMethodName();
                    if (type.length() <= 208 && method.length() <= 128
                            && ORIGIN_CLASS.matcher(type).matches() && ORIGIN_METHOD.matcher(method).matches())
                    {
                        return type + "#" + method + ":" + frame.getLineNumber();
                    }
                    return "unknown";
                }
            }
        }
        catch (RuntimeException | LinkageError ignored)
        {
            // 位置提取失败也不能阻止原事件和计数。
        }
        return "unknown";
    }

    private static Throwable rootCause(Throwable failure)
    {
        Throwable current = failure;
        for (int depth = 0; current.getCause() != null && current.getCause() != current && depth < 16; depth++)
        {
            current = current.getCause();
        }
        return current;
    }
}
