package cn.utcy.teaching.blockcoding.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.ai.llm.LlmModels;
import cn.utcy.teaching.blockcoding.infrastructure.BlockCodingProperties;
import cn.utcy.teaching.blockcoding.application.agent.ScratchProgramAgent;
import cn.utcy.teaching.blockcoding.domain.AssistantMode;
import cn.utcy.teaching.blockcoding.domain.BlockCodingChatSession;
import cn.utcy.teaching.blockcoding.engine.SbToText;
import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.blockcoding.infrastructure.BlockCodingChatMessageMapper;
import cn.utcy.teaching.blockcoding.infrastructure.BlockCodingChatSessionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatTurnTest {
    private final Actor actor = new Actor(7L, "t", SystemRole.TEACHER);
    private final ChatSessions chatSessions = mock(ChatSessions.class);
    private final BlockCodingChatSessionMapper sessions = mock(BlockCodingChatSessionMapper.class);
    private final BlockCodingChatMessageMapper messages = mock(BlockCodingChatMessageMapper.class);
    private final ScratchProgramAgent agent = mock(ScratchProgramAgent.class);
    private final CurrentActor currentActor = mock(CurrentActor.class);
    private final CourseAiKeys aiKeys = mock(CourseAiKeys.class);
    private final CourseBlockCodingConfigService courseConfig = mock(CourseBlockCodingConfigService.class);
    /** 攒起来不跑:模拟"上一轮还在跑" */
    private final List<Runnable> queued = new ArrayList<>();
    private final TaskExecutor executor = queued::add;
    private final ChatTurn turn = new ChatTurn(chatSessions, sessions, messages, new MessageRecords(new ObjectMapper()),
            courseConfig, agent, mock(SbToText.class), mock(LlmModels.class),
            new BlockCodingProperties(0.3, 0.9, 4096, 120000), new ObjectMapper(), executor,
            currentActor, aiKeys, new BrowserToolCalls());

    @Test
    @DisplayName("上一轮没结束就再发:拒绝且不留用户消息;上一轮跑完(这里是模型报错)后同一会话能再发")
    void oneTurnAtATimePerSession() {
        BlockCodingChatSession session = BlockCodingChatSession.create(1L, 7L);
        ReflectionTestUtils.setField(session, "id", 3L);
        when(currentActor.require()).thenReturn(actor);
        when(chatSessions.requireOwned(3L)).thenReturn(session);
        when(chatSessions.allowedModes(session, actor)).thenReturn(AssistantMode.allowedFor(false));
        when(chatSessions.courseOf(session)).thenReturn(6L);
        when(aiKeys.llmKeyForCourse(6L)).thenReturn("key");
        when(courseConfig.modelForCourse(6L)).thenReturn("qwen-plus");
        when(messages.selectBySession(3L)).thenReturn(List.of());
        when(agent.generate(any(), any(), any(), anyList(), anyString(), any(), anyList(), any(), any()))
                .thenThrow(new IllegalStateException("模型挂了"));

        turn.send(3L, "第一条", null, List.of(), null);
        assertThat(queued).hasSize(1);
        assertThatThrownBy(() -> turn.send(3L, "第二条", null, List.of(), null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("还在处理中");
        assertThat(queued).hasSize(1);

        queued.getFirst().run();
        turn.send(3L, "第三条", null, List.of(), null);
        assertThat(queued).hasSize(2);
    }
}
