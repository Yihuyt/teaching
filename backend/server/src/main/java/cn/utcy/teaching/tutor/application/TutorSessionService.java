package cn.utcy.teaching.tutor.application;

import cn.utcy.teaching.shared.util.Text;
import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.shared.web.PageResponse;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.identity.application.AccountDirectory;
import cn.utcy.teaching.tutor.infrastructure.TutorAssistantEntity;
import cn.utcy.teaching.tutor.infrastructure.TutorMessageEntity;
import cn.utcy.teaching.tutor.infrastructure.TutorMessageMapper;
import cn.utcy.teaching.tutor.infrastructure.TutorRowPurger;
import cn.utcy.teaching.tutor.infrastructure.TutorSessionEntity;
import cn.utcy.teaching.tutor.infrastructure.TutorSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TutorSessionService {

    private final TutorSessionMapper sessions;
    private final TutorMessageMapper messages;
    private final TutorAssistantService assistants;
    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;
    private final AccountDirectory accounts;
    private final ObjectMapper objectMapper;
    private final TutorRowPurger purger;

    public TutorSessionService(TutorSessionMapper sessions, TutorMessageMapper messages, TutorAssistantService assistants,
                               CourseAccess courseAccess, CurrentActor currentActor, AccountDirectory accounts,
                               ObjectMapper objectMapper, TutorRowPurger purger) {
        this.sessions = sessions;
        this.purger = purger;
        this.messages = messages;
        this.assistants = assistants;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
        this.accounts = accounts;
        this.objectMapper = objectMapper;
    }

    public record TutorSessionView(long id, long assistantId, String title, Instant createdAt, Instant updatedAt) {
    }

    public record TutorMessageView(long id, String role, String content, JsonNode sources,
                              @Schema(nullable = true) JsonNode trace, Instant createdAt) {
    }

    public record TutorAdminSessionView(long id, long accountId, String accountName, long assistantId,
                                        String assistantName, String title, long messageCount,
                                        Instant createdAt, Instant updatedAt) {
    }

    public record TutorTranscriptView(TutorAdminSessionView session, List<TutorMessageView> messages) {
    }

    @Transactional(readOnly = true)
    public List<TutorSessionView> list(long courseId, long assistantId) {
        Actor actor = currentActor.require();
        TutorAssistantEntity assistant = assistants.requireUsable(courseId, assistantId, actor);
        return sessions.selectList(new LambdaQueryWrapper<TutorSessionEntity>()
                        .eq(TutorSessionEntity::getAssistantId, assistant.getId())
                        .eq(TutorSessionEntity::getAccountId, actor.userId())
                        .orderByDesc(TutorSessionEntity::getUpdatedAt))
                .stream().map(TutorSessionService::view).toList();
    }

    @Transactional
    public TutorSessionView create(long courseId, long assistantId) {
        Actor actor = currentActor.require();
        TutorAssistantEntity assistant = assistants.requireUsable(courseId, assistantId, actor);
        TutorSessionEntity session = new TutorSessionEntity(courseId, assistant.getId(), actor.userId(), now());
        sessions.insert(session);
        return view(session);
    }

    @Transactional
    public void delete(long courseId, long sessionId) {
        TutorSessionEntity session = requireOwn(courseId, sessionId, currentActor.require());
        // 锁会话行:正在追加消息的对话在同一事务内读它,删除后不再有消息落地
        if (sessions.selectForUpdate(session.getId()) == null) {
            throw new NotFoundException("会话不存在");
        }
        purger.purgeSessions(List.of(session.getId()));
    }

    @Transactional(readOnly = true)
    public List<TutorMessageView> messages(long courseId, long sessionId) {
        requireOwn(courseId, sessionId, currentActor.require());
        return listMessages(sessionId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<TutorAdminSessionView> listForCourse(long courseId, Long assistantId, int page, int size) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        Page<TutorSessionEntity> result = sessions.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<TutorSessionEntity>()
                        .eq(TutorSessionEntity::getCourseId, courseId)
                        .eq(assistantId != null, TutorSessionEntity::getAssistantId, assistantId)
                        .orderByDesc(TutorSessionEntity::getUpdatedAt));
        Map<Long, Long> counts = new HashMap<>();
        List<Long> ids = result.getRecords().stream().map(TutorSessionEntity::getId).toList();
        if (!ids.isEmpty()) {
            for (Map<String, Object> row : messages.countBySession(ids)) {
                counts.put(((Number) row.get("sessionId")).longValue(), ((Number) row.get("total")).longValue());
            }
        }
        return PageResponse.of(result.getRecords().stream()
                .map(session -> adminView(session, counts.getOrDefault(session.getId(), 0L))).toList(),
                result.getTotal(), page, size);
    }

    @Transactional(readOnly = true)
    public TutorTranscriptView transcript(long courseId, long sessionId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        TutorSessionEntity session = requireInCourse(courseId, sessionId);
        List<TutorMessageEntity> rows = listMessages(sessionId);
        return new TutorTranscriptView(adminView(session, rows.size()), rows.stream().map(this::view).toList());
    }

    // ---- 模块内共用(鉴权由调用方完成) ----

    /** 本人的会话,且其助手对当前用户仍可用(学生:助手被关闭后旧会话不可再用) */
    TutorSessionEntity requireOwn(long courseId, long sessionId, Actor actor) {
        TutorSessionEntity session = requireInCourse(courseId, sessionId);
        if (session.getAccountId() != actor.userId()) {
            throw new NotFoundException("会话不存在");
        }
        assistants.requireUsable(courseId, session.getAssistantId(), actor);
        return session;
    }

    TutorSessionEntity requireInCourse(long courseId, long sessionId) {
        TutorSessionEntity session = sessions.selectById(sessionId);
        if (session == null || session.getCourseId() != courseId) {
            throw new NotFoundException("会话不存在");
        }
        return session;
    }

    List<TutorMessageEntity> listMessages(long sessionId) {
        return messages.selectList(new LambdaQueryWrapper<TutorMessageEntity>()
                .eq(TutorMessageEntity::getSessionId, sessionId)
                .orderByAsc(TutorMessageEntity::getId));
    }

    @Transactional
    public TutorMessageEntity append(long sessionId, String role, String content, String sourcesJson,
                                     String traceJson) {
        // 先锁会话行再写消息:会话已被删除(对话进行中被删)时不留孤儿消息
        TutorSessionEntity session = sessions.selectForUpdate(sessionId);
        if (session == null) {
            throw new NotFoundException("会话不存在");
        }
        TutorMessageEntity message = new TutorMessageEntity(sessionId, role, content, sourcesJson, traceJson, now());
        messages.insert(message);
        session.setUpdatedAt(now());
        sessions.updateById(session);
        return message;
    }

    @Transactional
    public void updateSummary(long sessionId, String summary, long upToMsgId) {
        TutorSessionEntity session = sessions.selectForUpdate(sessionId);
        if (session != null) {
            session.updateSummary(summary, upToMsgId);
            sessions.updateById(session);
        }
    }

    @Transactional
    public void updateTitle(long sessionId, String title) {
        TutorSessionEntity session = sessions.selectForUpdate(sessionId);
        if (session != null) {
            session.setTitle(Text.truncate(title, 100));
            sessions.updateById(session);
        }
    }

    private TutorAdminSessionView adminView(TutorSessionEntity session, long messageCount) {
        String name = accounts.require(session.getAccountId()).name();
        String assistantName = assistants.requireInCourse(session.getCourseId(), session.getAssistantId()).getName();
        return new TutorAdminSessionView(session.getId(), session.getAccountId(), name, session.getAssistantId(),
                assistantName, session.getTitle(), messageCount,
                toInstant(session.getCreatedAt()), toInstant(session.getUpdatedAt()));
    }

    private TutorMessageView view(TutorMessageEntity message) {
        return new TutorMessageView(message.getId(), message.getRole(), message.getContent(),
                readJson(message.getSourcesJson(), objectMapper.createArrayNode()),
                message.getTraceJson() == null ? null : readJson(message.getTraceJson(), null),
                toInstant(message.getCreatedAt()));
    }

    private JsonNode readJson(String json, JsonNode fallback) {
        try {
            return json == null || json.isBlank() ? fallback : objectMapper.readTree(json);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("会话消息 JSON 损坏", e);
        }
    }

    static TutorSessionView view(TutorSessionEntity session) {
        return new TutorSessionView(session.getId(), session.getAssistantId(), session.getTitle(),
                toInstant(session.getCreatedAt()), toInstant(session.getUpdatedAt()));
    }

    static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    static Instant toInstant(LocalDateTime time) {
        return time.toInstant(ZoneOffset.UTC);
    }
}
