package cn.utcy.teaching.blockcoding.application;

import cn.utcy.teaching.blockcoding.application.agent.ChangeCommitter;
import cn.utcy.teaching.blockcoding.application.agent.Question;
import cn.utcy.teaching.blockcoding.application.agent.ScratchAgentPrompt;
import cn.utcy.teaching.blockcoding.application.agent.Script;
import cn.utcy.teaching.blockcoding.application.agent.ScriptChange;
import cn.utcy.teaching.blockcoding.application.agent.SpriteChange;
import cn.utcy.teaching.blockcoding.application.agent.VariableChange;
import cn.utcy.teaching.blockcoding.domain.AssistantMode;
import cn.utcy.teaching.blockcoding.domain.BlockCodingChatSession;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class ChatViews {
    private ChatViews() {
    }

    /** modes:当前用户在这段对话里能用的模式(课程管理者 agent / chat,其他人只有 chat),第一个是默认;每条消息发送时可在其中选 */
    public record SessionView(long id, long projectId, List<String> modes,
                              Instant createdAt, Instant updatedAt) {
        static SessionView of(BlockCodingChatSession session, List<AssistantMode> modes) {
            return new SessionView(session.getId(), session.getProjectId(),
                    modes.stream().map(AssistantMode::key).toList(), session.getCreatedAt(), session.getUpdatedAt());
        }
    }

    /**
     * 消息里的一段积木。助手消息:本轮对作品的一处改动,kind = written 新写 / replaced 改写作品里的一段 / deleted 删掉作品里的一段
     * (code 是被删的原文);blockId 是它现在在编辑器里的顶层积木 id(删除为 null),previous 是动手前作品里的那段(新写为 null),
     * 回退时前端据此删掉新的、插回旧的。用户消息:kind = quoted,正文里【label】标记处的那段积木,显示时标记的位置渲染成它。
     */
    public record ScriptView(int id, String sprite, String code, int blockCount, String kind,
                             @Schema(nullable = true) String label,
                             @Schema(nullable = true) String blockId, @Schema(nullable = true) PreviousView previous) {
        static ScriptView of(ChangeCommitter.Committed committed) {
            ScriptChange change = committed.change();
            Script script = change.script();
            return new ScriptView(script.id(), script.sprite(), script.code(), script.blockCount(),
                    change.kind().name().toLowerCase(), null, committed.blockId(),
                    change.previous() == null ? null : PreviousView.of(change.previous()));
        }

        static ScriptView quoted(ScratchAgentPrompt.Quoted quoted) {
            Script script = quoted.script();
            return new ScriptView(script.id(), script.sprite(), script.code(), script.blockCount(), "quoted",
                    quoted.label(), null, null);
        }
    }

    /** 动手前作品里的那段:回退时按 xml 原样插回这个角色 */
    public record PreviousView(String sprite, String blockId, String code, String xml) {
        static PreviousView of(Script script) {
            return new PreviousView(script.sprite(), script.blockId(), script.code(), script.xml());
        }
    }

    public record InsertView(String sprite, String xml, List<String> variables, List<String> lists,
                             List<String> localVariables, List<String> localLists, List<String> broadcasts,
                             List<String> definedProcedures) {
        static InsertView of(Script script) {
            return new InsertView(script.sprite(), script.xml(), script.variables(), script.lists(),
                    script.localVariables(), script.localLists(), script.broadcasts(), script.definedProcedures());
        }
    }

    public record QuestionView(String text, List<String> options) {
        static QuestionView of(Question question) {
            return question == null ? null : new QuestionView(question.text(), question.options());
        }
    }

    /** 本轮对角色的改动:created 新建 / deleted 删除;preexisting = 删的是作品里原有的角色(造型、声音回退恢复不了) */
    public record SpriteChangeView(String name, String kind, boolean preexisting) {
        static SpriteChangeView of(SpriteChange change) {
            return new SpriteChangeView(change.sprite().name(), change.kind().name().toLowerCase(), change.preexisting());
        }
    }

    /** 本轮对变量 / 列表的改动:created / deleted;sprite 为 null 是全局的 */
    public record VariableChangeView(String name, boolean list, @Schema(nullable = true) String sprite, String kind) {
        static VariableChangeView of(VariableChange change) {
            return new VariableChangeView(change.name(), change.list(), change.sprite(), change.kind().name().toLowerCase());
        }
    }

    /**
     * 一条消息随身带的记录:积木清单(用户消息是拖进来的积木,助手消息是本轮写进作品的脚本改动)、本轮建 / 删的角色与变量、
     * 待答的提问、改动是否已被用户回退;两种角色同一种形状,存在消息行的 scripts_json 上。老记录没有 sprites / variables 按空处理
     */
    public record MessageChanges(List<ScriptView> scripts, List<SpriteChangeView> sprites, List<VariableChangeView> variables,
                                 @Schema(nullable = true) QuestionView question, boolean reverted) {
        static final MessageChanges NONE = new MessageChanges(List.of(), List.of(), List.of(), null, false);

        public MessageChanges {
            scripts = scripts == null ? List.of() : scripts;
            sprites = sprites == null ? List.of() : sprites;
            variables = variables == null ? List.of() : variables;
        }

        static MessageChanges of(ChangeCommitter.CommitReport report, QuestionView question) {
            return new MessageChanges(report.scripts().stream().map(ScriptView::of).toList(),
                    report.sprites().stream().map(SpriteChangeView::of).toList(),
                    report.variables().stream().map(VariableChangeView::of).toList(), question, false);
        }

        public boolean touchesProject() {
            return !scripts.isEmpty() || !sprites.isEmpty() || !variables.isEmpty();
        }

        MessageChanges asReverted() {
            return new MessageChanges(scripts, sprites, variables, question, true);
        }
    }

    /** 用户拖进对话框的一段积木:正文里的标记名(如 积木1,正文里写作【积木1】)、所属角色、顶层积木 id、XML */
    public record Quote(@NotBlank @Size(max = 20) String label, @NotBlank @Size(max = 100) String sprite,
                        @NotBlank @Size(max = 100) String blockId, @NotBlank @Size(max = 200_000) String xml) {
    }

    public record ScriptTextView(String code, int blockCount) {
    }

    public record MessageView(long id, int seq, String role, String content, List<ScriptView> scripts,
                              List<SpriteChangeView> sprites, List<VariableChangeView> variables,
                              @Schema(nullable = true) QuestionView question, boolean reverted, Instant createdAt) {
    }
}
