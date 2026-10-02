package cn.utcy.teaching.question.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.course.CourseOutlineLinks;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.learning.LearningEvent;
import cn.utcy.teaching.shared.learning.LearningEventRecorder;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.identity.application.AccountDirectory;
import cn.utcy.teaching.question.domain.CourseQuestion;
import cn.utcy.teaching.question.domain.CourseQuestionAttempt;
import cn.utcy.teaching.question.domain.CourseQuestionItem;
import cn.utcy.teaching.question.domain.CourseQuestionType;
import cn.utcy.teaching.question.infrastructure.CourseQuestionAttemptMapper;
import cn.utcy.teaching.question.infrastructure.CourseQuestionItemMapper;
import cn.utcy.teaching.question.infrastructure.CourseQuestionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CourseQuestionAttemptRulesTest {

    @Test
    @SuppressWarnings("unchecked")
    void overdueSubmitSettlesEmptyAndReturnsResult() {
        Fixture fixture = new Fixture();
        when(fixture.outlineLinks.isLinked(20L, cn.utcy.teaching.shared.course.CourseOutlineItemType.QUESTION, 10L))
                .thenReturn(true);
        when(fixture.questions.selectOne(any(LambdaQueryWrapper.class))).thenReturn(question());
        when(fixture.items.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(choiceItem()));
        CourseQuestionAttempt attempt = expiredAttempt();
        when(fixture.attempts.selectForUpdate(77L)).thenReturn(attempt);
        when(fixture.attempts.updateById(attempt)).thenReturn(1);

        var result = fixture.service.submitAttempt(20L, 10L, 77L,
                List.of(new CourseQuestionApplicationService.ItemAnswer(101L, new TextNode("正确答案"))));

        // 逾期:提交内容被忽略,按空卷结算并返回带 overdue 标记的结果;交卷照记学情(交卷方式不区分)
        assertThat(result.score()).isEqualTo(0);
        assertThat(result.overdue()).isTrue();
        assertThat(attempt.submitted()).isTrue();
        verify(fixture.learningEvents).record(any(LearningEvent.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void expiredSettlementSkipsAttemptAlreadySettledByConcurrentRequest() {
        Fixture fixture = new Fixture();
        when(fixture.outlineLinks.isLinked(20L, cn.utcy.teaching.shared.course.CourseOutlineItemType.QUESTION, 10L))
                .thenReturn(true);
        when(fixture.questions.selectOne(any(LambdaQueryWrapper.class))).thenReturn(question());
        when(fixture.questions.selectForUpdate(20L, 10L)).thenReturn(question());
        when(fixture.items.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(choiceItem()));
        CourseQuestionAttempt stale = expiredAttempt();
        CourseQuestionAttempt settled = expiredAttempt();
        settled.submit(Instant.now(), 0, "[]", "[]", true);
        when(fixture.attempts.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(stale)).thenReturn(List.of(settled));
        when(fixture.attempts.selectForUpdate(77L)).thenReturn(settled);
        when(fixture.attempts.insert(any(CourseQuestionAttempt.class))).thenAnswer(invocation -> {
            CourseQuestionAttempt inserted = invocation.getArgument(0);
            setId(inserted, 88L);
            return 1;
        });

        fixture.service.startAttempt(20L, 10L);

        // 锁后发现已被并发请求结算:不重复结算、不重复记学情事件
        verify(fixture.attempts, never()).updateById(any(CourseQuestionAttempt.class));
        verify(fixture.learningEvents, never()).record(any(LearningEvent.class));
    }

    /** 只读事务里查看结果不能加锁(MySQL 只读事务执行 FOR UPDATE 会报错);交卷才锁作答行 */
    @Test
    @SuppressWarnings("unchecked")
    void viewingOwnResultReadsWithoutRowLock() {
        Fixture fixture = new Fixture();
        when(fixture.outlineLinks.isLinked(20L, cn.utcy.teaching.shared.course.CourseOutlineItemType.QUESTION, 10L))
                .thenReturn(true);
        when(fixture.questions.selectOne(any(LambdaQueryWrapper.class))).thenReturn(question());
        when(fixture.items.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(choiceItem()));
        CourseQuestionAttempt settled = expiredAttempt();
        settled.submit(Instant.now(), 0, "[]", "[]", true);
        when(fixture.attempts.selectById(77L)).thenReturn(settled);

        var result = fixture.service.getAttempt(20L, 10L, 77L);

        assertThat(result.overdue()).isTrue();
        verify(fixture.attempts, never()).selectForUpdate(77L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateRejectsItemChangesWhileAnyAttemptExists() {
        Fixture fixture = new Fixture();
        when(fixture.questions.selectForUpdate(20L, 10L)).thenReturn(question());
        when(fixture.questions.updateById(any(CourseQuestion.class))).thenReturn(1);
        when(fixture.items.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(choiceItem()));
        when(fixture.attempts.exists(any(LambdaQueryWrapper.class))).thenReturn(true);

        assertThatThrownBy(() -> fixture.service.update(20L, 10L, save(saveItem("改过的题干"))))
                .isInstanceOf(ConflictException.class)
                .hasMessage("已有学生作答，不能修改题目内容");

        verify(fixture.items, never()).delete(any());
        verify(fixture.items, never()).insert(any(CourseQuestionItem.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateSettingsOnlyKeepsItemRowsAfterAttemptsExist() {
        Fixture fixture = new Fixture();
        when(fixture.questions.selectForUpdate(20L, 10L)).thenReturn(question());
        when(fixture.questions.updateById(any(CourseQuestion.class))).thenReturn(1);
        when(fixture.items.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(choiceItem()));

        var detail = fixture.service.update(20L, 10L, save(saveItem("请选择正确答案")));

        // 题目未变:保留原题目行(历史作答按行 id 关联),只更新标题与设置
        assertThat(detail.items()).hasSize(1);
        assertThat(detail.items().get(0).id()).isEqualTo(101L);
        verify(fixture.items, never()).delete(any());
        verify(fixture.items, never()).insert(any(CourseQuestionItem.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void overlongBlankAnswerIsRejectedBeforeSettlement() {
        Fixture fixture = new Fixture();
        when(fixture.outlineLinks.isLinked(20L, cn.utcy.teaching.shared.course.CourseOutlineItemType.QUESTION, 10L))
                .thenReturn(true);
        when(fixture.questions.selectOne(any(LambdaQueryWrapper.class))).thenReturn(question());
        CourseQuestionItem blank = new CourseQuestionItem(10L, 1, CourseQuestionType.FILL_IN_BLANK,
                "水的沸点是 ____ 摄氏度", null, "\"100\"", "", 10);
        setId(blank, 102L);
        when(fixture.items.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(blank));
        CourseQuestionAttempt attempt = CourseQuestionAttempt.start(question(), 9L, Instant.now());
        setId(attempt, 77L);
        when(fixture.attempts.selectForUpdate(77L)).thenReturn(attempt);

        assertThatThrownBy(() -> fixture.service.submitAttempt(20L, 10L, 77L,
                List.of(new CourseQuestionApplicationService.ItemAnswer(102L, new TextNode("答".repeat(201))))))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("填空作答不能超过 200 字符");

        verify(fixture.attempts, never()).updateById(any(CourseQuestionAttempt.class));
    }

    private static CourseQuestion question() {
        CourseQuestion question = CourseQuestion.create(20L, "第一章测验", new CourseQuestion.Settings(null, true, true));
        setId(question, 10L);
        return question;
    }

    private static CourseQuestionItem choiceItem() {
        CourseQuestionItem item = new CourseQuestionItem(10L, 1, CourseQuestionType.SINGLE_CHOICE, "请选择正确答案",
                "[\"错误答案\",\"正确答案\"]", "\"正确答案\"", "答案解析", 10);
        setId(item, 101L);
        return item;
    }

    private static CourseQuestionAttempt expiredAttempt() {
        CourseQuestion timed = CourseQuestion.create(20L, "第一章测验", new CourseQuestion.Settings(1, true, true));
        setId(timed, 10L);
        CourseQuestionAttempt attempt = CourseQuestionAttempt.start(timed, 9L, Instant.now().minusSeconds(600));
        setId(attempt, 77L);
        return attempt;
    }

    private static CourseQuestionApplicationService.SaveItem saveItem(String stem) {
        ObjectMapper mapper = new ObjectMapper();
        return new CourseQuestionApplicationService.SaveItem(CourseQuestionType.SINGLE_CHOICE, stem,
                mapper.createArrayNode().add("错误答案").add("正确答案"), new TextNode("正确答案"), "答案解析", 10);
    }

    private static CourseQuestionApplicationService.SaveQuestion save(CourseQuestionApplicationService.SaveItem item) {
        return new CourseQuestionApplicationService.SaveQuestion("第一章测验", null, true, true, List.of(item));
    }

    private static void setId(Object entity, long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class Fixture {
        private final Actor actor = new Actor(9L, "student", SystemRole.STUDENT);
        private final CourseQuestionMapper questions = mock(CourseQuestionMapper.class);
        private final CourseQuestionItemMapper items = mock(CourseQuestionItemMapper.class);
        private final CourseQuestionAttemptMapper attempts = mock(CourseQuestionAttemptMapper.class);
        private final CurrentActor currentActor = mock(CurrentActor.class);
        private final CourseAccess courseAccess = mock(CourseAccess.class);
        private final CourseOutlineLinks outlineLinks = mock(CourseOutlineLinks.class);
        private final LearningEventRecorder learningEvents = mock(LearningEventRecorder.class);
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final CourseQuestionApplicationService service;

        private Fixture() {
            when(currentActor.require()).thenReturn(actor);
            service = new CourseQuestionApplicationService(questions, items, attempts, currentActor, courseAccess,
                    outlineLinks, new CourseQuestionContract(objectMapper),
                    learningEvents, mock(AccountDirectory.class), objectMapper,
                    mock(cn.utcy.teaching.question.infrastructure.CourseQuestionRowPurger.class), List.of());
        }
    }
}
