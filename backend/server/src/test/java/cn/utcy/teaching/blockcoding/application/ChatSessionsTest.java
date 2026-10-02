package cn.utcy.teaching.blockcoding.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.blockcoding.application.ChatViews.MessageChanges;
import cn.utcy.teaching.blockcoding.application.ChatViews.MessageView;
import cn.utcy.teaching.blockcoding.application.ChatViews.ScriptView;
import cn.utcy.teaching.blockcoding.application.ChatViews.SessionView;
import cn.utcy.teaching.blockcoding.domain.BlockCodingChatMessage;
import cn.utcy.teaching.blockcoding.domain.BlockCodingChatSession;
import cn.utcy.teaching.blockcoding.domain.BlockCodingProject;
import cn.utcy.teaching.blockcoding.infrastructure.BlockCodingChatMessageMapper;
import cn.utcy.teaching.blockcoding.infrastructure.BlockCodingChatSessionMapper;
import cn.utcy.teaching.course.application.CourseAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSessionsTest {
    private final Actor teacher = new Actor(7L, "t", SystemRole.TEACHER);
    private final BlockCodingChatSessionMapper sessions = mock(BlockCodingChatSessionMapper.class);
    private final BlockCodingChatMessageMapper messages = mock(BlockCodingChatMessageMapper.class);
    private final BlockCodingProjectService projects = mock(BlockCodingProjectService.class);
    private final CourseAccess courseAccess = mock(CourseAccess.class);
    private final CurrentActor currentActor = mock(CurrentActor.class);
    private final BlockCodingChatPurger purger = mock(BlockCodingChatPurger.class);
    private final MessageRecords records = new MessageRecords(new ObjectMapper());
    private final ChatSessions chatSessions = new ChatSessions(sessions, messages, projects, courseAccess,
            currentActor, purger, records);

    @BeforeEach
    void actor() {
        when(currentActor.require()).thenReturn(teacher);
        // 数据库会给 id;这里插入时补一个
        when(sessions.insert(any(BlockCodingChatSession.class))).thenAnswer(call -> {
            BlockCodingChatSession created = call.getArgument(0);
            ReflectionTestUtils.setField(created, "id", 100L);
            return 1;
        });
    }

    private static <T> T withId(T entity, long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    @Test
    @DisplayName("第一次打开作品的对话就建一段:课程管理者能用修改 / 讲解,其他成员只有讲解")
    void openingCreatesSessionAndDecidesModesByManagement() {
        when(projects.requireOwned(6L, 1L)).thenReturn(withId(BlockCodingProject.create(6L, 7L, "作品"), 1L));
        when(sessions.selectByProject(1L, 7L)).thenReturn(List.of());
        when(courseAccess.canManage(6L, teacher)).thenReturn(true);

        SessionView manager = chatSessions.open(6L, 1L);
        assertThat(manager.id()).isEqualTo(100L);
        assertThat(manager.projectId()).isEqualTo(1L);
        assertThat(manager.modes()).containsExactly("agent", "chat");

        when(courseAccess.canManage(6L, teacher)).thenReturn(false);
        assertThat(chatSessions.open(6L, 1L).modes()).containsExactly("chat");
    }

    @Test
    @DisplayName("作品准入先于会话:不是本人在这门课里的作品就 404,不建会话")
    void openingRequiresOwnedProjectInCourse() {
        when(projects.requireOwned(6L, 1L)).thenThrow(new NotFoundException("作品不存在"));

        assertThatThrownBy(() -> chatSessions.open(6L, 1L)).isInstanceOf(NotFoundException.class);

        verify(sessions, never()).insert(any(BlockCodingChatSession.class));
    }

    @Test
    @DisplayName("已有对话复用:作品所属课程决定本次能用的模式")
    void reopeningReusesSessionAndRecomputesModes() {
        when(projects.requireOwned(6L, 1L)).thenReturn(withId(BlockCodingProject.create(6L, 7L, "作品"), 1L));
        BlockCodingChatSession existing = withId(BlockCodingChatSession.create(1L, 7L), 5L);
        when(sessions.selectByProject(1L, 7L)).thenReturn(List.of(existing));
        when(projects.courseOf(1L)).thenReturn(6L);
        when(courseAccess.canManage(6L, teacher)).thenReturn(true);

        assertThat(chatSessions.open(6L, 1L).id()).isEqualTo(5L);
        assertThat(chatSessions.courseOf(existing)).isEqualTo(6L);
        assertThat(chatSessions.allowedModes(existing, teacher)).extracting(mode -> mode.key())
                .containsExactly("agent", "chat");
        verify(sessions, never()).insert(any(BlockCodingChatSession.class));
    }

    @Test
    @DisplayName("记回退:只有带改动的助手消息能回退,回退标记随记录存回去,视图与历史都能看到")
    void markRevertedFlagsTheRecord() {
        BlockCodingChatSession session = withId(BlockCodingChatSession.create(1L, 7L), 3L);
        when(sessions.selectOwned(3L, 7L)).thenReturn(session);
        BlockCodingChatMessage plain = withId(BlockCodingChatMessage.of(3L, 2, "assistant", "只是说说", null), 20L);
        when(messages.selectById(20L)).thenReturn(plain);
        assertThatThrownBy(() -> chatSessions.markReverted(3L, 20L)).isInstanceOf(BadRequestException.class);

        ScriptView written = new ScriptView(1, "Cat", "when green flag clicked\nmove (10) steps", 2, "written", null, "ins-1", null);
        BlockCodingChatMessage changed = withId(BlockCodingChatMessage.of(3L, 4, "assistant", "做好了",
                records.write(new MessageChanges(List.of(written), List.of(), List.of(), null, false))), 21L);
        when(messages.selectById(21L)).thenReturn(changed);
        MessageView view = chatSessions.markReverted(3L, 21L);
        assertThat(view.reverted()).isTrue();
        assertThat(view.scripts()).singleElement().extracting(ScriptView::blockId).isEqualTo("ins-1");
        ArgumentCaptor<BlockCodingChatMessage> saved = ArgumentCaptor.forClass(BlockCodingChatMessage.class);
        verify(messages).updateById(saved.capture());
        assertThat(records.read(saved.getValue()).reverted()).isTrue();
        assertThat(ChatHistory.of(records, List.of(saved.getValue())).getFirst().toString()).contains("用户随后把这些改动全部回退了");
        // 回退过的不能再回退:前端会把原样再插一遍,作品里就多出一段
        when(messages.selectById(21L)).thenReturn(saved.getValue());
        assertThatThrownBy(() -> chatSessions.markReverted(3L, 21L)).isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("消息记录:没有积木、没有提问、没回退的消息不存记录;存了的原样读回")
    void recordsRoundTrip() {
        assertThat(records.write(new MessageChanges(List.of(), List.of(), List.of(), null, false))).isNull();
        assertThat(records.read(withId(BlockCodingChatMessage.of(3L, 1, "user", "你好", null), 1L))).isEqualTo(MessageChanges.NONE);
        ScriptView quoted = new ScriptView(1, "Cat", "when green flag clicked", 1, "quoted", "积木1", null, null);
        String json = records.write(new MessageChanges(List.of(quoted), List.of(), List.of(), null, false));
        assertThat(records.read(withId(BlockCodingChatMessage.of(3L, 1, "user", "看【积木1】", json), 2L)).scripts()).containsExactly(quoted);
        verify(purger, never()).purgeSessions(any());
    }
}
