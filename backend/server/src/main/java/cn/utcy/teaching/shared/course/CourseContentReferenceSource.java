package cn.utcy.teaching.shared.course;

/**
 * 某项课程内容(文件 / 试题 / 编程题)是否与别处关联:删除确认框据此只区分"有 / 无关联",不罗列明细。
 * 关联包括:在课程内容中、挂在知识图谱节点上、已有作答 / 提交记录、文件夹非空。
 */
public interface CourseContentReferenceSource {

    boolean references(long courseId, CourseOutlineItemType itemType, long contentId);
}
