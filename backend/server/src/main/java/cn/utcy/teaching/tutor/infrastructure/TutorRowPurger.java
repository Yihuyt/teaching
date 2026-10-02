package cn.utcy.teaching.tutor.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 智能助教从属行的显式删除(数据库不设外键):会话 → 消息;助手 → 挂载、会话。
 * 助手 / 会话 / 课程删除共用,必须在业务事务内调用。
 */
@Component
public class TutorRowPurger {

    private final TutorAssistantMapper assistants;
    private final TutorSessionMapper sessions;
    private final TutorMessageMapper messages;

    TutorRowPurger(TutorAssistantMapper assistants, TutorSessionMapper sessions, TutorMessageMapper messages) {
        this.assistants = assistants;
        this.sessions = sessions;
        this.messages = messages;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void purgeSessions(List<Long> sessionIds) {
        if (sessionIds.isEmpty()) {
            return;
        }
        messages.delete(new LambdaQueryWrapper<TutorMessageEntity>()
                .in(TutorMessageEntity::getSessionId, sessionIds));
        sessions.delete(new LambdaQueryWrapper<TutorSessionEntity>()
                .in(TutorSessionEntity::getId, sessionIds));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void purgeAssistant(long assistantId) {
        purgeSessions(sessionIds(new LambdaQueryWrapper<TutorSessionEntity>()
                .eq(TutorSessionEntity::getAssistantId, assistantId)));
        assistants.clearKnowledgeBases(assistantId);
        assistants.deleteById(assistantId);
    }

    void purgeCourse(long courseId) {
        purgeSessions(sessionIds(new LambdaQueryWrapper<TutorSessionEntity>()
                .eq(TutorSessionEntity::getCourseId, courseId)));
        assistants.selectList(new LambdaQueryWrapper<TutorAssistantEntity>()
                        .select(TutorAssistantEntity::getId)
                        .eq(TutorAssistantEntity::getCourseId, courseId))
                .forEach(assistant -> purgeAssistant(assistant.getId()));
    }

    private List<Long> sessionIds(LambdaQueryWrapper<TutorSessionEntity> where) {
        return sessions.selectList(where.select(TutorSessionEntity::getId))
                .stream()
                .map(TutorSessionEntity::getId)
                .toList();
    }
}
