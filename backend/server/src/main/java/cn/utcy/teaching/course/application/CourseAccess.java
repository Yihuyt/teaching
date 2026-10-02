package cn.utcy.teaching.course.application;

import cn.utcy.teaching.shared.actor.Actor;

public interface CourseAccess {

    void requireLearningAccess(long courseId, Actor actor);

    void requireManagementAccess(long courseId, Actor actor);

    /** 是否课程管理者(与 requireManagementAccess 同一规则,不抛异常) */
    boolean canManage(long courseId, Actor actor);

    /** 课程负责人(owner)id:课程内 AI 消费统一解析负责人的用户级密钥 */
    long courseOwnerId(long courseId);
}
