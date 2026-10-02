package cn.utcy.teaching.ai.llm;

import cn.utcy.teaching.ai.infrastructure.DashScopeProperties;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 平台内唯一的模型调用入口:所有对话都以流式发出,这里阻塞收齐。
 * 约定集中在此一处:首个增量到达前的传输故障退避重试(增量一旦外送不可重放,直接报错);
 * 流中静默超过 idleTimeout 视为断流;上游异常统一成 {@link AiUnavailableException};
 * 个别模型把整个回答放在思考字段里、正文为空,这时思考文本就是回答。
 */
@Component
public class LlmCalls {

    private static final Logger log = LoggerFactory.getLogger(LlmCalls.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long WATCHDOG_TICK_MS = 5_000;

    public interface Listener {
        default void onText(String delta) {
        }

        default void onThinking(String delta) {
        }

        default void onToolCallDelta(PartialToolCall call) {
        }
    }

    private final LlmModels models;
    private final DashScopeProperties properties;

    public LlmCalls(LlmModels models, DashScopeProperties properties) {
        this.models = models;
        this.properties = properties;
    }

    public LlmModels models() {
        return models;
    }

    /** 一次对话:消息含 system;jsonMode 时要求上游只输出 JSON;onDelta 可空 */
    public String chatText(String apiKey, ModelConfig config, List<ChatMessage> messages, boolean jsonMode,
                           Consumer<String> onDelta) {
        ChatRequest.Builder request = ChatRequest.builder().messages(messages);
        if (jsonMode) {
            request.responseFormat(ResponseFormat.JSON);
        }
        Listener listener = onDelta == null ? new Listener() {
        } : new Listener() {
            @Override
            public void onText(String delta) {
                onDelta.accept(delta);
            }
        };
        String text = chat(models.chatModel(apiKey, config), request.build(), listener).aiMessage().text();
        return text == null ? "" : text;
    }

    public ChatResponse chat(StreamingChatModel model, ChatRequest request, Listener listener) {
        AiUnavailableException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Stream stream = new Stream(listener, properties.idleTimeout().toMillis());
            try {
                return stream.run(model, request);
            } catch (AiUnavailableException exception) {
                if (stream.started() || !exception.retriable) {
                    throw exception;
                }
                lastFailure = exception;
                log.warn("大模型调用失败(attempt {}/{}): {}", attempt, MAX_ATTEMPTS, exception.getMessage());
            }
            if (attempt < MAX_ATTEMPTS) {
                backoff(attempt);
            }
        }
        throw lastFailure;
    }

    /** 文本向量化:按输入顺序返回;任一向量维度与 dimension 不符即报错(维度漂移意味着索引不可比,绝不静默) */
    public List<float[]> embed(String apiKey, String model, int dimension, List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<Embedding> embeddings;
        try {
            embeddings = models.embeddingModel(apiKey, model, dimension)
                    .embedAll(texts.stream().map(TextSegment::from).toList()).content();
        } catch (RuntimeException exception) {
            throw translate(exception, "向量化服务");
        }
        if (embeddings.size() != texts.size()) {
            throw new AiUnavailableException("向量化服务返回条数不符: 期望 " + texts.size() + " 条,实际 " + embeddings.size() + " 条");
        }
        List<float[]> vectors = new ArrayList<>(embeddings.size());
        for (Embedding embedding : embeddings) {
            if (embedding.dimension() != dimension) {
                throw new AiUnavailableException("向量维度不符: 期望 " + dimension + ",实际 " + embedding.dimension()
                        + "(model=" + model + ")");
            }
            vectors.add(embedding.vector());
        }
        return vectors;
    }

