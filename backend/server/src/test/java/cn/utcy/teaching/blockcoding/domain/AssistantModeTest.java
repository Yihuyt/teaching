package cn.utcy.teaching.blockcoding.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantModeTest {
    @Test
    void 课程管理者两种都能用_其他人只有讲解_没明确要修改就是讲解() {
        assertThat(AssistantMode.allowedFor(false)).containsExactly(AssistantMode.CHAT);
        assertThat(AssistantMode.allowedFor(true)).containsExactly(AssistantMode.AGENT, AssistantMode.CHAT);
        assertThat(AssistantMode.resolve(AssistantMode.allowedFor(false), "agent")).isEqualTo(AssistantMode.CHAT);
        assertThat(AssistantMode.resolve(AssistantMode.allowedFor(true), null)).isEqualTo(AssistantMode.CHAT);
        assertThat(AssistantMode.resolve(AssistantMode.allowedFor(true), " Agent ")).isEqualTo(AssistantMode.AGENT);
        assertThat(AssistantMode.resolve(AssistantMode.allowedFor(true), "whatever")).isEqualTo(AssistantMode.CHAT);
    }
}
