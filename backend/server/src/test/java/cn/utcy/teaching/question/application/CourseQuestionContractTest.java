package cn.utcy.teaching.question.application;

import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.question.domain.CourseQuestionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CourseQuestionContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CourseQuestionContract contract =
            new CourseQuestionContract(objectMapper);

    @Test
    void choiceQuestionRequiresAtLeastTwoOptions() throws Exception {
        assertThatThrownBy(() -> contract.encodeDefinition(
                CourseQuestionType.SINGLE_CHOICE,
                objectMapper.readTree("[\"唯一选项\"]"),
                objectMapper.readTree("\"唯一选项\"")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("至少需要两个选项");
    }

    @Test
    void fillInBlankRequiresTextAnswerAndSingleBlankMark() throws Exception {
        assertThatThrownBy(() -> contract.encodeDefinition(
                CourseQuestionType.FILL_IN_BLANK, null, objectMapper.readTree("[\"甲\"]")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("填空题标准答案必须是非空且不超过 200 字符的字符串");
        assertThatThrownBy(() -> CourseQuestionContract.validateStem(CourseQuestionType.FILL_IN_BLANK, "没有空格的题干"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("恰好包含一处");
        assertThatThrownBy(() -> CourseQuestionContract.validateStem(CourseQuestionType.FILL_IN_BLANK, "____ 和 ____"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> CourseQuestionContract.validateStem(CourseQuestionType.TRUE_FALSE, "含 ____ 的命题"))
                .isInstanceOf(BadRequestException.class);
        CourseQuestionContract.validateStem(CourseQuestionType.FILL_IN_BLANK, "光在真空中的速度约为 ______ m/s");
        contract.encodeDefinition(CourseQuestionType.FILL_IN_BLANK, null, objectMapper.readTree("\"3e8\""));
    }
}
