package cn.utcy.teaching.ai.llm;

import cn.utcy.teaching.ai.infrastructure.DashScopeProperties;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmCallsTest {

    private static final DashScopeProperties PROPERTIES = new DashScopeProperties(
            URI.create("http://localhost/v1"), URI.create("http://localhost/api"), Duration.ofSeconds(1), Duration.ofMillis(200));

    private final LlmCalls llm = new LlmCalls(new LlmModels(PROPERTIES), PROPERTIES);
    private final ChatRequest request = ChatRequest.builder().messages(UserMessage.from("问")).build();
    private final List<String> texts = new ArrayList<>();
    private final LlmCalls.Listener listener = new LlmCalls.Listener() {
        @Override
        public void onText(String delta) {
            texts.add(delta);
        }
    };

    @Test
    @DisplayName("首个 token 前的瞬时故障:重试后成功即正常返回")
    void retriesTransientFailureBeforeFirstToken() {
        AtomicInteger calls = new AtomicInteger();
        ChatResponse response = llm.chat(new ScriptedStreamingChatModel((req, out) -> {
            if (calls.incrementAndGet() == 1) {
                throw new RetriableException("连接被重置");
            }
            out.text("好了");
        }), request, listener);

        assertThat(response.aiMessage().text()).isEqualTo("好了");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(texts).containsExactly("好了");
    }

    @Test
    @DisplayName("正文已经外送后再出错:不重放,直接报错")
    void noRetryAfterFirstToken() {
        AtomicInteger calls = new AtomicInteger();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                calls.incrementAndGet();
                handler.onPartialResponse(new PartialResponse("一半"), new PartialResponseContext(handle(new AtomicBoolean())));
                handler.onError(new RetriableException("流中途断了"));
            }
        };

        assertThatThrownBy(() -> llm.chat(model, request, listener))
                .isInstanceOf(AiUnavailableException.class).hasMessageContaining("流中途断了");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(texts).containsExactly("一半");
    }

    @Test
    @DisplayName("鉴权失败不重试,提示到账户设置检查密钥")
    void authenticationFailureIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> llm.chat(new ScriptedStreamingChatModel((req, out) -> {
            calls.incrementAndGet();
            throw new AuthenticationException("401");
        }), request, listener))
                .isInstanceOf(AiUnavailableException.class).hasMessageContaining("API Key 无效");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("只有思考、没有正文也没有工具调用:思考文本就是回答")
    void thinkingBecomesTextWhenNothingElse() {
        ChatResponse response = llm.chat(new ScriptedStreamingChatModel((req, out) -> out.thinking("其实答案是 4")),
                request, listener);
        assertThat(response.aiMessage().text()).isEqualTo("其实答案是 4");
        assertThat(texts).isEmpty();
    }

    @Test
    @DisplayName("上游长时间没有输出:看门狗取消流并按可重试故障报错")
    void watchdogCancelsSilentStream() {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                calls.incrementAndGet();
                handler.onPartialResponse(new PartialResponse("开"), new PartialResponseContext(handle(cancelled)));
                // 之后再无任何输出
            }
        };

        assertThatThrownBy(() -> llm.chat(model, request, listener))
                .isInstanceOf(AiUnavailableException.class).hasMessageContaining("响应流中断");
        assertThat(cancelled).isTrue();
        // 已经外送过正文,不重放
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("向量维度与约定不符即报错,绝不静默")
    void embeddingDimensionMismatchFails() {
        LlmCalls calls = new LlmCalls(new LlmModels(PROPERTIES) {
            @Override
            public EmbeddingModel embeddingModel(String apiKey, String model, int dimension) {
                return new EmbeddingModel() {
                    @Override
                    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                        return Response.from(segments.stream().map(s -> Embedding.from(new float[] {1f, 2f, 3f})).toList());
                    }
                };
            }
        }, PROPERTIES);

        assertThat(calls.embed("key", "m", 3, List.of("a", "b"))).hasSize(2);
        assertThatThrownBy(() -> calls.embed("key", "m", 4, List.of("a")))
                .isInstanceOf(AiUnavailableException.class).hasMessageContaining("向量维度不符");
    }

    private static StreamingHandle handle(AtomicBoolean cancelled) {
        return new StreamingHandle() {
            @Override
            public void cancel() {
                cancelled.set(true);
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
    }

}
