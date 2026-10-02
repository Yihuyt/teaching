package cn.utcy.teaching.question.application;

import cn.utcy.teaching.ai.llm.FakeLlm;
import cn.utcy.teaching.ai.document.MineruClient;
import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.ai.llm.PromptLoader;
import cn.utcy.teaching.question.domain.CourseQuestionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExamMimicSourceTest {

    private static final ModelConfig MODEL =
            new ModelConfig("q", "qwen-test", false, 0.4, 0.9, 2048);

    private final ObjectMapper json = new ObjectMapper();
    private final MineruClient mineru = mock(MineruClient.class);
    private final FakeLlm llm = new FakeLlm();

    @Test
    void 解析抽题并转成仿写模板() {
        when(mineru.parseToMarkdown(eq("tok"), eq("https://oss/p"), any(), any())).thenReturn("# 期中卷\n1. 光速……");
        llm.answerText("""
                ```json
                {"questions":[
                  {"question_number":"1","question_text":"光在真空中的速度约为?A. 3e8 B. 3e6","question_type":"single_choice","difficulty":"easy","answer":"A"},
                  {"question_number":"2","question_text":"简述反射定律。","question_type":"short_answer","difficulty":"medium","answer":""},
                  {"question_number":"3","question_text":"平面镜成的是虚像。","question_type":"true_false","difficulty":"weird","answer":"对"},
                  {"question_number":"4","question_text":"","question_type":"true_false","difficulty":"easy","answer":""},
                  {"question_number":"5","question_text":"多余的题","question_type":"multiple_choice","difficulty":"hard","answer":"AB"}
                ]}
                ```""");
        List<String> notices = new ArrayList<>();
        List<String> progress = new ArrayList<>();

        List<QuestionPipelineService.QuizTemplate> templates = new ExamMimicSource(mineru, llm,
                new PromptLoader(json), MODEL, json).templates("key",
                new QuestionAttachments.Attachment(30L, "期中卷.pdf", "https://oss/p", "tok"), 4,
                progress::add, notices::add, () -> false);

        assertThat(templates).hasSize(2);
        assertThat(templates.get(0).questionId()).isEqualTo("q_1");
        assertThat(templates.get(0).type()).isEqualTo(CourseQuestionType.SINGLE_CHOICE);
        assertThat(templates.get(0).source()).isEqualTo("mimic");
        assertThat(templates.get(0).referenceQuestion()).startsWith("光在真空中的速度约为");
        assertThat(templates.get(0).referenceAnswer()).isEqualTo("A");
        assertThat(templates.get(1).questionId()).isEqualTo("q_3");
        assertThat(templates.get(1).difficulty()).isEqualTo("medium");
        assertThat(templates.get(1).referenceAnswer()).isEqualTo("对");
        assertThat(notices).singleElement().asString().contains("第 2 题是简答题").contains("已跳过");
        assertThat(progress).anyMatch(m -> m.contains("正在用模型抽取题目"));
    }

    @Test
    void 模型未返回JSON时明确失败() {
        when(mineru.parseToMarkdown(anyString(), anyString(), any(), any())).thenReturn("卷子");
        llm.answerText("这不是 JSON");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ExamMimicSource(mineru, llm,
                new PromptLoader(json), MODEL, json).templates("key",
                new QuestionAttachments.Attachment(30L, "期中卷.pdf", "https://oss/p", "tok"), 10,
                m -> { }, m -> { }, () -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("试卷抽题失败");
    }
}
