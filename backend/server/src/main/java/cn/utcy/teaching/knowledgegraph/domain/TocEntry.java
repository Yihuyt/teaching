package cn.utcy.teaching.knowledgegraph.domain;

/**
 * 教材目录条目(阅读顺序的扁平列表 + level 表达层级)。
 * page / endPage 为 PDF 物理页闭区间(1 起);草稿阶段 0 表示页码未对齐,确认稿不允许 0。
 * 止页是条目自己的属性(识别阶段按全书结构推得初值,教师可改):不落在任何确认条目
 * 区间内的页不参与抽取——删条目即剔除该段内容,想并入邻居就改邻居的止页。
 */
public record TocEntry(String number, String title, int level, int page, int endPage) {
}
