package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.courseware.domain.Stage;
import cn.utcy.teaching.courseware.infrastructure.pptx.PptxWriter;
import org.springframework.stereotype.Service;

@Service
public class PptxExportService {

    private final CoursewareApplicationService coursewares;
    private final PptxWriter writer;
    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;

    public PptxExportService(CoursewareApplicationService coursewares, PptxWriter writer,
                             CourseAccess courseAccess, CurrentActor currentActor) {
        this.coursewares = coursewares;
        this.writer = writer;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
    }

    public record ExportFile(String filename, byte[] bytes) {
    }

    public ExportFile exportPptx(long courseId, long coursewareId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        Stage stage = coursewares.getInternal(courseId, coursewareId);
        return new ExportFile(stage.title() + ".pptx", writer.write(stage));
    }
}
