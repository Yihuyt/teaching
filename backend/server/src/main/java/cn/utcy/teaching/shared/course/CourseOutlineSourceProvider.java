package cn.utcy.teaching.shared.course;

import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.error.NotFoundException;

import java.util.Map;
import java.util.Set;

/**
 * 各教学模块把自己的内容(文件 / 试题 / 编程题)以统一契约提供给课程内容。
 * 课程内容只引用就绪的内容:文件已上传完成、编程题已配置测试数据、试题至少有一道题目。
 */
public interface CourseOutlineSourceProvider {

    CourseOutlineItemType type();

    /** 课程内容读取:按 contentId 返回本课程内存在的标题;缺失由注册表当作不变量被破坏处理 */
    Map<Long, String> requireTitles(long courseId, Set<Long> contentIds);

    /** 加入课程内容:锁行;不属于该课程 → NotFoundException;未就绪 → ConflictException;返回标题 */
    String requireLinkable(long courseId, long contentId);
}
