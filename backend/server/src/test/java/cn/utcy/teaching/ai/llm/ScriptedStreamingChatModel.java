package cn.utcy.teaching.ai.llm;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.ArrayList;
import java.util.List;

/** 按脚本回答的假模型:每次请求把请求交给脚本,脚本往 Output 里写思考 / 正文 / 工具调用;没显式 done 就自动收尾 */
public final class ScriptedStreamingChatModel implements StreamingChatModel {

    @FunctionalInterface
    public interface Turn {
        void play(ChatRequest request, Output out);
    }

    public static final class Output {
        private final StreamingChatResponseHandler handler;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();
        private final List<ToolExecutionRequest> calls = new ArrayList<>();
        private boolean finished;

        Output(StreamingChatResponseHandler handler) {
            this.handler = handler;
        }

        public Output thinking(String delta) {
            thinking.append(delta);
            handler.onPartialThinking(new PartialThinking(delta));
            return this;
        }

        public Output text(String delta) {
            text.append(delta);
            handler.onPartialResponse(new PartialResponse(delta), null);
            return this;
        }

        public Output toolCall(String id, String name, String arguments) {
            int index = calls.size();
            for (String piece : arguments.split("(?<=\\G.{7})")) {
                handler.onPartialToolCall(PartialToolCall.builder().index(index).id(id).name(name).partialArguments(piece).build(), null);
            }
            ToolExecutionRequest request = ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
            calls.add(request);
            handler.onCompleteToolCall(new CompleteToolCall(index, request));
            return this;
        }

        public void done() {
            if (finished) {
                return;
            }
            finished = true;
            AiMessage message = calls.isEmpty()
                    ? AiMessage.builder().text(text.toString()).thinking(thinking.isEmpty() ? null : thinking.toString()).build()
                    : AiMessage.builder().text(text.toString()).thinking(thinking.isEmpty() ? null : thinking.toString())
                            .toolExecutionRequests(calls).build();
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(message).build());
        }
    }

    private final Turn turn;

    public ScriptedStreamingChatModel(Turn turn) {
        this.turn = turn;
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        Output out = new Output(handler);
        turn.play(request, out);
        out.done();
    }
}
