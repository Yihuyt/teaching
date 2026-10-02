package cn.utcy.teaching.blockcoding.eval;

import cn.utcy.teaching.ai.infrastructure.DashScopeProperties;
import cn.utcy.teaching.ai.llm.LlmCalls;
import cn.utcy.teaching.ai.llm.LlmModels;
import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.blockcoding.application.HarvestPayload;
import cn.utcy.teaching.blockcoding.application.agent.SkillCatalog;
import cn.utcy.teaching.ai.agent.ChatAgentLoop.ToolResult;
import cn.utcy.teaching.blockcoding.application.agent.ScratchAgentPrompt;
import cn.utcy.teaching.blockcoding.application.agent.ScratchProgramAgent;
import cn.utcy.teaching.blockcoding.application.agent.ScriptChange;
import cn.utcy.teaching.blockcoding.application.agent.Project;
import cn.utcy.teaching.blockcoding.application.agent.Workspace;
import cn.utcy.teaching.blockcoding.application.agent.Script;
import cn.utcy.teaching.blockcoding.application.agent.ScratchTools;
import java.util.concurrent.Executors;
import cn.utcy.teaching.blockcoding.engine.BlockTable;
import cn.utcy.teaching.blockcoding.engine.SbEngine;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler;
import cn.utcy.teaching.blockcoding.engine.SbParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 积木程序生成评测:对 tasks.json 里的每个任务让智能体真实调模型生成,每题写一份 <out>/<id>.json
 * (含各脚本 XML,供 Playwright 装进真实编辑器运行判分)。
 * 环境变量:BLOCKCODING_LIVE_KEY_FILE(密钥文件,不打印)、BLOCKCODING_EVAL_OUT、
 * BLOCKCODING_EVAL_TASKS(id 正则)、BLOCKCODING_LIVE_MODEL。
 */
@EnabledIfEnvironmentVariable(named = "BLOCKCODING_LIVE_KEY_FILE", matches = ".+")
class ScratchGenerationEvalTest {
    @Test
    void evaluate() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String apiKey = Files.readString(Path.of(System.getenv("BLOCKCODING_LIVE_KEY_FILE"))).trim();
        Path out = Path.of(System.getenv().getOrDefault("BLOCKCODING_EVAL_OUT", "eval-out"));
        Files.createDirectories(out);
        Pattern taskFilter = Pattern.compile(System.getenv().getOrDefault("BLOCKCODING_EVAL_TASKS", ".*"));
        String modelId = System.getenv().getOrDefault("BLOCKCODING_LIVE_MODEL", "qwen-plus");

        SbEngine engine = new SbEngine(new ScriptCompiler(new SbParser(mapper), new BlockTable(mapper)));
        DashScopeProperties properties = new DashScopeProperties(
                URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1"),
                URI.create("https://dashscope.aliyuncs.com/api/v1"),
                Duration.ofSeconds(5), Duration.ofMinutes(2));
        LlmModels llmModels = new LlmModels(properties);
        LlmCalls llm = new LlmCalls(llmModels, properties);
        ModelConfig model = new ModelConfig(modelId, modelId, false, 0.3, 0.9, 8192);
        SkillCatalog skills = new SkillCatalog("blockcoding/skills");
        ScratchTools scratchTools = new ScratchTools(engine, skills, mapper);
        ScratchProgramAgent agent = new ScratchProgramAgent(scratchTools, new ScratchAgentPrompt(skills), mapper,
                Executors.newVirtualThreadPerTaskExecutor(), llm, 120_000);

