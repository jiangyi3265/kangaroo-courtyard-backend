package com.ruoyi.framework.web.exception;

import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.exception.DemoModeException;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.exception.file.FileException;
import com.ruoyi.common.exception.file.FileUploadException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.html.EscapeUtil;

/**
 * 全局异常处理器
 * 
 * @author ruoyi
 */
@RestControllerAdvice
public class GlobalExceptionHandler
{
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 权限校验异常
     */
    @ExceptionHandler(AccessDeniedException.class)
    public AjaxResult handleAccessDeniedException(AccessDeniedException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',权限校验失败'{}'", requestURI, e.getMessage());
        return AjaxResult.error(HttpStatus.FORBIDDEN, "没有权限，请联系管理员授权");
    }

    /**
     * 请求方式不支持
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public AjaxResult handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException e,
            HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.warn("请求地址'{}',不支持'{}'请求", requestURI, e.getMethod());
        return AjaxResult.error(HttpStatus.BAD_METHOD, e.getMessage());
    }

    /**
     * 业务异常
     */
    @ExceptionHandler(ServiceException.class)
    public AjaxResult handleServiceException(ServiceException e, HttpServletRequest request)
    {
        // ServiceException 是**产品设计好的拒绝**（重复报名、没有该门店权限、状态不允许…），
        // 不是故障。原先按 ERROR + 完整堆栈记录，有两个后果：
        //   1. 真正的故障被这类噪声淹没，出事时翻不到；
        //   2. 这些接口没有限流，一个循环就能把日志磁盘写满，连带数据库一起死。
        // 记 WARN、不打堆栈，但把请求地址带上 —— 原先连 URI 都没有，事后根本定位不到是哪个接口。
        log.warn("业务拒绝 {} : {}", request.getRequestURI(), e.getMessage());
        Integer code = e.getCode();
        return StringUtils.isNotNull(code) ? AjaxResult.error(code, e.getMessage()) : AjaxResult.error(e.getMessage());
    }

    /**
     * 请求路径中缺少必需的路径变量
     */
    @ExceptionHandler(MissingPathVariableException.class)
    public AjaxResult handleMissingPathVariableException(MissingPathVariableException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.warn("请求路径中缺少必需的路径变量'{}' : {}", requestURI, e.getVariableName());
        return AjaxResult.error(HttpStatus.BAD_REQUEST, String.format("请求路径中缺少必需的路径变量[%s]", e.getVariableName()));
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
        log.warn("请求参数类型不匹配'{}' : 参数[{}]", requestURI, e.getName());
        Class<?> required = e.getRequiredType();
        return AjaxResult.error(HttpStatus.BAD_REQUEST, String.format("请求参数类型不匹配，参数[%s]要求类型为：'%s'，但输入值为：'%s'", e.getName(),
                required == null ? "?" : required.getSimpleName(), value));
    }

