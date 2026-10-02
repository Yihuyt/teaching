package cn.utcy.teaching.blockcoding.application;

import cn.utcy.teaching.blockcoding.domain.BlockCodingChatMessage;
import cn.utcy.teaching.blockcoding.domain.BlockCodingChatSession;
import cn.utcy.teaching.blockcoding.infrastructure.BlockCodingChatMessageMapper;
import cn.utcy.teaching.blockcoding.infrastructure.BlockCodingChatSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 对话会话及其消息的显式删除(数据库不设外键);删会话 / 删工程共用,必须在业务事务内调用 */
@Component
public class BlockCodingChatPurger {

    private final BlockCodingChatSessionMapper sessions;
    private final BlockCodingChatMessageMapper messages;

    BlockCodingChatPurger(BlockCodingChatSessionMapper sessions, BlockCodingChatMessageMapper messages) {
        this.sessions = sessions;
        this.messages = messages;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void purgeSessions(List<Long> sessionIds) {
        if (sessionIds.isEmpty()) {
            return;
        }
        messages.delete(new LambdaQueryWrapper<BlockCodingChatMessage>()
                .in(BlockCodingChatMessage::getSessionId, sessionIds));
        sessions.delete(new LambdaQueryWrapper<BlockCodingChatSession>()
                .in(BlockCodingChatSession::getId, sessionIds));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void purgeSessionsOfProject(long projectId) {
        purgeSessions(sessions.selectList(new LambdaQueryWrapper<BlockCodingChatSession>()
                        .select(BlockCodingChatSession::getId)
                        .eq(BlockCodingChatSession::getProjectId, projectId))
                .stream()
                .map(BlockCodingChatSession::getId)
                .toList());
    }
}
