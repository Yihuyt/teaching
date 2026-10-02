package cn.utcy.teaching.blockcoding.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.blockcoding.application.ChatViews.MessageChanges;
import cn.utcy.teaching.blockcoding.application.ChatViews.MessageView;
import cn.utcy.teaching.blockcoding.application.ChatViews.SessionView;
import cn.utcy.teaching.blockcoding.domain.AssistantMode;
import cn.utcy.teaching.blockcoding.domain.BlockCodingChatMessage;
import cn.utcy.teaching.blockcoding.domain.BlockCodingChatSession;
import cn.utcy.teaching.blockcoding.infrastructure.BlockCodingChatMessageMapper;
import cn.utcy.teaching.blockcoding.infrastructure.BlockCodingChatSessionMapper;
import cn.utcy.teaching.course.application.CourseAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ChatSessions {
    private final BlockCodingChatSessionMapper sessions;
    private final BlockCodingChatMessageMapper messages;
    private final BlockCodingProjectService projects;
    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;
    private final BlockCodingChatPurger purger;
    private final MessageRecords records;

    public ChatSessions(BlockCodingChatSessionMapper sessions, BlockCodingChatMessageMapper messages,
                        BlockCodingProjectService projects, CourseAccess courseAccess, CurrentActor currentActor,
                        BlockCodingChatPurger purger, MessageRecords records) {
        this.sessions = sessions;
        this.messages = messages;
        this.projects = projects;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
        this.purger = purger;
        this.records = records;
    }

    public SessionView open(long courseId, long projectId) {
        Actor actor = currentActor.require();
        projects.requireOwned(courseId, projectId);
        List<BlockCodingChatSession> existing = sessions.selectByProject(projectId, actor.userId());
        if (!existing.isEmpty()) {
            BlockCodingChatSession session = existing.getFirst();
            return SessionView.of(session, AssistantMode.allowedFor(courseAccess.canManage(courseId, actor)));
        }
        BlockCodingChatSession session = BlockCodingChatSession.create(projectId, actor.userId());
        sessions.insert(session);
        return SessionView.of(session, AssistantMode.allowedFor(courseAccess.canManage(courseId, actor)));
    }

    public long courseOf(BlockCodingChatSession session) {
        return projects.courseOf(session.getProjectId());
    }

    public List<AssistantMode> allowedModes(BlockCodingChatSession session, Actor actor) {
        return AssistantMode.allowedFor(courseAccess.canManage(courseOf(session), actor));
    }

    @Transactional
    public void delete(long sessionId) {
        BlockCodingChatSession session = sessions.selectOwnedForUpdate(sessionId, currentActor.require().userId());
        if (session == null) {
            throw new NotFoundException("会话不存在");
        }
        purger.purgeSessions(List.of(session.getId()));
    }

    public List<MessageView> messages(long sessionId) {
        requireOwned(sessionId);
        return messages.selectBySession(sessionId).stream().map(records::view).toList();
    }

    /** 用户回退了这条助手消息的全部改动(编辑器里的恢复由前端按 previous 执行):记下来,历史里如实说 */
    @Transactional
    public MessageView markReverted(long sessionId, long messageId) {
        requireOwned(sessionId);
        BlockCodingChatMessage message = messages.selectById(messageId);
        if (message == null || message.getSessionId() != sessionId || !"assistant".equals(message.getRole())) {
            throw new NotFoundException("消息不存在");
        }
        MessageChanges scripts = records.read(message);
        if (!scripts.touchesProject()) {
            throw new BadRequestException("这条消息没有改过作品");
        }
        if (scripts.reverted()) {
            throw new BadRequestException("这轮改动已经回退过了");
        }
        message.setScriptsJson(records.write(scripts.asReverted()));
        messages.updateById(message);
        return records.view(message);
    }

    BlockCodingChatSession requireOwned(long sessionId) {
        BlockCodingChatSession session = sessions.selectOwned(sessionId, currentActor.require().userId());
        if (session == null) {
            throw new NotFoundException("会话不存在");
        }
        return session;
    }
}
