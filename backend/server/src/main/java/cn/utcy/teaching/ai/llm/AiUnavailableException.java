package cn.utcy.teaching.ai.llm;

import cn.utcy.teaching.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/** 大模型、向量化、语音合成、文生图这类上游 AI 服务不可用或拒绝请求;面向用户的文案,502 */
public class AiUnavailableException extends DomainException {

    /** 传输层瞬时故障(可在增量外送前重试)还是确定性拒绝 */
    final boolean retriable;

    public AiUnavailableException(String message) {
        this(message, null, false);
    }

    public AiUnavailableException(String message, Throwable cause) {
        this(message, cause, false);
    }

    AiUnavailableException(String message, Throwable cause, boolean retriable) {
        super(HttpStatus.BAD_GATEWAY, message);
        this.retriable = retriable;
        if (cause != null) {
            initCause(cause);
        }
    }
}
