package cn.utcy.teaching.course.application;

import cn.utcy.teaching.ai.application.UserAiConfigService;
import org.springframework.stereotype.Component;

/**
 * 课程内 AI 消费的密钥解析:教师操作与学生触发的问答 / 助教统一计费到**课程负责人**的用户级配置——
 * 学生零配置,平台不持有业务密钥。未配置即在此给出可行动的报错,没有兜底。
 */
@Component
public class CourseAiKeys {

    static final String LLM_HINT = "课程负责人尚未配置大模型 API Key,请负责人在「账户设置 → AI 服务」中配置";
    static final String MINERU_HINT = "课程负责人尚未配置 MinerU 令牌,请负责人在「账户设置 → AI 服务」中配置";

    private final UserAiConfigService configs;
    private final CourseAccess courseAccess;

    public CourseAiKeys(UserAiConfigService configs, CourseAccess courseAccess) {
        this.configs = configs;
        this.courseAccess = courseAccess;
    }

    public String llmKeyForCourse(long courseId) {
        return configs.requireLlmKey(courseAccess.courseOwnerId(courseId), LLM_HINT);
    }

    public String mineruTokenForCourse(long courseId) {
        return configs.requireMineruToken(courseAccess.courseOwnerId(courseId), MINERU_HINT);
    }
}
