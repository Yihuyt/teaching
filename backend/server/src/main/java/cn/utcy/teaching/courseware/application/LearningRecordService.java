package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.learning.LearningEvent;
import cn.utcy.teaching.shared.learning.LearningEventRecorder;
import cn.utcy.teaching.shared.learning.LearningEventType;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.courseware.domain.Stage;
import cn.utcy.teaching.courseware.infrastructure.SceneViewEventEntity;
import cn.utcy.teaching.courseware.infrastructure.SceneViewEventMapper;
import cn.utcy.teaching.courseware.infrastructure.QaEventMapper;
import cn.utcy.teaching.courseware.infrastructure.QuizAttemptMapper;
import cn.utcy.teaching.identity.application.AccountDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LearningRecordService {

    private static final int QA_REPORT_LIMIT = 100;

    private final CoursewareApplicationService coursewares;
    private final SceneViewEventMapper sceneViewMapper;
    private final QuizAttemptMapper attemptMapper;
    private final QaEventMapper qaEventMapper;
    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;
    private final AccountDirectory accounts;
    private final LearningEventRecorder learningEvents;

    public LearningRecordService(CoursewareApplicationService coursewares,
                                 SceneViewEventMapper sceneViewMapper, QuizAttemptMapper attemptMapper,
                                 QaEventMapper qaEventMapper, CourseAccess courseAccess,
                                 CurrentActor currentActor, AccountDirectory accounts,
            LearningEventRecorder learningEvents) {
        this.coursewares = coursewares;
        this.sceneViewMapper = sceneViewMapper;
        this.attemptMapper = attemptMapper;
        this.qaEventMapper = qaEventMapper;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
        this.accounts = accounts;
        this.learningEvents = learningEvents;
    }

    /** 一名学生在本课件上的活动汇总;lastActiveAt 为最近一次浏览或作答 */
    public record StudentActivity(long accountId, String displayName, long scenesViewed,
                                  long quizAttempts, long quizCorrect, long lastActiveAt) {
    }

    public record QuestionStat(String blockId, long attempts, long correctCount) {
    }

    public record QaRecord(long accountId, String displayName, String sceneId, String question,
                           String answer, long askedAt) {
    }

    public record LearningReport(int sceneCount, List<StudentActivity> students,
                                 List<QuestionStat> questions, List<QaRecord> qaRecords) {
    }

    @Transactional
    public void recordSceneView(long courseId, long coursewareId, String sceneId) {
        Actor actor = currentActor.require();
        courseAccess.requireLearningAccess(courseId, actor);
        coursewares.requireLearnable(courseId, coursewareId);
        Stage stage = coursewares.getInternal(courseId, coursewareId);
        boolean sceneExists = stage.scenes().stream().anyMatch(p -> p.id().equals(sceneId));
        if (!sceneExists) {
            throw new NotFoundException("页面不存在");
        }
        sceneViewMapper.insert(new SceneViewEventEntity(coursewareId, actor.userId(), sceneId,
                LocalDateTime.now(ZoneOffset.UTC)));
        learningEvents.record(new LearningEvent(courseId, actor.userId(),
                LearningEventType.COURSEWARE_SCENE_VIEWED, coursewareId, java.util.Map.of("sceneId", sceneId)));
    }

    @Transactional(readOnly = true)
    public LearningReport report(long courseId, long coursewareId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        int sceneCount = coursewares.requireEntity(courseId, coursewareId).getSceneCount();

        Map<Long, StudentActivity> byAccount = new LinkedHashMap<>();
        for (SceneViewEventMapper.SceneViewAccountStat stat
                : sceneViewMapper.summarizeByAccount(coursewareId)) {
            byAccount.put(stat.accountId(), new StudentActivity(stat.accountId(),
                    displayName(stat.accountId()), stat.scenesViewed(), 0, 0,
                    CoursewareApplicationService.toEpochMilli(stat.lastViewedAt())));
        }
        for (QuizAttemptMapper.QuizAccountStat stat : attemptMapper.summarizeByAccount(coursewareId)) {
            StudentActivity existing = byAccount.get(stat.accountId());
            long lastAttempt = CoursewareApplicationService.toEpochMilli(stat.lastAttemptAt());
            if (existing == null) {
                byAccount.put(stat.accountId(), new StudentActivity(stat.accountId(),
                        displayName(stat.accountId()), 0, stat.attempts(), stat.correctCount(),
                        lastAttempt));
            } else {
                byAccount.put(stat.accountId(), new StudentActivity(existing.accountId(),
                        existing.displayName(), existing.scenesViewed(), stat.attempts(),
                        stat.correctCount(), Math.max(existing.lastActiveAt(), lastAttempt)));
            }
        }

        List<QuestionStat> questions = attemptMapper.summarizeByBlock(coursewareId).stream()
                .map(stat -> new QuestionStat(stat.blockId(), stat.attempts(), stat.correctCount()))
                .toList();
        List<QaRecord> qaRecords = qaEventMapper.listRecent(coursewareId, QA_REPORT_LIMIT).stream()
                .map(e -> new QaRecord(e.getAccountId(), displayName(e.getAccountId()),
                        e.getSceneId(), e.getQuestion(), e.getAnswer(),
                        CoursewareApplicationService.toEpochMilli(e.getAskedAt())))
                .toList();
        return new LearningReport(sceneCount, new ArrayList<>(byAccount.values()), questions,
                qaRecords);
    }

    private String displayName(long accountId) {
        return accounts.require(accountId).name();
    }
}
