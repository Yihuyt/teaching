package cn.utcy.teaching.question.application;

import cn.utcy.teaching.ai.agent.ChatAgentLoop;
import cn.utcy.teaching.ai.llm.FakeLlm;
import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.ai.llm.PromptLoader;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseRetrievalService;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseRetrievalService.KnowledgeBaseRef;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseRetrievalService.RetrievedPassage;
import cn.utcy.teaching.retrieval.application.RetrievalTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuestionToolsTest {

    private static final ModelConfig MODEL = new ModelConfig("q", "test", false, 0.4, 0.9, 2048);

    private final ObjectMapper json = new ObjectMapper();
    private final KnowledgeBaseRetrievalService knowledgeBases = mock(KnowledgeBaseRetrievalService.class);
    private final FakeLlm llm = new FakeLlm();
    private final QuestionTools tools = new QuestionTools(
            new RetrievalTools(knowledgeBases, json), llm, new PromptLoader(json));

    @Test
    void 按挂载决定工具() {
        assertThat(tools.definitions(RetrievalTools.Mounts.NONE)).isEmpty();
        RetrievalTools.Mounts mounted = new RetrievalTools.Mounts(List.of(new KnowledgeBaseRef(3L, "库")));
        assertThat(tools.definitions(mounted)).hasSize(1);
        assertThat(tools.definitions(mounted).get(0).name()).isEqualTo("rag");
        assertThat(QuestionTools.kbNote(mounted)).contains("已挂载知识库:库");
        assertThat(QuestionTools.toolList(mounted)).contains("- `rag` —");
        assertThat(QuestionTools.kbNote(RetrievalTools.Mounts.NONE)).isEqualTo("(未挂载知识库)");
    }

    @Test
    void rag调用_检索渲染并压缩进轨迹() {
        RetrievalTools.Mounts mounts = new RetrievalTools.Mounts(List.of(new KnowledgeBaseRef(3L, "光学知识库")));
        when(knowledgeBases.retrieve(eq(6L), eq(3L), eq("反射定律"), anyInt())).thenReturn(List.of(
                new RetrievedPassage(3L, "光学知识库", "讲义.pdf", "3.1", "反射角等于入射角。")));
        llm.answerText("概括:反射角等于入射角");
        ExplorationTrace trace = new ExplorationTrace();
        List<String> notices = new ArrayList<>();

        RetrievalTools.SourceRegistry registry = new RetrievalTools.SourceRegistry();
        ChatAgentLoop.ToolResult outcome = tools.runner("key", MODEL, 6L, mounts, registry, trace, notices::add)
                .run("call-1", "rag", "{\"query\":\"反射定律\",\"kb_name\":\"光学知识库\"}");

        assertThat(outcome.content()).isEqualTo("概括:反射角等于入射角");
        assertThat(outcome.isError()).isFalse();
        assertThat(outcome.sources()).singleElement()
                .satisfies(source -> assertThat(source).containsEntry("kbName", "光学知识库"));
        assertThat(trace.render()).contains("工具调用:rag").contains("概括:反射角等于入射角");
        assertThat(notices).isEmpty();

        ChatAgentLoop.ToolResult bad = tools.runner("key", MODEL, 6L, mounts, registry, trace, notices::add)
                .run("call-2", "rag", "{\"query\":\"x\",\"kb_name\":\"不存在\"}");
        assertThat(bad.isError()).isTrue();
        assertThat(bad.content()).contains("kb_name 必须是已挂载的知识库之一");
    }
}
