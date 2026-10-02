package cn.utcy.teaching.shared.learning;

import cn.utcy.teaching.shared.course.CourseOutlineLinks;

/**
 * 学习事件记录器:各模块在**学生路径**的行为发生处调用(教师/管理路径不记),
 * 由 analytics 模块实现为直插 learning_event 表。与 CourseOutlineLinks 同为根级共享接口,
 * 调用方模块不依赖 analytics。
 */
public interface LearningEventRecorder {

    void record(LearningEvent event);
}
