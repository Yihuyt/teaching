package cn.utcy.teaching.shared.course;

import cn.utcy.teaching.shared.error.ConflictException;

/**
 * 课程删除前各模块的善后:可以拒绝(抛 ConflictException),也可以显式删除随课程一起消失的行与外部对象。
 * 在删除课程的同一事务内、删除课程内容与课程行之前按 @Order 顺序调用:只做检查的守卫必须标最高优先级,
 * 因为其他守卫对 ES 等外部对象的删除不随数据库事务回滚,拒绝要发生在任何外部删除之前。
 */
public interface CourseDeletionGuard {

    void beforeCourseDeleted(long courseId);
}
