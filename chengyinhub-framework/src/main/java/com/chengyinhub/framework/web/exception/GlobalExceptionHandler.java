package com.chengyinhub.framework.web.exception;

import jakarta.servlet.http.HttpServletRequest;
import com.chengyinhub.common.utils.UserSafeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.exception.BusinessRefusal;
import com.chengyinhub.common.exception.DemoModeException;
import com.chengyinhub.common.exception.ErrorCode;
import com.chengyinhub.common.exception.ErrorContract;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.html.EscapeUtil;

/**
 * 全局异常处理器
 * 
 * @author ruoyi
 */
@RestControllerAdvice
public class GlobalExceptionHandler
{
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RuntimeFailureAlarmFilter runtimeFailureAlarm;

    /** 未预期的系统异常返回给用户的稳定文案(判据真源 = {@link UserSafeMessage},与自己 try/catch 的控制器共用)。 */
    private static final String GENERIC_SYSTEM_ERROR_MSG = UserSafeMessage.GENERIC;

    /**
     * R9 BE-05: additive envelope key that separates a deliberate business refusal from a system fault.
     * RuoYi codes both as {@code code=500}; the mini-program and the app already branch on {@code code} and
     * {@code msg}, so the code stays as it is and this key is only added. Clients and the smoke harness read
     * {@code errorKind} to tell "the rule said no" ({@value #KIND_BUSINESS}) from "something broke" ({@value #KIND_SYSTEM}).
     */
    public static final String ERROR_KIND = "errorKind";
    public static final String KIND_BUSINESS = "BUSINESS";
    public static final String KIND_SYSTEM = "SYSTEM";

    static AjaxResult kind(AjaxResult result, String kind)
    {
        result.put(ERROR_KIND, kind);
        return result;
    }

    /** Missing routes/resources are expected 404s, not application failures. */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    @ResponseStatus(org.springframework.http.HttpStatus.NOT_FOUND)
    public AjaxResult handleNotFound(Exception exception, HttpServletRequest request)
    {
        log.debug("请求路径不存在 method={}", request.getMethod());
        return AjaxResult.error(HttpStatus.NOT_FOUND, "请求路径不存在").withErrorCode(ErrorCode.NOT_FOUND);
    }

    /**
     * 生效的上传限额(与 application.yml 的 spring.servlet.multipart 同源,只读不写)。
     * 默认值与 application.yml 一致,便于不经 Spring 直接构造(单测)时仍能给出真实限额。
     */
    @Value("${spring.servlet.multipart.max-file-size:10MB}")
    private String maxFileSize = "10MB";

    @Value("${spring.servlet.multipart.max-request-size:20MB}")
    private String maxRequestSize = "20MB";

    /**
     * 未知/未预期异常对用户的消息必须人话:业务方以 RuntimeException 抛出的中文短句保留,
     * 技术泄漏标记(类名/包路径/堆栈/SQL/URI)一律替换为稳定文案。null/空消息也走稳定文案。
     */
    private String userSafeMessage(String raw)
    {
        return UserSafeMessage.of(raw, GENERIC_SYSTEM_ERROR_MSG);
    }

    /**
     * 上传大小超限。
     *
     * <p>multipart 解析在进入 Controller 之前就按 {@code spring.servlet.multipart} 的
     * 单文件/总请求限额拦截,原异常(MaxUploadSizeExceededException,内含 Tomcat
     * SizeLimitExceededException)带技术细节,会被通用分支收敛成「系统繁忙」,用户无从处理。
     * 这里按**生效配置**给出可操作反馈:说明真正适用的限额与压缩/换文件路径,不建议无意义重试。
     * 限额与拦截本身保持不动。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public AjaxResult handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e, HttpServletRequest request)
    {
        log.error("请求地址'{}',上传文件超出大小限制.", request.getRequestURI(), e);
        return AjaxResult.error(HttpStatus.BAD_REQUEST,
                String.format("文件过大，请压缩或更换文件后上传（单个文件最大%s，单次请求最大%s）",
                        maxFileSize, maxRequestSize)).withErrorCode(ErrorCode.PAYLOAD_TOO_LARGE);
    }

    /**
     * multipart 请求体解析失败(大小超限之外的其它情形,如边界损坏/编码异常)。
     *
     * <p>{@link MaxUploadSizeExceededException} 是 {@link MultipartException} 的子类,由上面的
     * 专用分支按最具体匹配处理。这里**只兜底非大小超限**的 multipart 失败:给可操作的重试/换文件
     * 提示,既不冒充「文件过大」,也不把技术细节漏给用户。
     */
    @ExceptionHandler(MultipartException.class)
    public AjaxResult handleMultipartException(MultipartException e, HttpServletRequest request)
    {
        log.error("请求地址'{}',multipart 请求解析失败.", request.getRequestURI(), e);
        return AjaxResult.error(HttpStatus.BAD_REQUEST, "上传数据无法解析，请重试或更换文件").withErrorCode(ErrorCode.BAD_REQUEST);
    }

