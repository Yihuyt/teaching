package cn.utcy.teaching.question.application;

import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.course.CourseContentDeletionGuard;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.course.CourseOutlineLinks;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.learning.LearningEvent;
import cn.utcy.teaching.shared.learning.LearningEventRecorder;
import cn.utcy.teaching.shared.learning.LearningEventType;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.identity.application.AccountDirectory;
import cn.utcy.teaching.question.domain.CourseQuestion;
import cn.utcy.teaching.question.domain.CourseQuestionAttempt;
import cn.utcy.teaching.question.domain.CourseQuestionItem;
import cn.utcy.teaching.question.domain.CourseQuestionType;
import cn.utcy.teaching.question.infrastructure.AccountAttemptSummary;
import cn.utcy.teaching.question.infrastructure.CourseQuestionAttemptMapper;
import cn.utcy.teaching.question.infrastructure.CourseQuestionRowPurger;
import cn.utcy.teaching.question.infrastructure.CourseQuestionItemMapper;
import cn.utcy.teaching.question.infrastructure.CourseQuestionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.List;
import java.util.Map;

/**
 * 试题 = 一份带分值的卷子。教师在资料库里建卷(若干单选 / 填空 / 判断,每题分值;限时 / 是否可重做 / 交卷后是否公开答案);
 * 学生从课程内容打开,开始作答建档(含截止时刻),交卷由服务端逐题判分、汇总得分并记学情事件。
 * 超过截止 60 秒仍未交的作答按空卷结算(下次打开时结算),不接受补交。
 */
@Service
public class CourseQuestionApplicationService {

    public static final int MAX_ITEMS = 100;
    public static final double MAX_ITEM_SCORE = 1000;
    public static final int MAX_TIME_LIMIT_MINUTES = 600;
    /** 交卷宽限:客户端倒计时归零后自动交卷,留给网络的余量 */
    static final long SUBMIT_GRACE_SECONDS = 60;

    private final CourseQuestionMapper questions;
    private final CourseQuestionItemMapper items;
    private final CourseQuestionAttemptMapper attempts;
    private final CurrentActor currentActor;
    private final CourseAccess courseAccess;
    private final CourseOutlineLinks courseOutlineLinks;
    private final CourseQuestionContract contract;
    private final LearningEventRecorder learningEvents;
    private final AccountDirectory accounts;
    private final ObjectMapper objectMapper;
    private final CourseQuestionRowPurger purger;
    private final List<CourseContentDeletionGuard> deletionGuards;

    public CourseQuestionApplicationService(
            CourseQuestionMapper questions,
            CourseQuestionItemMapper items,
            CourseQuestionAttemptMapper attempts,
            CurrentActor currentActor,
            CourseAccess courseAccess,
            CourseOutlineLinks courseOutlineLinks,
            CourseQuestionContract contract,
            LearningEventRecorder learningEvents,
            AccountDirectory accounts,
            ObjectMapper objectMapper,
            CourseQuestionRowPurger purger,
            List<CourseContentDeletionGuard> deletionGuards
    ) {
        this.purger = purger;
        this.deletionGuards = List.copyOf(deletionGuards);
        this.questions = questions;
        this.items = items;
        this.attempts = attempts;
        this.currentActor = currentActor;
        this.courseAccess = courseAccess;
        this.courseOutlineLinks = courseOutlineLinks;
        this.contract = contract;
        this.learningEvents = learningEvents;
        this.accounts = accounts;
        this.objectMapper = objectMapper;
    }

    // ---- 命令与视图 ----

    public record SaveItem(CourseQuestionType type, String stemMarkdown, JsonNode options, JsonNode answer,
                           String analysisMarkdown, double score) {
    }

    public record SaveQuestion(String title, Integer timeLimitMinutes, boolean allowRetake, boolean revealAnswers,
                               List<SaveItem> items) {
    }

    public record CourseQuestionView(long id, long courseId, String title,
                                     @Schema(nullable = true) Integer timeLimitMinutes,
                                     boolean allowRetake, boolean revealAnswers, int itemCount, double totalScore,
                                     Instant createdAt, Instant updatedAt) {
    }

