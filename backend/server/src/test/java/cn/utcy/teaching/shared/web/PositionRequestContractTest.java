package cn.utcy.teaching.shared.web;

import cn.utcy.teaching.course.api.CourseController;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class PositionRequestContractTest {

    @Test
    void everyOrderedContentRequestUsesOneBasedPositions() throws NoSuchMethodException {
        assertOneBased(CourseController.UnitOrderRequest.class);
        assertOneBased(CourseController.ItemOrderRequest.class);
    }

    @Test
    void unitAndOutlineItemCreationDoNotAcceptManualPositions() {
        assertThat(CourseController.CreateUnitRequest.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("position");
        assertThat(CourseController.UpdateUnitRequest.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("position", "parentId");
        assertThat(CourseController.AddOutlineItemRequest.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("position");
    }

    private void assertOneBased(Class<?> requestType) throws NoSuchMethodException {
        Method positionAccessor = requestType.getDeclaredMethod("position");
        Min minimum = positionAccessor.getAnnotation(Min.class);
        assertThat(minimum)
                .as("%s.position 必须声明 @Min", requestType.getSimpleName())
                .isNotNull();
        assertThat(minimum.value())
                .as("%s.position 必须从 1 开始", requestType.getSimpleName())
                .isEqualTo(1);
    }
}