    static AiUnavailableException translate(Throwable exception, String service) {
        Throwable cause = exception;
        while (cause.getCause() != null && !(cause instanceof HttpException)) {
            cause = cause.getCause();
        }
        String detail = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        if (exception instanceof AuthenticationException || (cause instanceof HttpException http
                && (http.statusCode() == 401 || http.statusCode() == 403))) {
            return new AiUnavailableException("大模型 API Key 无效,请到「账户设置 → AI 服务」检查配置", exception, false);
        }
        if (exception instanceof InvalidRequestException || (cause instanceof HttpException http && http.statusCode() == 400)) {
            return new AiUnavailableException(service + "拒绝了请求: " + detail, exception, false);
        }
        if (exception instanceof NonRetriableException && !(exception instanceof RateLimitException)) {
            return new AiUnavailableException(service + "返回错误: " + detail, exception, false);
        }
        // 只有明确的传输层瞬时故障才值得重试;其余异常(含程序自身的错误)原样报出
        boolean retriable = exception instanceof RateLimitException || exception instanceof TimeoutException
                || exception instanceof RetriableException
                || cause instanceof java.io.IOException
                || (cause instanceof HttpException http && (http.statusCode() >= 500 || http.statusCode() == 408 || http.statusCode() == 429));
        return new AiUnavailableException(service + "暂时不可用: " + detail, exception, retriable);
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(1000L << (attempt - 1));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiUnavailableException("等待大模型服务时线程被中断");
        }
    }

    private static final class Stream implements StreamingChatResponseHandler {
        private final Listener listener;
        private final long idleMillis;
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicReference<ChatResponse> response = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<StreamingHandle> handle = new AtomicReference<>();
        private final AtomicLong lastActivity = new AtomicLong(System.nanoTime());
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean settled = new AtomicBoolean(false);

        Stream(Listener listener, long idleMillis) {
            this.listener = listener;
            this.idleMillis = idleMillis;
        }

        boolean started() {
            return started.get();
        }

        ChatResponse run(StreamingChatModel model, ChatRequest request) {
            Thread watchdog = Thread.ofVirtual().start(this::watch);
            try {
                model.chat(request, this);
                done.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AiUnavailableException("等待大模型服务时线程被中断", exception, false);
            } catch (RuntimeException exception) {
                throw translate(exception, "大模型服务");
            } finally {
                watchdog.interrupt();
            }
            Throwable error = failure.get();
            if (error != null) {
                if (error instanceof AiUnavailableException unavailable) {
                    throw unavailable;
                }
                throw translate(error, "大模型服务");
            }
            return withThinkingAsFallback(response.get());
        }

        private void watch() {
            try {
                while (true) {
                    Thread.sleep(Math.min(WATCHDOG_TICK_MS, idleMillis));
                    if ((System.nanoTime() - lastActivity.get()) / 1_000_000 > idleMillis) {
                        StreamingHandle open = handle.get();
                        if (open != null) {
                            open.cancel();
                        }
                        fail(new AiUnavailableException("大模型响应流中断:上游长时间没有输出", null, true));
                        return;
                    }
                }
            } catch (InterruptedException ignored) {
                // 流已收齐或已失败
            }
        }

        private void touch(StreamingHandle streamingHandle) {
            lastActivity.set(System.nanoTime());
            started.set(true);
            if (streamingHandle != null) {
                handle.set(streamingHandle);
            }
        }

        private void fail(Throwable error) {
            if (settled.compareAndSet(false, true)) {
                failure.set(error);
                done.countDown();
            }
        }

        @Override
        public void onPartialResponse(String partialResponse) {
            onPartialResponse(new PartialResponse(partialResponse), null);
        }

        @Override
        public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
            touch(context == null ? null : context.streamingHandle());
            if (partialResponse.text() != null && !partialResponse.text().isEmpty()) {
                listener.onText(partialResponse.text());
            }
        }

        @Override
        public void onPartialThinking(PartialThinking partialThinking) {
            onPartialThinking(partialThinking, null);
        }

        @Override
        public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
            touch(context == null ? null : context.streamingHandle());
            if (partialThinking.text() != null && !partialThinking.text().isEmpty()) {
                listener.onThinking(partialThinking.text());
            }
        }

        @Override
        public void onPartialToolCall(PartialToolCall partialToolCall) {
            onPartialToolCall(partialToolCall, null);
        }

        @Override
        public void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext context) {
            touch(context == null ? null : context.streamingHandle());
            listener.onToolCallDelta(partialToolCall);
        }

        @Override
        public void onCompleteResponse(ChatResponse completeResponse) {
            if (settled.compareAndSet(false, true)) {
                response.set(completeResponse);
                done.countDown();
            }
        }

        @Override
        public void onError(Throwable error) {
            fail(error);
        }

        /** 正文为空、也没有工具调用,但思考非空:思考文本就是回答 */
        private static ChatResponse withThinkingAsFallback(ChatResponse original) {
            AiMessage message = original.aiMessage();
            boolean noText = message.text() == null || message.text().isBlank();
            boolean noTools = !message.hasToolExecutionRequests();
            if (noText && noTools && message.thinking() != null && !message.thinking().isBlank()) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(message.thinking()))
                        .tokenUsage(original.tokenUsage())
                        .finishReason(original.finishReason())
                        .build();
            }
            return original;
        }
    }
}
