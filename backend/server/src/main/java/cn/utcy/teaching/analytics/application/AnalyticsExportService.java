package cn.utcy.teaching.analytics.application;

import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.analytics.application.AnalyticsApplicationService.ClassOverview;
import cn.utcy.teaching.analytics.application.AnalyticsApplicationService.NodeClassStat;
import cn.utcy.teaching.analytics.application.AnalyticsApplicationService.StudentSummary;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.analytics.application.ContentCalculator.ContentClassStat;
import cn.utcy.teaching.analytics.application.MasteryCalculator.Level;
import cn.utcy.teaching.course.application.CourseAccess;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 班级学情 Excel:「学生」表每学生一行(基础列 + 每个知识点一列档位文案);「内容」表每项试题 / 编程题一行 */
@Service
public class AnalyticsExportService {

    private final AnalyticsApplicationService analytics;
    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;

    public AnalyticsExportService(AnalyticsApplicationService analytics, CourseAccess courseAccess,
                                  CurrentActor currentActor) {
        this.analytics = analytics;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
    }

    @Transactional(readOnly = true)
    public byte[] exportXlsx(long courseId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        ClassOverview overview = analytics.buildOverview(courseId);
        // 知识点档位需要逐学生节点数据:复用学生报告
        Map<Long, Map<String, Level>> levelsByStudent = new HashMap<>();
        for (StudentSummary student : overview.students()) {
            Map<String, Level> levels = new HashMap<>();
            analytics.studentReport(courseId, student.accountId(), true).mastery()
                    .forEach(node -> levels.put(node.graphId() + ":" + node.nodeId(), node.level()));
            levelsByStudent.put(student.accountId(), levels);
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("学生");
            CellStyle header = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            header.setFont(bold);

            List<String> headers = new ArrayList<>(List.of(
                    "姓名", "用户名", "进度%", "综合掌握度", "薄弱知识点数", "需关注"));
            for (NodeClassStat node : overview.nodes()) {
                headers.add(node.label());
            }
            Row headRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = headRow.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(header);
            }

            int rowIndex = 1;
            for (StudentSummary student : overview.students()) {
                Row row = sheet.createRow(rowIndex++);
                int col = 0;
                row.createCell(col++).setCellValue(student.displayName());
                row.createCell(col++).setCellValue(student.username());
                row.createCell(col++).setCellValue(Math.round(student.progressPercent() * 100));
                row.createCell(col++).setCellValue(student.masteryAverage() == null ? "—"
                        : String.format("%.0f%%", student.masteryAverage() * 100));
                row.createCell(col++).setCellValue(student.weakCount());
                row.createCell(col++).setCellValue(student.needsAttention() ? "是" : "否");
                Map<String, Level> levels = levelsByStudent.getOrDefault(student.accountId(), Map.of());
                for (NodeClassStat node : overview.nodes()) {
                    Level level = levels.get(node.graphId() + ":" + node.nodeId());
                    row.createCell(col++).setCellValue(levelLabel(level));
                }
            }
            for (int i = 0; i < Math.min(headers.size(), 6); i++) {
                sheet.autoSizeColumn(i);
            }
            writeContents(workbook.createSheet("内容"), header, overview);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("学情导出写入失败", exception);
        }
    }

    private static void writeContents(Sheet sheet, CellStyle header, ClassOverview overview) {
        List<String> headers = List.of("名称", "类型", "所属单元", "作答人数", "完成人数", "平均得分", "作答次数");
        Row headRow = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            Cell cell = headRow.createCell(i);
            cell.setCellValue(headers.get(i));
            cell.setCellStyle(header);
        }
        int rowIndex = 1;
        for (ContentClassStat content : overview.contents()) {
            Row row = sheet.createRow(rowIndex++);
            int col = 0;
            row.createCell(col++).setCellValue(content.title());
            row.createCell(col++).setCellValue(content.itemType() == CourseOutlineItemType.QUESTION ? "试题" : "编程题");
            row.createCell(col++).setCellValue(content.units().stream()
                    .map(unit -> unit.isEmpty() ? "顶层" : unit)
                    .collect(Collectors.joining("；")));
            row.createCell(col++).setCellValue(content.attemptedStudents());
            row.createCell(col++).setCellValue(content.completedStudents());
            row.createCell(col++).setCellValue(content.averageScore() == null ? "—"
                    : String.format("%.0f%%", content.averageScore() * 100));
            row.createCell(col++).setCellValue(content.attemptCount());
        }
        for (int i = 0; i < headers.size(); i++) {
            sheet.autoSizeColumn(i);
        }
    }

    static String levelLabel(Level level) {
        if (level == null) {
            return "—";
        }
        return switch (level) {
            case UNTOUCHED -> "未接触";
            case TOUCHED -> "已接触";
            case WEAK -> "薄弱";
            case BASIC -> "基本掌握";
            case PROFICIENT -> "熟练";
        };
    }
}
