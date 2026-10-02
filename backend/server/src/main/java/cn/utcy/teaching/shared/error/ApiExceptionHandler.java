package cn.utcy.teaching.shared.error;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import cn.utcy.teaching.shared.web.RequestIdFilter;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final URI DOMAIN_ERROR = URI.create("urn:teaching:problem:domain-error");
    private static final URI VALIDATION_ERROR =
            URI.create("urn:teaching:problem:validation-error");
    private static final URI INTERNAL_ERROR =
            URI.create("urn:teaching:problem:internal-error");

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemDetail> handleDomainException(DomainException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        problem.setType(DOMAIN_ERROR);
        problem.setTitle(titleFor(exception.status()));
        attachRequestId(problem);
        return ResponseEntity.status(exception.status()).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "请求参数校验失败");
        problem.setType(VALIDATION_ERROR);
        problem.setTitle("请求参数错误");
        problem.setProperty("errors", errors);
        attachRequestId(problem);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setType(VALIDATION_ERROR);
        problem.setTitle("请求参数错误");
        attachRequestId(problem);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleMethodValidation() {
        return requestProblem(HttpStatus.BAD_REQUEST, "请求参数错误", "请求参数校验失败");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableMessage() {
        return requestProblem(
                HttpStatus.BAD_REQUEST,
                "请求体格式错误",
                "请求内容格式不正确");
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            ServletRequestBindingException.class
    })
    public ResponseEntity<ProblemDetail> handleRequestBinding() {
        return requestProblem(
                HttpStatus.BAD_REQUEST,
                "请求参数错误",
                "请求参数缺失或类型不正确");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedMediaType() {
        return requestProblem(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "请求内容类型不受支持",
                "该接口只接受声明的请求内容类型");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ProblemDetail> handleNotAcceptable() {
        return requestProblem(
                HttpStatus.NOT_ACCEPTABLE,
                "响应类型不受支持",
                "该接口无法生成请求的响应内容类型");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrityViolation() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "数据与现有记录冲突或仍被其他内容引用");
        problem.setType(DOMAIN_ERROR);
        problem.setTitle("数据冲突");
        attachRequestId(problem);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "用户名、密码或账户凭据状态不正确");
        problem.setType(DOMAIN_ERROR);
        problem.setTitle("登录失败");
        attachRequestId(problem);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "请求的接口不存在");
        problem.setType(DOMAIN_ERROR);
        problem.setTitle("接口不存在");
        attachRequestId(problem);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotAllowed() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.METHOD_NOT_ALLOWED, "该接口不支持当前请求方法");
        problem.setType(DOMAIN_ERROR);
        problem.setTitle("请求方法不受支持");
        attachRequestId(problem);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(problem);
    }

    /**
     * 长连接(SSE)客户端断开后容器通知的异常:不是服务端错误,响应也早已提交,
     * 按 Spring 的约定返回 null 让容器收尾,不再当未知异常记 ERROR。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ResponseEntity<Void> handleClientGone(AsyncRequestNotUsableException exception) {
        LOG.debug("客户端已断开,放弃写响应:{}", exception.getMessage());
        return null;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception) {
        LOG.error("接口处理发生未预期的服务端异常", exception);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "服务器处理请求时发生错误");
        problem.setType(INTERNAL_ERROR);
        problem.setTitle("服务器内部错误");
        attachRequestId(problem);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    private ResponseEntity<ProblemDetail> requestProblem(
            HttpStatus status,
            String title,
            String detail
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(VALIDATION_ERROR);
        problem.setTitle(title);
        attachRequestId(problem);
        return ResponseEntity.status(status).body(problem);
    }

    private String titleFor(HttpStatus status) {
        return switch (status) {
            case UNAUTHORIZED -> "需要登录";
            case NOT_FOUND -> "资源不存在";
            case CONFLICT -> "业务状态冲突";
            case FORBIDDEN -> "无权执行此操作";
            case TOO_MANY_REQUESTS -> "请求过于频繁";
            default -> "业务处理失败";
        };
    }

    private static void attachRequestId(ProblemDetail problem) {
        String requestId = RequestIdFilter.current();
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
    }

}
