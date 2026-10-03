package cn.utcy.teaching.blockcoding.application;

import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.blockcoding.domain.BlockCodingModels;
import cn.utcy.teaching.blockcoding.domain.CourseBlockCodingConfig;
import cn.utcy.teaching.blockcoding.infrastructure.CourseBlockCodingConfigMapper;
import cn.utcy.teaching.course.application.CourseAccess;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CourseBlockCodingConfigService {
    private final CourseBlockCodingConfigMapper configs;
    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;

    public CourseBlockCodingConfigService(
            CourseBlockCodingConfigMapper configs,
            CourseAccess courseAccess,
            CurrentActor currentActor
    ) {
        this.configs = configs;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
    }

    /** 课程成员视图：只暴露开关，辅导提示词不下发给学生 */
    public MemberConfigView view(long courseId) {
        courseAccess.requireLearningAccess(courseId, currentActor.require());
        CourseBlockCodingConfig config = configs.selectById(courseId);
        return new MemberConfigView(config != null && config.isEnabled());
    }

    public ManagementConfigView viewForManagement(long courseId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        return toManagementView(configs.selectById(courseId));
    }

    public ManagementConfigView update(long courseId, boolean enabled, String tutorPrompt, String model) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        String prompt = tutorPrompt == null ? "" : tutorPrompt;
        String chosen = BlockCodingModels.require(model);
        CourseBlockCodingConfig config = configs.selectById(courseId);
        if (config == null) {
            config = CourseBlockCodingConfig.create(courseId, enabled, prompt, chosen);
            configs.insert(config);
        } else {
            config.update(enabled, prompt, chosen);
            configs.updateById(config);
        }
        return toManagementView(config);
    }

    void requireEnabledForLearner(long courseId) {
        courseAccess.requireLearningAccess(courseId, currentActor.require());
        CourseBlockCodingConfig config = configs.selectById(courseId);
        if (config == null || !config.isEnabled()) {
            throw new BadRequestException("该课程未开启积木编程辅导");
        }
    }

    /** 助手用的模型:课程没有配置时用默认模型 */
    public String modelForCourse(long courseId) {
        CourseBlockCodingConfig config = configs.selectById(courseId);
        return config == null ? BlockCodingModels.DEFAULT : config.getModel();
    }

    public String activeTutorGuidance(long courseId) {
        CourseBlockCodingConfig config = configs.selectById(courseId);
        if (config == null || !config.isEnabled()) {
            return null;
        }
        String prompt = config.getTutorPrompt();
        return prompt == null || prompt.isBlank() ? null : prompt;
    }

    private static ManagementConfigView toManagementView(CourseBlockCodingConfig config) {
        String prompt = config == null || config.getTutorPrompt() == null ? "" : config.getTutorPrompt();
        String model = config == null ? BlockCodingModels.DEFAULT : config.getModel();
        return new ManagementConfigView(config != null && config.isEnabled(), prompt, model, BlockCodingModels.ALL);
    }

    public record MemberConfigView(boolean enabled) {
    }

    public record ManagementConfigView(boolean enabled, String tutorPrompt, String model, List<String> models) {
    }
}
