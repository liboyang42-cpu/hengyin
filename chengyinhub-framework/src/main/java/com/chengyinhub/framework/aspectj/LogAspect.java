package com.chengyinhub.framework.aspectj;

import javax.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NamedThreadLocal;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.common.enums.BusinessStatus;
import com.chengyinhub.common.enums.OperatorType;
import com.chengyinhub.common.utils.SecurityUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.ip.IpUtils;
import com.chengyinhub.framework.manager.AsyncManager;
import com.chengyinhub.framework.manager.factory.AsyncFactory;
import com.chengyinhub.system.domain.SysOperLog;

/**
 * 操作日志记录处理
 * 
 * @author ruoyi
 */
@Aspect
@Component
public class LogAspect
{
    private static final Logger log = LoggerFactory.getLogger(LogAspect.class);

    /** 计算操作消耗时间 */
    private static final ThreadLocal<Long> TIME_THREADLOCAL = new NamedThreadLocal<Long>("Cost Time");

    /**
     * 处理请求前执行
     */
    @Before(value = "@annotation(controllerLog)")
    public void doBefore(JoinPoint joinPoint, Log controllerLog)
    {
        TIME_THREADLOCAL.set(System.currentTimeMillis());
    }

    /**
     * 处理完请求后执行
     *
     * @param joinPoint 切点
     */
    @AfterReturning(pointcut = "@annotation(controllerLog)", returning = "jsonResult")
    public void doAfterReturning(JoinPoint joinPoint, Log controllerLog, Object jsonResult)
    {
        handleLog(joinPoint, controllerLog, null);
    }

    /**
     * 拦截异常操作
     * 
     * @param joinPoint 切点
     * @param e 异常
     */
    @AfterThrowing(value = "@annotation(controllerLog)", throwing = "e")
    public void doAfterThrowing(JoinPoint joinPoint, Log controllerLog, Exception e)
    {
        handleLog(joinPoint, controllerLog, e);
    }

    protected void handleLog(final JoinPoint joinPoint, Log controllerLog, final Exception e)
    {
        try
        {
            // *========数据库日志=========*//
            SysOperLog operLog = new SysOperLog();
            operLog.setStatus(BusinessStatus.SUCCESS.ordinal());
            HttpServletRequest request = getCurrentRequest();
            operLog.setOperIp(IpUtils.getIpAddr(request));
            if (request != null)
            {
                Object routePattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                if (routePattern instanceof String)
                {
                    operLog.setOperUrl(StringUtils.substring((String) routePattern, 0, 255));
                }
                operLog.setRequestMethod(request.getMethod());
            }
            setOperator(operLog, SecurityUtils.getAuthentication());

            if (e != null)
            {
                operLog.setStatus(BusinessStatus.FAIL.ordinal());
                // 异常消息可能携带 token、手机号或请求正文，审计表只记录异常类型。
                operLog.setErrorMsg(e.getClass().getSimpleName());
            }
            // 设置方法名称
            String className = joinPoint.getTarget().getClass().getName();
            String methodName = joinPoint.getSignature().getName();
            operLog.setMethod(className + "." + methodName + "()");
            // 处理设置注解上的参数
            setControllerMethodDescription(controllerLog, operLog);
            // 设置消耗时间
            Long startTime = TIME_THREADLOCAL.get();
            operLog.setCostTime(startTime == null ? 0L : System.currentTimeMillis() - startTime);
            // 保存数据库
            try
            {
                AsyncManager.me().execute(AsyncFactory.recordOper(operLog));
            }
            catch (RuntimeException exp)
            {
                // 审计基础设施故障不得改变原业务结果，且异常消息可能携带敏感请求数据。
                log.error("操作审计提交失败: {}", exp.getClass().getSimpleName());
            }
        }
        finally
        {
            TIME_THREADLOCAL.remove();
        }
    }

    private HttpServletRequest getCurrentRequest()
    {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes
                ? ((ServletRequestAttributes) attributes).getRequest()
                : null;
    }

    private void setOperator(SysOperLog operLog, Authentication authentication)
    {
        operLog.setOperatorType(OperatorType.OTHER.ordinal());
        if (authentication == null)
        {
            return;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof LoginUser)
        {
            operLog.setOperatorType(OperatorType.MANAGE.ordinal());
            LoginUser loginUser = (LoginUser) principal;
            SysUser currentUser = loginUser.getUser();
            if (StringUtils.isNotNull(currentUser))
            {
                operLog.setOperName(currentUser.getUserName());
                if (StringUtils.isNotNull(currentUser.getDept()))
                {
                    operLog.setDeptName(currentUser.getDept().getDeptName());
                }
            }
        }
        else if (principal instanceof LoginAppUser)
        {
            operLog.setOperatorType(OperatorType.MOBILE.ordinal());
            LoginAppUser loginAppUser = (LoginAppUser) principal;
            if (loginAppUser.getUserId() != null)
            {
                operLog.setOperName("app:" + loginAppUser.getUserId());
            }
        }
    }

    /**
     * 获取注解中对方法的描述信息 用于Controller层注解
     * 
     * @param log 日志
     * @param operLog 操作日志
     */
    private void setControllerMethodDescription(Log log, SysOperLog operLog)
    {
        // 设置action动作
        operLog.setBusinessType(log.businessType().ordinal());
        // 设置标题
        operLog.setTitle(log.title());
        // 审计日志只保存操作元数据；请求/响应正文可能包含 token、密码或手机号，禁止入库。
    }

}
