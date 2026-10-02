package cn.utcy.teaching.shared.course;

/**
 * 某项内容与课程内容的关系:内容模块用它做学生端访问门禁(isLinked),
 * 删除内容时把它从课程内容里一并移除(unlink,同一事务内)。
 */
public interface CourseOutlineLinks {

    boolean isLinked(long courseId, CourseOutlineItemType itemType, long contentId);

    void unlink(long courseId, CourseOutlineItemType itemType, long contentId);
}
