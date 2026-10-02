package cn.utcy.teaching.programming.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProblemDifficultyTest {

    @Test
    void exposesOnlyTheThreePlatformDifficulties() {
        assertThat(ProblemDifficulty.values())
                .extracting(ProblemDifficulty::value)
                .containsExactly("easy", "medium", "hard");
    }

    @Test
    void rejectsRemovedBeginnerDifficulty() {
        assertThatThrownBy(() -> ProblemDifficulty.fromValue("beginner"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("未知题目难度：beginner");
    }
}
