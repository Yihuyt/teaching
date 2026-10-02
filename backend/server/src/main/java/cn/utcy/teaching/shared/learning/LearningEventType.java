package cn.utcy.teaching.shared.learning;

/**
 * 学习事件词表(与 learning_event 表的 CHECK 约束一致)。
 * 只记录学生路径上的行为;各模块在行为发生处经 {@link LearningEventRecorder} 写入。
 */
public enum LearningEventType {
    QUESTION_ATTEMPTED("question_attempted", "question"),
    COURSEWARE_SCENE_VIEWED("courseware_scene_viewed", "courseware"),
    COURSEWARE_QUIZ_ATTEMPTED("courseware_quiz_attempted", "courseware"),
    COURSEWARE_QUESTION_ASKED("courseware_question_asked", "courseware"),
    KB_QUESTION_ASKED("kb_question_asked", "knowledge_base"),
    KG_VIEWED("kg_viewed", "knowledge_graph"),
    KG_RESOURCE_OPENED("kg_resource_opened", "knowledge_graph"),
    PROGRAMMING_JUDGED("programming_judged", "programming_problem"),
    /** 智能问答(对话型助手)的学生提问,对象为会话;KB_QUESTION_ASKED 仅保留以读历史行 */
    TUTOR_QUESTION_ASKED("tutor_question_asked", "tutor_session");

    private final String value;
    private final String objectType;

    LearningEventType(String value, String objectType) {
        this.value = value;
        this.objectType = objectType;
    }

    public String value() {
        return value;
    }

    public String objectType() {
        return objectType;
    }

    public static LearningEventType fromValue(String value) {
        for (LearningEventType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知学习事件类型：" + value);
    }
}
