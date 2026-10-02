package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.learning.LearningEvent;
import cn.utcy.teaching.shared.learning.LearningEventRecorder;
import cn.utcy.teaching.shared.learning.LearningEventType;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.courseware.domain.Block;
import cn.utcy.teaching.courseware.domain.Stage;
import cn.utcy.teaching.courseware.infrastructure.QuizAttemptEntity;
import cn.utcy.teaching.courseware.infrastructure.QuizAttemptMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 测验判分 —— 服务端裁决,正确答案不出服务端;每次作答按学生落库。
 * 学生必须是已发布课程的成员。
 */
@Service
public class QuizGradingService {

    private final CoursewareApplicationService coursewares;
    private final QuizAttemptMapper attemptMapper;
    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;
    private final ObjectMapper objectMapper;
    private final LearningEventRecorder learningEvents;

    public QuizGradingService(CoursewareApplicationService coursewares,
                              QuizAttemptMapper attemptMapper, CourseAccess courseAccess,
                              CurrentActor currentActor, ObjectMapper objectMapper,
            LearningEventRecorder learningEvents) {
        this.coursewares = coursewares;
        this.attemptMapper = attemptMapper;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
        this.objectMapper = objectMapper;
        this.learningEvents = learningEvents;
    }

    public record Verdict(boolean correct, List<String> answer, String explanation) {
    }

    public record MyAttempt(String sceneId, String blockId, List<String> chosen, boolean correct,
                            long attemptedAt) {
    }

    @Transactional
    public Verdict grade(long courseId, long coursewareId, String sceneId, String blockId,
                         List<String> chosen) {
        Actor actor = currentActor.require();
        courseAccess.requireLearningAccess(courseId, actor);
        coursewares.requireLearnable(courseId, coursewareId);
        Stage stage = coursewares.getInternal(courseId, coursewareId);
        Stage.Scene scene = stage.scenes().stream()
                .filter(p -> p.id().equals(sceneId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("页面不存在"));
        Block.QuizChoice quiz = scene.blocks().stream()
                .filter(b -> b.id().equals(blockId))
                .filter(b -> b instanceof Block.QuizChoice)
                .map(b -> (Block.QuizChoice) b)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("题目不存在"));

        // 非法选项标签直接 400——判错入库会污染作答统计
        Set<String> validLabels = new HashSet<>();
        for (Block.QuizOption option : quiz.options()) {
            validLabels.add(option.label());
        }
        for (String label : chosen) {
            if (!validLabels.contains(label)) {
                throw new BadRequestException(
                        "选项 \"" + label + "\" 不存在,合法选项:" + String.join("/", validLabels));
            }
        }

        Set<String> expected = new HashSet<>(quiz.answer());
        Set<String> actual = new HashSet<>(chosen);
        boolean correct = expected.equals(actual);

        attemptMapper.insert(new QuizAttemptEntity(coursewareId, actor.userId(), sceneId, blockId,
                toJson(chosen), correct, LocalDateTime.now(ZoneOffset.UTC)));
        learningEvents.record(new LearningEvent(courseId, actor.userId(),
                LearningEventType.COURSEWARE_QUIZ_ATTEMPTED, coursewareId,
                java.util.Map.of("blockId", blockId, "correct", correct)));

        return new Verdict(correct, quiz.answer(), quiz.explanation());
    }

    @Transactional(readOnly = true)
    public List<MyAttempt> myAttempts(long courseId, long coursewareId) {
        Actor actor = currentActor.require();
        courseAccess.requireLearningAccess(courseId, actor);
        coursewares.requireLearnable(courseId, coursewareId);
        return attemptMapper.selectList(new LambdaQueryWrapper<QuizAttemptEntity>()
                        .eq(QuizAttemptEntity::getCoursewareId, coursewareId)
                        .eq(QuizAttemptEntity::getAccountId, actor.userId())
                        .orderByAsc(QuizAttemptEntity::getAttemptedAt)).stream()
                .map(entity -> new MyAttempt(entity.getSceneId(), entity.getBlockId(),
                        fromJson(entity.getChosen()), Boolean.TRUE.equals(entity.getCorrect()),
                        CoursewareApplicationService.toEpochMilli(entity.getAttemptedAt())))
                .toList();
    }

    private String toJson(List<String> chosen) {
        try {
            return objectMapper.writeValueAsString(chosen);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("作答序列化失败", e);
        }
    }

    private List<String> fromJson(String chosen) {
        try {
            return objectMapper.readValue(chosen, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("作答记录反序列化失败", e);
        }
    }
}
