package cn.utcy.teaching.tutor.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TutorTitleServiceTest {

    @Test
    void 清洗标题前缀引号与末尾标点() {
        assertThat(TutorTitleService.sanitize("标题:「光的反射定律」。")).isEqualTo("光的反射定律");
        assertThat(TutorTitleService.sanitize("**Title: \"Reflection basics\"**\n第二行")).isEqualTo("Reflection basics");
        assertThat(TutorTitleService.sanitize("<think>x</think>折射与反射!")).isEqualTo("折射与反射");
        assertThat(TutorTitleService.fallback("a".repeat(60))).isEqualTo("a".repeat(50) + "…");
    }
}
