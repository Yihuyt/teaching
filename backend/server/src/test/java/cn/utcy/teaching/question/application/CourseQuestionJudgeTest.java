package cn.utcy.teaching.question.application;

import cn.utcy.teaching.question.domain.CourseQuestionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 逐题判分:三题型比对规则;缺答或形态不对按错,不抛错(整卷交卷允许留空) */
class CourseQuestionJudgeTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void 单选逐字比对() throws Exception {
        assertThat(CourseQuestionApplicationService.judge(CourseQuestionType.SINGLE_CHOICE,
                json.readTree("\"甲\""), json.readTree("\"甲\""))).isTrue();
        assertThat(CourseQuestionApplicationService.judge(CourseQuestionType.SINGLE_CHOICE,
                json.readTree("\"甲\""), json.readTree("\"乙\""))).isFalse();
        assertThat(CourseQuestionApplicationService.judge(CourseQuestionType.SINGLE_CHOICE,
                json.readTree("\"甲\""), json.readTree("[\"甲\"]"))).isFalse();
    }

    @Test
    void 填空按规整后的文本精确比对() throws Exception {
        assertThat(CourseQuestionApplicationService.judge(CourseQuestionType.FILL_IN_BLANK,
                json.readTree("\"Newton\""), json.readTree("\"  newton \""))).isTrue();
        assertThat(CourseQuestionApplicationService.judge(CourseQuestionType.FILL_IN_BLANK,
                json.readTree("\"反射定律\""), json.readTree("\"折射定律\""))).isFalse();
        assertThat(CourseQuestionApplicationService.judge(CourseQuestionType.FILL_IN_BLANK,
                json.readTree("\"a\""), json.readTree("\"  \""))).isFalse();
    }

    @Test
    void 判断题比对布尔_缺答按错() throws Exception {
        assertThat(CourseQuestionApplicationService.judge(CourseQuestionType.TRUE_FALSE,
                json.readTree("true"), json.readTree("true"))).isTrue();
        assertThat(CourseQuestionApplicationService.judge(CourseQuestionType.TRUE_FALSE,
                json.readTree("true"), null)).isFalse();
        assertThat(CourseQuestionApplicationService.judge(CourseQuestionType.TRUE_FALSE,
                json.readTree("true"), json.nullNode())).isFalse();
    }
}