    /**
     * 权限校验异常
     */
    @ExceptionHandler(AccessDeniedException.class)
    public AjaxResult handleAccessDeniedException(AccessDeniedException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',权限校验失败'{}'", requestURI, e.getMessage());
        return AjaxResult.error(HttpStatus.FORBIDDEN, "没有权限，请联系管理员授权").withErrorCode(ErrorCode.FORBIDDEN);
    }

    /**
     * 请求方式不支持
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public AjaxResult handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException e,
            HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',不支持'{}'请求", requestURI, e.getMethod());
        return AjaxResult.error(userSafeMessage(e.getMessage())).withErrorCode(ErrorCode.METHOD_NOT_ALLOWED);
    }

    /**
     * 有类型的业务拒绝(R5 error-contract)。旧契约下与迁移前的 {@code error(msg)} 同形;
     * 选择 v2 的请求由 {@link ErrorContractAdvice} 补 HTTP 4xx 与 errorCode。拒绝是预期分支,不记 error 日志、不报警。
     */
    @ExceptionHandler(BusinessRefusal.class)
    public AjaxResult handleBusinessRefusal(BusinessRefusal e, HttpServletRequest request)
    {
        log.info("业务拒绝 code={} uri={}", e.getErrorCode().code(), request.getRequestURI());
        // errorKind 与 ServiceException 一致:无头请求时两条拒绝路径逐字节相同。
        return kind(AjaxResult.refuse(e.getLegacyCode(), e.getErrorCode(), userSafeMessage(e.getMessage()), e.getData())
                .withErrorCode(e.getErrorCode(), e.getHttpStatus()), KIND_BUSINESS);
    }

    /**
     * 业务异常
     */
    @ExceptionHandler(ServiceException.class)
    public AjaxResult handleServiceException(ServiceException e, HttpServletRequest request)
    {
        log.error(e.getMessage(), e);
        Integer code = e.getCode();
        String message = userSafeMessage(e.getMessage());
        AjaxResult result;
        if (e.getData() != null)
        {
            // 回执化冲突:除 code/msg 外把当前世界随包带回,客户端无需再拉一次。
            result = StringUtils.isNotNull(code) ? new AjaxResult(code, message, e.getData())
                    : AjaxResult.error(message, e.getData());
        }
        else
        {
            result = StringUtils.isNotNull(code) ? AjaxResult.error(code, message) : AjaxResult.error(message);
        }
        // 无 code 的 ServiceException 是业务拒绝(除非文案明显是兜底故障);有 code 的按 code 判定。
        if (code == null && !ErrorContract.looksLikeFailure(message))
        {
            result.withErrorCode(ErrorCode.REFUSED);
        }
        return kind(result, KIND_BUSINESS);
    }

    /**
     * 请求路径中缺少必需的路径变量
     */
    @ExceptionHandler(MissingPathVariableException.class)
    public AjaxResult handleMissingPathVariableException(MissingPathVariableException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("请求路径中缺少必需的路径变量'{}',发生系统异常.", requestURI, e);
        return AjaxResult.error(String.format("请求路径中缺少必需的路径变量[%s]", e.getVariableName()))
                .withErrorCode(ErrorCode.BAD_REQUEST);
    }