        JsonNode tasks;
        try (var in = getClass().getResourceAsStream("/blockcoding/eval/tasks.json")) {
            tasks = mapper.readTree(in);
        }
        ArrayNode summary = mapper.createArrayNode();
        for (JsonNode task : tasks) {
            String id = task.path("id").asText();
            if (!taskFilter.matcher(id).find()) {
                continue;
            }
            List<String> spriteNames = new ArrayList<>();
            task.path("sprites").forEach(n -> spriteNames.add(n.asText()));
            HarvestPayload harvest = harvest(spriteNames);

            long t0 = System.currentTimeMillis();
            {
                ArrayNode trace = mapper.createArrayNode();
                ScratchProgramAgent.Generation generation = agent.generate(
                        llmModels.chatModel(apiKey, model), Workspace.PROJECT, Project.fromHarvest(harvest, List.of()),
                        List.of(), task.path("prompt").asText(), null, List.of(),
                        new ScratchProgramAgent.Listener() {
                            @Override
                            public void onTool(String name, String argumentsJson) {
                                trace.addObject().put("tool", name).put("args", argumentsJson);
                            }

                            @Override
                            public void onToolResult(String name, ToolResult result) {
                                trace.addObject().put("result", name).put("error", result.isError())
                                        .put("content", result.content());
                            }

                            @Override
                            public void onNotice(String message) {
                                trace.addObject().put("notice", message);
                            }
                        }, () -> false);
                long ms = System.currentTimeMillis() - t0;
                ArrayNode fenceOut = mapper.createArrayNode();
                int writeErrors = 0;
                for (JsonNode entry : trace) {
                    if (entry.path("result").asText().equals(ScratchTools.WRITE_SCRIPT) && entry.path("error").asBoolean()) {
                        writeErrors++;
                    }
                }
                for (ScriptChange change : generation.diff().scripts()) {
                    Script script = change.script();
                    ObjectNode f = fenceOut.addObject();
                    f.put("index", script.id() - 1);
                    f.put("firstPass", "success");
                    f.put("status", "success");
                    f.put("blockCount", script.blockCount());
                    f.put("code", script.code());
                    f.put("xml", script.xml());
                    f.put("targetSprite", script.sprite());
                    f.set("variables", mapper.valueToTree(script.variables()));
                    f.set("lists", mapper.valueToTree(script.lists()));
                    f.set("localVariables", mapper.valueToTree(script.localVariables()));
                    f.set("localLists", mapper.valueToTree(script.localLists()));
                    f.set("broadcasts", mapper.valueToTree(script.broadcasts()));
                    f.set("definedProcedures", mapper.valueToTree(script.definedProcedures()));
                    f.putArray("errors");
                    f.putArray("repairs");
                }
                ObjectNode result = mapper.createObjectNode();
                result.put("id", id);
                result.put("level", task.path("level").asText());
                result.put("title", task.path("title").asText());
                result.put("prompt", task.path("prompt").asText());
                result.set("sprites", task.path("sprites"));
                result.put("model", model.id());
                result.put("chatMs", ms);
                result.put("reply", generation.reply());
                result.put("rounds", generation.rounds());
                result.put("toolSteps", generation.toolSteps());
                result.put("writeErrors", writeErrors);
                result.put("fenceCount", generation.diff().scripts().size());
                result.put("firstPassOk", generation.diff().scripts().size());
                result.put("finalOk", generation.diff().scripts().size());
                result.set("fences", fenceOut);
                result.set("trace", trace);
                Files.writeString(out.resolve(id + ".json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                ObjectNode row = summary.addObject();
                row.put("id", id);
                row.put("level", task.path("level").asText());
                row.put("scripts", generation.diff().scripts().size());
                row.put("rounds", generation.rounds());
                row.put("toolSteps", generation.toolSteps());
                row.put("writeErrors", writeErrors);
                row.put("ms", ms);
                row.put("completed", generation.completed());
                System.out.println("EVAL " + row);
            }
        }
        Files.writeString(out.resolve("summary.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
    }

    private static HarvestPayload harvest(List<String> spriteNames) {
        List<HarvestPayload.SpriteContext> sprites = new ArrayList<>();
        List<String> names = spriteNames.isEmpty() ? List.of("Sprite1") : spriteNames;
        for (String name : names) {
            sprites.add(new HarvestPayload.SpriteContext(name, false,
                    List.of("costume1", "costume2"), List.of("Meow"), List.of(), List.of()));
        }
        sprites.add(new HarvestPayload.SpriteContext("Stage", true, List.of("backdrop1"), List.of("pop"), List.of(), List.of()));
        return new HarvestPayload(sprites, List.of(), List.of(), List.of(), Map.of(), names.get(0), Map.of());
    }
}
