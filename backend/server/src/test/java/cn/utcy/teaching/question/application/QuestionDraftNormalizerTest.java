package cn.utcy.teaching.question.application;

import cn.utcy.teaching.question.domain.CourseQuestionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionDraftNormalizerTest {

    private final ObjectMapper json = new ObjectMapper();
    private final QuestionDraftNormalizer normalizer =
            new QuestionDraftNormalizer(json, new CourseQuestionContract(json));

    @Test
    void 字母答案映射回选项原文且无问题() throws Exception {
        ObjectNode question = normalizer.normalize(json.readTree("""
                {"title":"顶点坐标","type":"single_choice","stemMarkdown":"顶点坐标是?",
                 "options":["(1,2)","(2,1)","(0,0)","(-1,2)"],"answer":"A","analysisMarkdown":"配方可得。"}
                """), CourseQuestionType.SINGLE_CHOICE);

        assertThat(question.path("answer").asText()).isEqualTo("(1,2)");
        assertThat(normalizer.collectIssues(question, CourseQuestionType.SINGLE_CHOICE, Set.of())).isEmpty();
    }

    @Test
    void 判断题中文答案转布尔() throws Exception {
        ObjectNode question = normalizer.normalize(json.readTree("""
                {"title":"开口方向","type":"true_false","stemMarkdown":"a>0 时开口向上。",
                 "options":null,"answer":"正确","analysisMarkdown":"由 a 的符号判定。"}
                """), CourseQuestionType.TRUE_FALSE);

        assertThat(question.path("answer").isBoolean()).isTrue();
        assertThat(question.path("answer").booleanValue()).isTrue();
    }

    @Test
    void 答案不在选项内与标题重复都进问题清单() throws Exception {
        ObjectNode question = normalizer.normalize(json.readTree("""
                {"title":"函数性质","type":"single_choice","stemMarkdown":"正确的是?",
                 "options":["开口向上","开口向下","过原点","对称轴 x=1"],"answer":"都不对","analysisMarkdown":"逐项分析。"}
                """), CourseQuestionType.SINGLE_CHOICE);

        List<String> issues = normalizer.collectIssues(question, CourseQuestionType.SINGLE_CHOICE,
                Set.of("函数性质"));

        assertThat(issues).anyMatch(issue -> issue.contains("已有选项"));
        assertThat(issues).anyMatch(issue -> issue.contains("重复"));
    }

    @Test
    void 没有JSON时交付占位题并带问题() {
        ObjectNode question = normalizer.normalize(null, CourseQuestionType.SINGLE_CHOICE);
        List<String> issues = normalizer.collectIssues(question, CourseQuestionType.SINGLE_CHOICE, Set.of());
        QuestionDraftNormalizer.DraftQuestion draft =
                normalizer.toDraft(question, CourseQuestionType.SINGLE_CHOICE, "反射定律", issues);

        assertThat(question).isNull();
        assertThat(draft.title()).isEqualTo("[生成失败] 反射定律");
        assertThat(draft.issues()).containsExactly("模型没有返回题目 JSON");
    }
}
