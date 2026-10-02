package cn.utcy.teaching.shared.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextTest {

    @Test
    void truncateCapsSilentlyAndKeepsNull() {
        assertThat(Text.truncate("abcdef", 3)).isEqualTo("abc");
        assertThat(Text.truncate("abc", 3)).isEqualTo("abc");
        assertThat(Text.truncate(null, 3)).isNull();
    }

    @Test
    void abbreviateMarksTheCutAndTreatsNullAsEmpty() {
        assertThat(Text.abbreviate("abcdef", 3)).isEqualTo("abc…");
        assertThat(Text.abbreviate("abc", 3)).isEqualTo("abc");
        assertThat(Text.abbreviate(null, 3)).isEmpty();
    }
}