    public record ItemView(long id, int position, CourseQuestionType type, String stemMarkdown,
                           @Schema(nullable = true) JsonNode options, JsonNode answer, String analysisMarkdown,
                           double score) {
    }

    public record CourseQuestionDetail(long id, long courseId, String title,
                                       @Schema(nullable = true) Integer timeLimitMinutes,
                                       boolean allowRetake, boolean revealAnswers, double totalScore,
                                       List<ItemView> items,
                                       /** 已有学生作答:题目内容锁定,只可改标题与作答设置 */
                                       boolean contentLocked,
                                       Instant createdAt, Instant updatedAt) {
    }

    public record LearningItemView(long id, int position, CourseQuestionType type, String stemMarkdown,
                                   @Schema(nullable = true) JsonNode options, double score) {
    }

    public record ActiveAttemptView(long id, Instant startedAt, @Schema(nullable = true) Instant deadlineAt,
                                    /** 服务端时钟算出的剩余秒数(不限时为空):前端据此倒计时,不信任本机时钟 */
                                    @Schema(nullable = true) Long remainingSeconds) {

        static ActiveAttemptView of(CourseQuestionAttempt attempt, Instant now) {
            Long remaining = attempt.getDeadlineAt() == null
                    ? null : Math.max(0, Duration.between(now, attempt.getDeadlineAt()).getSeconds());
            return new ActiveAttemptView(attempt.getId(), attempt.getStartedAt(), attempt.getDeadlineAt(), remaining);
        }
    }

    public record LearningQuestionView(long id, long courseId, String title,
                                       @Schema(nullable = true) Integer timeLimitMinutes,
                                       boolean allowRetake, boolean revealAnswers, double totalScore,
                                       List<LearningItemView> items, int attemptCount,
                                       @Schema(nullable = true) Double bestScore,
                                       @Schema(nullable = true) ActiveAttemptView activeAttempt,
                                       boolean canStart) {
    }

    public record ItemAnswer(long itemId, @Schema(nullable = true) JsonNode answer) {
    }

    /** 逐题结果:公开答案时带标准答案与解析,否则为 null */
    public record ItemResultView(long itemId, int position, boolean correct, double score, double maxScore,
                                 @Schema(nullable = true) JsonNode given,
                                 @Schema(nullable = true) JsonNode answer,
                                 @Schema(nullable = true) String analysisMarkdown) {
    }

    public record AttemptResult(long attemptId, long questionId, double score, double totalScore,
                                Instant submittedAt, boolean revealAnswers,
                                /** 超过作答时限后结算的空卷:未采纳任何作答 */
                                boolean overdue,
                                List<ItemResultView> items) {
    }

    public record StudentResultView(long accountId, String accountName, int attemptCount, double bestScore,
                                    Instant lastSubmittedAt) {
    }

    public record AttachedQuestionView(long id, String title, int itemCount, double totalScore) {
    }

    // ---- 教师端 ----

