package cn.utcy.teaching.shared.course;

/**
 * 课程内容(文件 / 试题 / 编程题)删除前其他模块的善后:引用它的地方由各自模块显式解除
 * (知识库文档与图谱构建任务解除来源资料引用、知识图谱节点卸载挂载)。
 * 在删除内容的同一事务内、删除行之前调用。
 */
public interface CourseContentDeletionGuard {

    void beforeContentDeleted(long courseId, CourseOutlineItemType itemType, long contentId);
}
