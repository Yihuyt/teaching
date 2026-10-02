package cn.utcy.teaching.knowledgegraph.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TocRecognitionServiceTest {

    @Test
    @DisplayName("目录页连续段:取首个连续 true 段,段后的 true 不算,全 false 为空")
    void firstContiguousRun() {
        assertThat(TocRecognitionService.firstContiguousRun(
                List.of(false, false, true, true, false, true)))
                .containsExactly(3, 4);
        assertThat(TocRecognitionService.firstContiguousRun(List.of(true, true)))
                .containsExactly(1, 2);
        assertThat(TocRecognitionService.firstContiguousRun(List.of(false, false)))
                .isEmpty();
    }
}