    @Transactional(readOnly = true)
    public List<CourseQuestionView> list(long courseId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        List<CourseQuestion> rows = questions.selectList(new LambdaQueryWrapper<CourseQuestion>()
                .eq(CourseQuestion::getCourseId, courseId)
                .orderByDesc(CourseQuestion::getUpdatedAt)
                .orderByDesc(CourseQuestion::getId));
        Map<Long, Summary> summaries = summaries(rows.stream().map(CourseQuestion::getId).toList());
        return rows.stream().map(q -> view(q, summaries.get(q.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public CourseQuestionDetail get(long courseId, long questionId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        CourseQuestion question = require(courseId, questionId);
        return detail(question, listItems(questionId));
    }

    @Transactional
    public CourseQuestionDetail create(long courseId, SaveQuestion command) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        List<EncodedItem> encoded = encode(command);
        CourseQuestion question = CourseQuestion.create(courseId, command.title().strip(), settings(command));
        requireMutation(questions.insert(question), "试题创建未生效");
        replaceItems(question.getId(), encoded);
        return detail(question, listItems(question.getId()));
    }

    /**
     * 更新:标题与作答设置随时可改;题目内容(题干 / 选项 / 答案 / 解析 / 分值 / 顺序)在已有学生
     * 作答后锁定——历史作答的逐题结果以题目行 id 为键,重建题目行会让全部历史成绩明细失效。
     */
    @Transactional
    public CourseQuestionDetail update(long courseId, long questionId, SaveQuestion command) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        CourseQuestion question = requireForUpdate(courseId, questionId);
        List<EncodedItem> encoded = encode(command);
        CourseQuestion.Settings next = settings(command);
        // 有学生正在作答时不能改作答设置:关重做会让其下次提交被拒,开公开答案会泄给作答中的人
        if (!next.equals(question.settings()) && attempts.exists(new LambdaQueryWrapper<CourseQuestionAttempt>()
                .eq(CourseQuestionAttempt::getQuestionId, questionId)
                .isNull(CourseQuestionAttempt::getSubmittedAt))) {
            throw new ConflictException("有学生正在作答，暂不能修改作答设置");
        }
        question.update(command.title().strip(), next);
        requireMutation(questions.updateById(question), "试题状态已变化，更新未生效");
        if (!sameItems(listItems(questionId), encoded)) {
            if (hasAttempt(questionId)) {
                throw new ConflictException("已有学生作答，不能修改题目内容");
            }
            replaceItems(questionId, encoded);
        }
        return detail(question, listItems(questionId));
    }

    /**
     * 有过任何作答(含进行中)题目内容就锁定:重建题目行会换 id,进行中的作答按旧 id 交卷会被判成全空,
     * 已交卷的历史明细也会失去关联。
     */
    private boolean hasAttempt(long questionId) {
        return attempts.exists(new LambdaQueryWrapper<CourseQuestionAttempt>()
                .eq(CourseQuestionAttempt::getQuestionId, questionId));
    }

    private boolean sameItems(List<CourseQuestionItem> existing, List<EncodedItem> encoded) {
        if (existing.size() != encoded.size()) {
            return false;
        }
        for (int index = 0; index < existing.size(); index++) {
            CourseQuestionItem current = existing.get(index);
            EncodedItem next = encoded.get(index);
            if (current.getType() != next.type()
                    || !current.getStemMarkdown().equals(next.stemMarkdown())
                    || !jsonEquals(current.getOptionsJson(), next.optionsJson())
                    || !jsonEquals(current.getAnswerJson(), next.answerJson())
                    || !current.getAnalysisMarkdown().equals(next.analysisMarkdown())
                    || current.getScore() != next.score()) {
                return false;
            }
        }
        return true;
    }

    /** JSON 结构等价(存库串的空白 / 键序不影响比较) */
    private boolean jsonEquals(String left, String right) {
        if (Objects.equals(left, right)) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return readJson(left).equals(readJson(right));
    }

    /** 删除是教师的明确决定,一律级联:从课程内容移除、图谱节点卸载、作答与成绩一并删除 */
    @Transactional
    public void delete(long courseId, List<Long> questionIds) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        List<Long> locked = new ArrayList<>();
        for (Long questionId : questionIds) {
            CourseQuestion question = requireForUpdate(courseId, questionId);
            courseOutlineLinks.unlink(courseId, CourseOutlineItemType.QUESTION, questionId);
            deletionGuards.forEach(guard -> guard.beforeContentDeleted(
                    courseId, CourseOutlineItemType.QUESTION, questionId));
            locked.add(question.getId());
        }
        purger.purgeQuestions(locked);
    }

    /** 删除确认:有过作答即"有关联" */
    @Transactional(readOnly = true)
    public boolean hasAttempts(long courseId, long questionId) {
        return questions.exists(new LambdaQueryWrapper<CourseQuestion>()
                .eq(CourseQuestion::getId, questionId)
                .eq(CourseQuestion::getCourseId, courseId))
                && attempts.exists(new LambdaQueryWrapper<CourseQuestionAttempt>()
                .eq(CourseQuestionAttempt::getQuestionId, questionId));
    }

    @Transactional(readOnly = true)
    public List<StudentResultView> results(long courseId, long questionId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        require(courseId, questionId);
        List<StudentResultView> rows = new ArrayList<>();
        for (AccountAttemptSummary row : attempts.summarizeByAccount(questionId)) {
            AccountDirectory.AccountSummary account = accounts.require(row.accountId());
            rows.add(new StudentResultView(row.accountId(), account.name(), row.attemptCount(),
                    row.bestScore(), row.lastSubmittedAt()));
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public List<AttemptResult> studentAttempts(long courseId, long questionId, long accountId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        CourseQuestion question = require(courseId, questionId);
        List<CourseQuestionItem> questionItems = listItems(questionId);
        return attemptsOf(questionId, accountId).stream()
                .filter(CourseQuestionAttempt::submitted)
                .sorted((a, b) -> b.getSubmittedAt().compareTo(a.getSubmittedAt()))
                .map(attempt -> resultView(question, questionItems, attempt, true))
                .toList();
    }

    /**
     * 供其他模块在**自行完成访问控制后**读取试题摘要(当前调用方:知识图谱的节点挂载资源);
     * 仅校验试题属于该课程,不查课程内容。
     */
    @Transactional(readOnly = true)
    public AttachedQuestionView getTrusted(long courseId, long questionId) {
        CourseQuestion question = require(courseId, questionId);
        Summary summary = summaries(List.of(questionId)).get(questionId);
        return new AttachedQuestionView(question.getId(), question.getTitle(),
                summary == null ? 0 : summary.itemCount(), summary == null ? 0 : summary.totalScore());
    }

    // ---- 学生端 ----

    @Transactional
    public LearningQuestionView getForLearning(long courseId, long questionId) {
        long accountId = currentActor.require().userId();
        CourseQuestion question = requireLinkedForLearning(courseId, questionId);
        List<CourseQuestionItem> questionItems = listItems(questionId);
        Instant now = Instant.now();
        settleExpired(question, questionItems, accountId, now);
        List<CourseQuestionAttempt> mine = attemptsOf(questionId, accountId);
        CourseQuestionAttempt active = mine.stream().filter(a -> !a.submitted()).findFirst().orElse(null);
        List<CourseQuestionAttempt> submitted = mine.stream().filter(CourseQuestionAttempt::submitted).toList();
        Double best = submitted.stream().map(CourseQuestionAttempt::getScore).max(Double::compare).orElse(null);
        boolean canStart = active != null || submitted.isEmpty() || question.getAllowRetake();
        return new LearningQuestionView(question.getId(), question.getCourseId(), question.getTitle(),
                question.getTimeLimitMinutes(), question.getAllowRetake(), question.getRevealAnswers(),
                totalScore(questionItems), questionItems.stream().map(this::learningItem).toList(),
                submitted.size(), best,
                active == null ? null : ActiveAttemptView.of(active, now),
                canStart);
    }

    @Transactional
    public ActiveAttemptView startAttempt(long courseId, long questionId) {
        long accountId = currentActor.require().userId();
        // 锁试题行:并发的两次开始否则都会看到"没有进行中的作答"而各插一条,绕过不可重做限制
        CourseQuestion question = requireLinkedForLearning(courseId, questionId, true);
        List<CourseQuestionItem> questionItems = listItems(questionId);
        if (questionItems.isEmpty()) {
            throw new ConflictException("该试题还没有题目");
        }
        Instant now = Instant.now();
        settleExpired(question, questionItems, accountId, now);
        List<CourseQuestionAttempt> mine = attemptsOf(questionId, accountId);
        for (CourseQuestionAttempt attempt : mine) {
            if (!attempt.submitted()) {
                return ActiveAttemptView.of(attempt, now);
            }
        }
        if (!mine.isEmpty() && !question.getAllowRetake()) {
            throw new ConflictException("该试题只能作答一次，你已交卷");
        }
        CourseQuestionAttempt attempt = CourseQuestionAttempt.start(question, accountId, now);
        attempts.insert(attempt);
        return ActiveAttemptView.of(attempt, now);
    }

    @Transactional
    public AttemptResult submitAttempt(long courseId, long questionId, long attemptId, List<ItemAnswer> answers) {
        long accountId = currentActor.require().userId();
        CourseQuestion question = requireLinkedForLearning(courseId, questionId);
        CourseQuestionAttempt attempt = requireOwnAttempt(questionId, attemptId, accountId, true);
        if (attempt.submitted()) {
            throw new ConflictException("本次作答已交卷");
        }
        List<CourseQuestionItem> questionItems = listItems(questionId);
        Instant now = Instant.now();
        if (attempt.getDeadlineAt() != null && now.isAfter(attempt.getDeadlineAt().plusSeconds(SUBMIT_GRACE_SECONDS))) {
            return settleOverdue(question, questionItems, attempt, now);
        }
        List<ItemAnswer> given = answers == null ? List.of() : answers;
        requireAnswerShapes(questionItems, given);
        return settle(question, questionItems, attempt, given, now);
    }

    /**
     * 作答体按题型校验(作答原样落库并回显给学生与教师,不能收任意内容):
     * 题目必须属于本试题;单选是选项文本(与选项同限)、判断是布尔、填空是不超过 200 字符的文本;空视为未作答。
     */
    private static void requireAnswerShapes(List<CourseQuestionItem> questionItems, List<ItemAnswer> answers) {
        Map<Long, CourseQuestionType> types = new HashMap<>();
        for (CourseQuestionItem item : questionItems) {
            types.put(item.getId(), item.getType());
        }
        for (ItemAnswer answer : answers) {
            CourseQuestionType type = types.get(answer.itemId());
            if (type == null) {
                throw new BadRequestException("作答的题目不属于本试题");
            }
            JsonNode value = answer.answer();
            if (value == null || value.isNull()) {
                continue;
            }
            switch (type) {
                case SINGLE_CHOICE -> {
                    if (!value.isTextual() || value.textValue().length() > CourseQuestionContract.MAX_OPTION_CHARS) {
                        throw new BadRequestException("单选作答必须是一个选项");
                    }
                }
                case TRUE_FALSE -> {
                    if (!value.isBoolean()) {
                        throw new BadRequestException("判断作答必须是对或错");
                    }
                }
                case FILL_IN_BLANK -> {
                    if (!value.isTextual()) {
                        throw new BadRequestException("填空作答必须是文本");
                    }
                    if (value.textValue().length() > CourseQuestionContract.MAX_BLANK_ANSWER_CHARS) {
                        throw new BadRequestException("填空作答不能超过 "
                                + CourseQuestionContract.MAX_BLANK_ANSWER_CHARS + " 字符");
                    }
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public AttemptResult getAttempt(long courseId, long questionId, long attemptId) {
        long accountId = currentActor.require().userId();
        CourseQuestion question = requireLinkedForLearning(courseId, questionId);
        CourseQuestionAttempt attempt = requireOwnAttempt(questionId, attemptId, accountId, false);
        if (!attempt.submitted()) {
            throw new ConflictException("本次作答尚未交卷");
        }
        return resultView(question, listItems(questionId), attempt);
    }

    @Transactional(readOnly = true)
    public List<AttemptResult> myAttempts(long courseId, long questionId) {
        long accountId = currentActor.require().userId();
        CourseQuestion question = requireLinkedForLearning(courseId, questionId);
        List<CourseQuestionItem> questionItems = listItems(questionId);
        return attemptsOf(questionId, accountId).stream()
                .filter(CourseQuestionAttempt::submitted)
                .sorted((a, b) -> b.getSubmittedAt().compareTo(a.getSubmittedAt()))
                .map(attempt -> resultView(question, questionItems, attempt))
                .toList();
    }

    // ---- 判分 ----

    static boolean judge(CourseQuestionType type, JsonNode expected, JsonNode given) {
        if (given == null || given.isNull() || given.isMissingNode()) {
            return false;
        }
        return switch (type) {
            case SINGLE_CHOICE -> given.isTextual() && expected.asText().equals(given.asText());
            case FILL_IN_BLANK -> given.isTextual() && !given.textValue().isBlank()
                    && normalizeBlank(expected.textValue()).equals(normalizeBlank(given.textValue()));
            case TRUE_FALSE -> given.isBoolean() && expected.asBoolean() == given.asBoolean();
        };
    }

    /** 填空比对:去首尾空白、内部空白折叠为一个、忽略大小写;不做模糊匹配 */
    static String normalizeBlank(String text) {
        return text.strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private AttemptResult settleOverdue(CourseQuestion question, List<CourseQuestionItem> questionItems,
                                        CourseQuestionAttempt attempt, Instant now) {
        return settle(question, questionItems, attempt, List.of(), now, true);
    }

    private AttemptResult settle(CourseQuestion question, List<CourseQuestionItem> questionItems,
                                 CourseQuestionAttempt attempt, List<ItemAnswer> answers, Instant now) {
        return settle(question, questionItems, attempt, answers, now, false);
    }

    private AttemptResult settle(CourseQuestion question, List<CourseQuestionItem> questionItems,
                                 CourseQuestionAttempt attempt, List<ItemAnswer> answers, Instant now,
                                 boolean overdue) {
        Map<Long, JsonNode> given = new HashMap<>();
        for (ItemAnswer answer : answers) {
            given.put(answer.itemId(), answer.answer());
        }
        ArrayNode answersJson = objectMapper.createArrayNode();
        ArrayNode resultsJson = objectMapper.createArrayNode();
        double total = 0;
        for (CourseQuestionItem item : questionItems) {
            JsonNode value = given.get(item.getId());
            boolean correct = judge(item.getType(), readJson(item.getAnswerJson()), value);
            double score = correct ? item.getScore() : 0;
            total += score;
            ObjectNode answerNode = answersJson.addObject();
            answerNode.put("itemId", item.getId());
            answerNode.set("answer", value == null ? objectMapper.nullNode() : value);
            ObjectNode resultNode = resultsJson.addObject();
            resultNode.put("itemId", item.getId());
            resultNode.put("correct", correct);
            resultNode.put("score", score);
        }
        attempt.submit(now, total, writeJson(answersJson), writeJson(resultsJson), overdue);
        requireMutation(attempts.updateById(attempt), "作答状态已变化，交卷未生效");
        learningEvents.record(new LearningEvent(question.getCourseId(), attempt.getAccountId(),
                LearningEventType.QUESTION_ATTEMPTED, question.getId(),
                Map.of("score", total, "totalScore", totalScore(questionItems))));
        return resultView(question, questionItems, attempt);
    }

    private void settleExpired(CourseQuestion question, List<CourseQuestionItem> questionItems, long accountId,
                               Instant now) {
        for (CourseQuestionAttempt attempt : attemptsOf(question.getId(), accountId)) {
            if (!attempt.submitted() && attempt.getDeadlineAt() != null
                    && now.isAfter(attempt.getDeadlineAt().plusSeconds(SUBMIT_GRACE_SECONDS))) {
                // 拿到行锁后重查:并发的另一次结算可能已经先完成,重复结算会重复记学情事件
                CourseQuestionAttempt locked = attempts.selectForUpdate(attempt.getId());
                if (locked != null && !locked.submitted()) {
                    settleOverdue(question, questionItems, locked, now);
                }
            }
        }
    }

    private AttemptResult resultView(CourseQuestion question, List<CourseQuestionItem> questionItems,
                                     CourseQuestionAttempt attempt) {
        return resultView(question, questionItems, attempt, question.getRevealAnswers());
    }

    /** reveal:是否附带标准答案与解析——学生看自己的按试题设置,教师看学生的总是附带 */
    private AttemptResult resultView(CourseQuestion question, List<CourseQuestionItem> questionItems,
                                     CourseQuestionAttempt attempt, boolean reveal) {
        Map<Long, JsonNode> given = new HashMap<>();
        for (JsonNode node : readJson(attempt.getAnswersJson())) {
            given.put(node.path("itemId").asLong(), node.path("answer"));
        }
        Map<Long, JsonNode> results = new HashMap<>();
        for (JsonNode node : readJson(attempt.getResultsJson())) {
            results.put(node.path("itemId").asLong(), node);
        }
        List<ItemResultView> views = new ArrayList<>();
        for (CourseQuestionItem item : questionItems) {
            JsonNode result = results.get(item.getId());
            JsonNode value = given.get(item.getId());
            views.add(new ItemResultView(item.getId(), item.getPosition(),
                    result != null && result.path("correct").asBoolean(false),
                    result == null ? 0 : result.path("score").asDouble(0), item.getScore(),
                    value == null || value.isNull() ? null : value,
                    reveal ? readJson(item.getAnswerJson()) : null,
                    reveal ? item.getAnalysisMarkdown() : null));
        }
        return new AttemptResult(attempt.getId(), question.getId(), attempt.getScore(), totalScore(questionItems),
                attempt.getSubmittedAt(), reveal, attempt.getOverdue(), views);
    }

    // ---- 内部 ----

    private record EncodedItem(CourseQuestionType type, String stemMarkdown, String optionsJson, String answerJson,
                               String analysisMarkdown, double score) {
    }

    private record Summary(int itemCount, double totalScore) {
    }

    private List<EncodedItem> encode(SaveQuestion command) {
        if (command.items() == null || command.items().isEmpty()) {
            throw new BadRequestException("试题至少要有一道题");
        }
        if (command.items().size() > MAX_ITEMS) {
            throw new BadRequestException("试题最多包含 " + MAX_ITEMS + " 道题");
        }
        if (command.timeLimitMinutes() != null
                && (command.timeLimitMinutes() < 1 || command.timeLimitMinutes() > MAX_TIME_LIMIT_MINUTES)) {
            throw new BadRequestException("限时须在 1 到 " + MAX_TIME_LIMIT_MINUTES + " 分钟之间");
        }
        List<EncodedItem> encoded = new ArrayList<>();
        int position = 0;
        for (SaveItem item : command.items()) {
            position++;
            if (item.score() <= 0 || item.score() > MAX_ITEM_SCORE || Math.round(item.score() * 10) != item.score() * 10) {
                throw new BadRequestException("第 " + position + " 题分值须为 0.1 到 " + (int) MAX_ITEM_SCORE + " 之间、最多一位小数");
            }
            CourseQuestionContract.validateStem(item.type(), item.stemMarkdown());
            CourseQuestionContract.EncodedPayload payload = contract.encodeDefinition(item.type(), item.options(), item.answer());
            encoded.add(new EncodedItem(item.type(), item.stemMarkdown(), payload.optionsJson(), payload.answerJson(),
                    item.analysisMarkdown(), item.score()));
        }
        return encoded;
    }

    private static CourseQuestion.Settings settings(SaveQuestion command) {
        return new CourseQuestion.Settings(command.timeLimitMinutes(), command.allowRetake(), command.revealAnswers());
    }

    private void replaceItems(long questionId, List<EncodedItem> encoded) {
        items.delete(new LambdaQueryWrapper<CourseQuestionItem>().eq(CourseQuestionItem::getQuestionId, questionId));
        int position = 0;
        for (EncodedItem item : encoded) {
            items.insert(new CourseQuestionItem(questionId, ++position, item.type(), item.stemMarkdown(),
                    item.optionsJson(), item.answerJson(), item.analysisMarkdown(), item.score()));
        }
    }

    List<CourseQuestionItem> listItems(long questionId) {
        return items.selectList(new LambdaQueryWrapper<CourseQuestionItem>()
                .eq(CourseQuestionItem::getQuestionId, questionId)
                .orderByAsc(CourseQuestionItem::getPosition));
    }

    private List<CourseQuestionAttempt> attemptsOf(long questionId, long accountId) {
        return attempts.selectList(new LambdaQueryWrapper<CourseQuestionAttempt>()
                .eq(CourseQuestionAttempt::getQuestionId, questionId)
                .eq(CourseQuestionAttempt::getAccountId, accountId)
                .orderByAsc(CourseQuestionAttempt::getStartedAt));
    }

    private Map<Long, Summary> summaries(List<Long> questionIds) {
        Map<Long, Summary> result = new HashMap<>();
        if (questionIds.isEmpty()) {
            return result;
        }
        for (Map<String, Object> row : items.summarize(questionIds)) {
            result.put(((Number) row.get("questionId")).longValue(), new Summary(
                    ((Number) row.get("itemCount")).intValue(), ((Number) row.get("totalScore")).doubleValue()));
        }
        return result;
    }

    private static double totalScore(List<CourseQuestionItem> questionItems) {
        double total = 0;
        for (CourseQuestionItem item : questionItems) {
            total += item.getScore();
        }
        return total;
    }

    private CourseQuestion require(long courseId, long questionId) {
        CourseQuestion question = questions.selectOne(
                new LambdaQueryWrapper<CourseQuestion>()
                        .eq(CourseQuestion::getCourseId, courseId)
                        .eq(CourseQuestion::getId, questionId));
        if (question == null) {
            throw new NotFoundException("课程中不存在该试题");
        }
        return question;
    }

    private CourseQuestion requireLinkedForLearning(long courseId, long questionId) {
        return requireLinkedForLearning(courseId, questionId, false);
    }

    private CourseQuestion requireLinkedForLearning(long courseId, long questionId, boolean forUpdate) {
        courseAccess.requireLearningAccess(courseId, currentActor.require());
        if (!courseOutlineLinks.isLinked(courseId, CourseOutlineItemType.QUESTION, questionId)) {
            throw new NotFoundException("课程内容中不存在该试题");
        }
        return forUpdate ? requireForUpdate(courseId, questionId) : require(courseId, questionId);
    }

    private CourseQuestion requireForUpdate(long courseId, long questionId) {
        CourseQuestion question = questions.selectForUpdate(courseId, questionId);
        if (question == null) {
            throw new NotFoundException("课程中不存在该试题");
        }
        return question;
    }

    /** forUpdate:交卷要锁作答行(并发两次交卷只能成功一次);只读查看在只读事务里不能加锁 */
    private CourseQuestionAttempt requireOwnAttempt(long questionId, long attemptId, long accountId, boolean forUpdate) {
        CourseQuestionAttempt attempt = forUpdate ? attempts.selectForUpdate(attemptId) : attempts.selectById(attemptId);
        if (attempt == null || attempt.getQuestionId() != questionId || attempt.getAccountId() != accountId) {
            throw new NotFoundException("作答记录不存在");
        }
        return attempt;
    }

    private static void requireMutation(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new ConflictException(message);
        }
    }

    private CourseQuestionView view(CourseQuestion question, Summary summary) {
        return new CourseQuestionView(question.getId(), question.getCourseId(), question.getTitle(),
                question.getTimeLimitMinutes(), question.getAllowRetake(), question.getRevealAnswers(),
                summary == null ? 0 : summary.itemCount(), summary == null ? 0 : summary.totalScore(),
                question.getCreatedAt(), question.getUpdatedAt());
    }

    private CourseQuestionDetail detail(CourseQuestion question, List<CourseQuestionItem> questionItems) {
        return new CourseQuestionDetail(question.getId(), question.getCourseId(), question.getTitle(),
                question.getTimeLimitMinutes(), question.getAllowRetake(), question.getRevealAnswers(),
                totalScore(questionItems), questionItems.stream().map(this::itemView).toList(),
                hasAttempt(question.getId()), question.getCreatedAt(), question.getUpdatedAt());
    }

    private ItemView itemView(CourseQuestionItem item) {
        return new ItemView(item.getId(), item.getPosition(), item.getType(), item.getStemMarkdown(),
                readNullableJson(item.getOptionsJson()), readJson(item.getAnswerJson()), item.getAnalysisMarkdown(),
                item.getScore());
    }

    private LearningItemView learningItem(CourseQuestionItem item) {
        return new LearningItemView(item.getId(), item.getPosition(), item.getType(), item.getStemMarkdown(),
                readNullableJson(item.getOptionsJson()), item.getScore());
    }

    private JsonNode readNullableJson(String json) {
        return json == null ? null : readJson(json);
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("试题数据不是有效 JSON", exception);
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

}