    /**
     * 请求体不是合法 JSON、或字段类型对不上（比如数字字段传了字符串）。
     * 这是调用方的错，不是故障：原先落到 Exception 兜底里，按 ERROR 打完整堆栈、回「系统繁忙」，
     * 一个带畸形 body 的循环就能把日志刷满，也会让值班的人误以为服务出问题。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public AjaxResult handleHttpMessageNotReadable(HttpMessageNotReadableException e, HttpServletRequest request)
    {
        log.warn("请求体无法解析 {} : {}", request.getRequestURI(), rootMessage(e));
        return AjaxResult.error(HttpStatus.BAD_REQUEST, "请求内容格式不正确");
    }

    /** 缺少必填的查询参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public AjaxResult handleMissingParameter(MissingServletRequestParameterException e, HttpServletRequest request)
    {
        log.warn("缺少请求参数 {} : {}", request.getRequestURI(), e.getParameterName());
        return AjaxResult.error(HttpStatus.BAD_REQUEST, "缺少必填参数：" + e.getParameterName());
    }

    /** Content-Type 不对 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public AjaxResult handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e, HttpServletRequest request)
    {
        log.warn("不支持的请求内容类型 {} : {}", request.getRequestURI(), e.getContentType());
        return AjaxResult.error(HttpStatus.UNSUPPORTED_TYPE, "不支持的请求内容类型");
    }

    /** 上传内容超过容器允许的上限（文件太大）。413 没有现成常量 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public AjaxResult handleMaxUploadSize(MaxUploadSizeExceededException e, HttpServletRequest request)
    {
        log.warn("上传内容过大 {}", request.getRequestURI());
        return AjaxResult.error(413, "上传的文件太大，请压缩后重试");
    }

    /** 文件名过长 / 文件过大 / 扩展名不允许：上传的人能看懂、能改的错 */
    @ExceptionHandler({ FileException.class, FileUploadException.class })
    public AjaxResult handleFileException(Exception e, HttpServletRequest request)
    {
        log.warn("文件上传被拒 {} : {}", request.getRequestURI(), e.getMessage());
        return AjaxResult.error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * 唯一键冲突：连点、并发重复提交、或确实已经存在。原先是不透明的 500「系统繁忙」，
     * 用户和客服都不知道该重试还是该换个值。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public AjaxResult handleDuplicateKey(DuplicateKeyException e, HttpServletRequest request)
    {
        log.warn("唯一键冲突 {} : {}", request.getRequestURI(), rootMessage(e));
        return AjaxResult.error(HttpStatus.CONFLICT, "已经存在相同的记录，或操作太快了，请刷新后再确认");
    }

    /**
     * 写库被约束拒绝：内容超过列宽、必填列为空、数值超出范围。
     * 多半是输入问题（少数是代码漏了校验，日志里留着根因，按 WARN 便于排查而不刷堆栈）。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public AjaxResult handleDataIntegrity(DataIntegrityViolationException e, HttpServletRequest request)
    {
        log.warn("数据不符合库表约束 {} : {}", request.getRequestURI(), rootMessage(e));
        return AjaxResult.error(422, "提交的内容不符合要求（内容过长、缺少必填项或数值超出范围），请检查后重试");
    }

    /**
     * 调用方传来的日期 / 数字 / 列表参数格式不对：接口直接 parse 或强转，格式不对就抛到这里。
     * 原先一律落到 RuntimeException 兜底（ERROR + 堆栈 + 「系统繁忙」），用户不知道是自己填错了，值班的人也被噪声淹没。
     * 记 WARN 并带上出错的位置（栈顶一帧），真正的代码缺陷仍然能从日志里找到。
     */
    @ExceptionHandler({ java.time.format.DateTimeParseException.class, NumberFormatException.class, ClassCastException.class })
    public AjaxResult handleBadInputFormat(RuntimeException e, HttpServletRequest request)
    {
        StackTraceElement[] st = e.getStackTrace();
        log.warn("参数格式不正确 {} : {} @ {}", request.getRequestURI(), rootMessage(e), st.length > 0 ? st[0] : "?");
        String what = e instanceof java.time.format.DateTimeParseException ? "日期或时间格式不正确"
                : (e instanceof NumberFormatException ? "数字格式不正确" : "参数类型不正确");
        return AjaxResult.error(422, what + "，请检查后重试");
    }

    private static String rootMessage(Throwable e)
    {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t)
        {
            t = t.getCause();
        }
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : (m.length() > 300 ? m.substring(0, 300) : m);
    }

    /**
     * 拦截未知的运行时异常
     */
    @ExceptionHandler(com.ruoyi.common.exception.base.BaseException.class)
    public AjaxResult handleBusinessBase(com.ruoyi.common.exception.base.BaseException e)
    {
        // 验证码过期、登录校验等已知业务失败需要可操作的提示，不走未知错误兜底。
        return AjaxResult.error(e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public AjaxResult handleRuntimeException(RuntimeException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        String traceId = newTraceId();
        log.error("请求地址'{}',发生未知异常. traceId={}", requestURI, traceId, e);
        return AjaxResult.error(clientSafeMessage(traceId));
    }

    /**
     * 系统异常
     */
    @ExceptionHandler(Exception.class)
    public AjaxResult handleException(Exception e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        String traceId = newTraceId();
        log.error("请求地址'{}',发生系统异常. traceId={}", requestURI, traceId, e);
        return AjaxResult.error(clientSafeMessage(traceId));
    }

    /**
     * 未知异常回给客户端的文案。
     *
     * 这里原先是 `AjaxResult.error(e.getMessage())` —— 把异常原文直接下发。
     * 实测过：一个超长的 idemKey 就能让回包里带出 mapper 文件的绝对路径、
     * Mapper 接口的全限定名和原始 SQL 语句。那是给攻击者的地图。
     *
     * 反过来，只回一句"系统异常"又会让用户和客服都无从查起，
     * 所以带上一个追踪编号：客户端看到它，服务端日志里搜它就能定位到那一条堆栈。
     */
    private static String clientSafeMessage(String traceId)
    {
        return "系统繁忙，请稍后重试（编号 " + traceId + "）";
    }

    /** 短追踪编号：够用来在日志里定位，又不至于长到用户念不出来 */
    private static String newTraceId()
    {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /**
     * 自定义验证异常
     */
    @ExceptionHandler(BindException.class)
    public AjaxResult handleBindException(BindException e)
    {
        log.warn("参数校验未通过: {}", e.getMessage());
        String message = e.getAllErrors().get(0).getDefaultMessage();
        return AjaxResult.error(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 自定义验证异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Object handleMethodArgumentNotValidException(MethodArgumentNotValidException e)
    {
        log.warn("参数校验未通过: {}", e.getMessage());
        String message = e.getBindingResult().getFieldError().getDefaultMessage();
        return AjaxResult.error(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 演示模式异常
     */
    @ExceptionHandler(DemoModeException.class)
    public AjaxResult handleDemoModeException(DemoModeException e)
    {
        return AjaxResult.error("演示模式，不允许操作");
    }
}
