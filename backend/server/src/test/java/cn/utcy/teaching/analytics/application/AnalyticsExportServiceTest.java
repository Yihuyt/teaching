package cn.utcy.teaching.analytics.application;

import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.analytics.application.AnalyticsApplicationService.ClassOverview;
import cn.utcy.teaching.analytics.application.AnalyticsApplicationService.NodeClassStat;
import cn.utcy.teaching.analytics.application.AnalyticsApplicationService.NodeMasteryView;
import cn.utcy.teaching.analytics.application.AnalyticsApplicationService.StudentReport;
import cn.utcy.teaching.analytics.application.AnalyticsApplicationService.StudentSummary;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.knowledgegraph.domain.NodeKind;
import cn.utcy.teaching.analytics.application.ContentCalculator.ContentClassStat;
import cn.utcy.teaching.analytics.application.ContentCalculator.StudentContentStat;
import cn.utcy.teaching.analytics.application.MasteryCalculator.Level;
import cn.utcy.teaching.analytics.application.ProgressCalculator.Progress;
import cn.utcy.teaching.course.application.CourseAccess;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalyticsExportServiceTest {

    @Test
    void 导出表头与行数正确() throws Exception {
        AnalyticsApplicationService analytics = mock(AnalyticsApplicationService.class);
        NodeClassStat node = new NodeClassStat(1L, "图谱", 11L, "二次函数", NodeKind.KNOWLEDGE_POINT, true, 0.5, 1, 1, 0, 0, 0, 0);
        StudentSummary student = new StudentSummary(9L, "小明", "xiaoming", 0.5, 0.3, 1, true);
        when(analytics.buildOverview(6L)).thenReturn(new ClassOverview(
                1, 0.5, 0.3, List.of(student),
                List.of(new ContentClassStat(CourseOutlineItemType.QUESTION, 20L, "一元二次方程", List.of("第一章"),
                        1, 1, 0.5, 2, List.of(new StudentContentStat(9L, true, 2, 0.5)))),
                List.of(node)));
        when(analytics.studentReport(eq(6L), eq(9L), eq(true))).thenReturn(new StudentReport(
                9L, "小明", new Progress(2, 1, List.of(), List.of()),
                List.of(new NodeMasteryView(1L, "图谱", 11L, "二次函数", NodeKind.KNOWLEDGE_POINT, 0.3, Level.WEAK,
                        true, List.of())),
                0.3, 1, null));

        AnalyticsExportService service = new AnalyticsExportService(
                analytics, mock(CourseAccess.class), mock(CurrentActor.class));
        byte[] bytes = service.exportXlsx(6L);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("姓名");
            assertThat(sheet.getRow(0).getCell(6).getStringCellValue()).isEqualTo("二次函数");
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("小明");
            assertThat(sheet.getRow(1).getCell(5).getStringCellValue()).isEqualTo("是");
            assertThat(sheet.getRow(1).getCell(6).getStringCellValue()).isEqualTo("薄弱");
            Sheet contents = workbook.getSheet("内容");
            assertThat(contents.getRow(0).getCell(0).getStringCellValue()).isEqualTo("名称");
            assertThat(contents.getRow(1).getCell(0).getStringCellValue()).isEqualTo("一元二次方程");
            assertThat(contents.getRow(1).getCell(2).getStringCellValue()).isEqualTo("第一章");
            assertThat(contents.getRow(1).getCell(5).getStringCellValue()).isEqualTo("50%");
        }
    }
}
