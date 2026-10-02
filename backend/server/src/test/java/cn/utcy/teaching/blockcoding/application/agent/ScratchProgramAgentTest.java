package cn.utcy.teaching.blockcoding.application.agent;

import cn.utcy.teaching.ai.agent.ChatAgentLoop;
import cn.utcy.teaching.ai.llm.FakeLlm;
import cn.utcy.teaching.ai.llm.ScriptedStreamingChatModel;
import cn.utcy.teaching.blockcoding.application.agent.SkillCatalog;
import cn.utcy.teaching.blockcoding.application.HarvestPayload;
import cn.utcy.teaching.blockcoding.domain.AssistantMode;
import cn.utcy.teaching.blockcoding.engine.BlockTable;
import cn.utcy.teaching.blockcoding.engine.SbEngine;
import cn.utcy.teaching.blockcoding.engine.SbParser;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class ScratchProgramAgentTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ScratchProgramAgent agent(StreamingChatModel model) {
        SkillCatalog skills = new SkillCatalog("blockcoding/skills");
        SbEngine engine = new SbEngine(new ScriptCompiler(new SbParser(MAPPER), new BlockTable(MAPPER)));
        return new ScratchProgramAgent(new ScratchTools(engine, skills, MAPPER), new ScratchAgentPrompt(skills), MAPPER,
                Executors.newVirtualThreadPerTaskExecutor(), new FakeLlm(model), 120_000);
    }

    private static Consumer<ScriptedStreamingChatModel.Output> text(String text) {
        return out -> out.text(text);
    }

    private static Consumer<ScriptedStreamingChatModel.Output> call(String name, String arguments) {
        return out -> out.toolCall("call_1", name, arguments);
    }

    private static HarvestPayload project() {
        List<HarvestPayload.SpriteContext> sprites = List.of(
                new HarvestPayload.SpriteContext("Cat", false, List.of("造型1"), List.of("Meow"), List.of(), List.of()),
                new HarvestPayload.SpriteContext("Stage", true, List.of("背景1"), List.of(), List.of(), List.of()));
        return new HarvestPayload(sprites, List.of(), List.of(), List.of(), Map.of(), "Cat", Map.of());
    }

    @Test
    @DisplayName("只输出文字就停下:追加提醒,下一轮只给收尾工具且强制调用;final_answer 的正文分片流出、拼起来和最终回复一致")
    void plainTextIsNotAFinish() {
        List<String> notices = new ArrayList<>();
        StringBuilder streamed = new StringBuilder();
        List<ToolChoice> choices = new ArrayList<>();
        List<String> lastMessages = new ArrayList<>();
        List<List<String>> offered = new ArrayList<>();
        List<String> traces = new ArrayList<>();
        ScratchProgramAgent.Listener listener = new ScratchProgramAgent.Listener() {
            @Override
            public void onContent(String delta) {
                streamed.append(delta);
            }

            @Override
            public void onNotice(String message) {
                notices.add(message);
            }

            @Override
            public void onTrace(String message) {
                traces.add(message);
            }
        };
        List<Consumer<ScriptedStreamingChatModel.Output>> rounds = new ArrayList<>(List.of(
                call(ScratchTools.WRITE_SCRIPT, "{\"sprite\": \"Cat\", \"code\": \"when green flag clicked\\nmove (10) steps\"}"),
                text("做好了。"),
                call(ScratchTools.FINAL_ANSWER, "{\"text\": \"做好了,点绿旗后小猫会\\\"往前走\\\"。\"}")));
        ScriptedStreamingChatModel model = new ScriptedStreamingChatModel((request, out) -> {
            choices.add(request.toolChoice());
            offered.add(request.toolSpecifications().stream().map(spec -> spec.name()).toList());
            lastMessages.add(ChatAgentLoop.textOf(request.messages().getLast()));
            rounds.removeFirst().accept(out);
        });

        ScratchProgramAgent.Generation generation = agent(model).generate(model, Workspace.PROJECT, Project.fromHarvest(project(), List.of()), List.of(), "让小猫走",
                null, List.of(), listener, () -> false);

        assertThat(generation.reply()).isEqualTo("做好了,点绿旗后小猫会\"往前走\"。");
        assertThat(streamed.toString()).isEqualTo(generation.reply());
        assertThat(generation.diff().scripts()).hasSize(1);
        assertThat(generation.rounds()).isEqualTo(3);
        assertThat(choices).containsExactly(null, null, ToolChoice.REQUIRED);
        assertThat(lastMessages.get(2)).isEqualTo(ScratchProgramAgent.TEXT_STOP_REMINDER);
        assertThat(offered.get(2)).as("文字停下后只给收尾工具,免得它被迫调工具时随手改作品")
                .containsExactly(ScratchTools.FINAL_ANSWER, ScratchTools.ASK_USER);
        assertThat(traces).anyMatch(n -> n.contains("强制它调用工具"));
        assertThat(notices).as("循环内部的提示不给用户看").isEmpty();
    }

    @Test
    @DisplayName("final_answer 被打回后再交:先通知正文作废,再从头流出新正文;用户最终看到的就是被采纳的那份")
    void rejectedReplyIsStreamedAgainFromScratch() {
        List<String> events = new ArrayList<>();
        ScratchProgramAgent.Listener listener = new ScratchProgramAgent.Listener() {
            @Override
            public void onContent(String delta) {
                events.add("+" + delta);
            }

            @Override
            public void onReplyReset() {
                events.add("RESET");
            }
        };
        List<Consumer<ScriptedStreamingChatModel.Output>> rounds = new ArrayList<>(List.of(
                call(ScratchTools.FINAL_ANSWER, "{\"text\": \"这样写:\\n```scratchblocks\\nfly (10) steps\\n```\"}"),
                call(ScratchTools.FINAL_ANSWER, "{\"text\": \"这样写:\\n```scratchblocks\\nmove (10) steps\\n```\"}")));
        ScriptedStreamingChatModel model = new ScriptedStreamingChatModel((request, out) -> rounds.removeFirst().accept(out));

        ScratchProgramAgent.Generation generation = agent(model).generate(model, Workspace.PROJECT, Project.fromHarvest(project(), List.of()), List.of(), "怎么走",
                null, List.of(), listener, () -> false);

        assertThat(generation.reply()).isEqualTo("这样写:\n```scratchblocks\nmove (10) steps\n```");
        int reset = events.indexOf("RESET");
        assertThat(reset).isGreaterThan(0);
        assertThat(String.join("", events.subList(0, reset)).replace("+", "")).contains("fly (10) steps");
        assertThat(String.join("", events.subList(reset + 1, events.size())).replace("+", "")).isEqualTo(generation.reply());
    }

    @Test
    @DisplayName("轮数用完:只给收尾工具并强制调用,final_answer 的话就是回复,已写的脚本照常成为产物")
    void exhaustedRoundsWrapUpThroughFinalAnswer() {
        List<String> notices = new ArrayList<>();
        List<List<String>> offered = new ArrayList<>();
        ScratchProgramAgent.Listener listener = new ScratchProgramAgent.Listener() {
            @Override
            public void onNotice(String message) {
                notices.add(message);
            }
        };
        ScriptedStreamingChatModel model = new ScriptedStreamingChatModel((request, out) -> {
            List<String> names = request.toolSpecifications().stream().map(spec -> spec.name()).toList();
            offered.add(names);
            if (names.equals(List.of(ScratchTools.FINAL_ANSWER, ScratchTools.ASK_USER))) {
                assertThat(request.toolChoice()).isEqualTo(ToolChoice.REQUIRED);
                out.toolCall("call_done", ScratchTools.FINAL_ANSWER, "{\"text\": \"轮数到了,先做到这里。\"}");
                return;
            }
            out.toolCall("call_1", offered.size() == 1 ? ScratchTools.WRITE_SCRIPT : ScratchTools.LIST_PROJECT,
                    offered.size() == 1 ? "{\"sprite\": \"Cat\", \"code\": \"when green flag clicked\\nmove (10) steps\"}" : "{}");
        });

        ScratchProgramAgent.Generation generation = agent(model).generate(model, Workspace.PROJECT, Project.fromHarvest(project(), List.of()), List.of(), "让小猫走",
                null, List.of(), listener, () -> false);

        assertThat(generation.reply()).isEqualTo("轮数到了,先做到这里。");
        assertThat(generation.diff().scripts()).hasSize(1);
        assertThat(generation.completed()).isTrue();
        assertThat(offered.getLast()).containsExactly(ScratchTools.FINAL_ANSWER, ScratchTools.ASK_USER);
        assertThat(offered).hasSize(ScratchProgramAgent.MAX_ROUNDS + 1);
        assertThat(notices).containsExactly(ScratchProgramAgent.USER_NOTICE_BUDGET);
    }
}