    /**
     * 请求参数类型不匹配
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public AjaxResult handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        String value = Convert.toStr(e.getValue());
        if (StringUtils.isNotEmpty(value))
        {
            value = EscapeUtil.clean(value);
        }
        log.error("请求参数类型不匹配'{}',发生系统异常.", requestURI, e);
        // 只暴露参数的简单类型名,不把 java.lang.Long 这类全限定类名直出给用户(终审 §12.3-3)
        String typeName = e.getRequiredType() == null ? "" : e.getRequiredType().getSimpleName();
        return AjaxResult.error(userSafeMessage(String.format("请求参数类型不匹配，参数[%s]要求类型为：'%s'，但输入值为：'%s'",
                e.getName(), typeName, value))).withErrorCode(ErrorCode.BAD_REQUEST);
    }

    /**
     * 请求体 JSON / 反序列化失败属于客户端入参错误，不计入系统故障告警。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public AjaxResult handleHttpMessageNotReadableException(HttpMessageNotReadableException e,
            HttpServletRequest request)
    {
        log.warn("请求地址'{}',请求体解析失败:{}", request.getRequestURI(), e.getMessage());
        return AjaxResult.error(HttpStatus.BAD_REQUEST, "请求参数格式有误");
    }

    /**
     * 显式参数校验异常属于客户端入参错误，不计入系统故障告警。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public AjaxResult handleIllegalArgumentException(IllegalArgumentException e, HttpServletRequest request)
    {
        log.warn("请求地址'{}',参数校验失败:{}", request.getRequestURI(), e.getMessage());
        return AjaxResult.error(HttpStatus.BAD_REQUEST, UserSafeMessage.of(e.getMessage(), "请求参数有误"));
    }

    /**
     * 拦截未知的运行时异常
     */
    @ExceptionHandler(RuntimeException.class)
    public AjaxResult handleRuntimeException(RuntimeException e, HttpServletRequest request)
    {
        if (runtimeFailureAlarm != null) runtimeFailureAlarm.systemFailure(e);
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',发生未知异常.", requestURI, e);
        return kind(AjaxResult.error(userSafeMessage(e.getMessage())).withErrorCode(ErrorCode.SYSTEM_FAILURE), KIND_SYSTEM);
    }

    /**
     * 系统异常
     */
    @ExceptionHandler(Exception.class)
    public AjaxResult handleException(Exception e, HttpServletRequest request)
    {
        if (runtimeFailureAlarm != null) runtimeFailureAlarm.systemFailure(e);
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',发生系统异常.", requestURI, e);
        // 请求体/必填参数缺失是客户端问题,不是服务端故障。
        boolean clientError = e instanceof HttpMessageNotReadableException || e instanceof MissingServletRequestParameterException;
        return kind(AjaxResult.error(userSafeMessage(e.getMessage()))
                .withErrorCode(clientError ? ErrorCode.BAD_REQUEST : ErrorCode.SYSTEM_FAILURE), clientError ? KIND_BUSINESS : KIND_SYSTEM);
    }

    /**
     * 自定义验证异常
     */
    @ExceptionHandler(BindException.class)
    public AjaxResult handleBindException(BindException e)
    {
        log.error(e.getMessage(), e);
        String message = e.getAllErrors().get(0).getDefaultMessage();
        return AjaxResult.error(message).withErrorCode(ErrorCode.BAD_REQUEST);
    }

    /**
     * 自定义验证异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Object handleMethodArgumentNotValidException(MethodArgumentNotValidException e)
    {
        log.error(e.getMessage(), e);
        String message = e.getBindingResult().getFieldError().getDefaultMessage();
        return AjaxResult.error(message).withErrorCode(ErrorCode.BAD_REQUEST);
    }

    /**
     * 演示模式异常
     */
    @ExceptionHandler(DemoModeException.class)
    public AjaxResult handleDemoModeException(DemoModeException e)
    {
        return AjaxResult.error("演示模式，不允许操作").withErrorCode(ErrorCode.FORBIDDEN);
    }
}
