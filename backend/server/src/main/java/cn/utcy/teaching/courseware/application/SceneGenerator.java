package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.shared.util.Text;
import cn.utcy.teaching.ai.llm.LlmCalls;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import cn.utcy.teaching.ai.structured.LlmOutputMappers;
import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.ai.llm.PromptLoader;
import cn.utcy.teaching.ai.structured.SchemaRegistry;
import cn.utcy.teaching.ai.structured.StructuredGenerator;
import cn.utcy.teaching.courseware.domain.Block;
import cn.utcy.teaching.courseware.domain.InteractiveHtml;
import cn.utcy.teaching.courseware.domain.Stage;
import cn.utcy.teaching.courseware.domain.StageNormalizer;
import cn.utcy.teaching.courseware.domain.StageProblem;
import cn.utcy.teaching.courseware.domain.StageProjections;
import cn.utcy.teaching.courseware.domain.StageValidator;
import cn.utcy.teaching.courseware.domain.SceneBrief;
import cn.utcy.teaching.courseware.domain.layout.LayoutEngine;
import cn.utcy.teaching.courseware.infrastructure.CoursewareAssetStorage;
import cn.utcy.teaching.courseware.infrastructure.CoursewareProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Service
public class SceneGenerator {

    private static final int BLOCKS_MAX_ROUNDS = 4;
    private static final String PROMPTS = "courseware/prompts";
    /** 测验页讲稿在学生作答前播放,模型常顺口报出答案;教师自己写的讲稿不受此限 */
    private static final Pattern QUIZ_ANSWER_REVEAL =
            Pattern.compile("正确答案|答案(是|为|就是|应该是)|应该?选|所以选|选项\\s*[A-Da-d]\\s*(是对的|正确)");

    /** 分配给本页的一张图:src 为课件名下的对象键(素材图片或文生图产物);required 的图是按大纲专门画的,必须放上页面 */
    public record ImageInput(String src, String description, int width, int height, String source, boolean required) {
    }

    public record Generated(Stage.Scene scene, List<String> warnings) {
    }

    private final StructuredGenerator structured;
    private final SchemaRegistry schemas;
    private final PromptLoader prompts;
    private final LlmCalls llm;
    private final ModelConfig llmModel;
    private final LayoutEngine layout;
    private final ObjectMapper objectMapper;
    private final ObjectMapper llmMapper;
    private final CoursewareAssetStorage assets;
    private final boolean vision;

    public SceneGenerator(StructuredGenerator structured,
                         @Qualifier("coursewareSchemas") SchemaRegistry schemas,
                         PromptLoader prompts, LlmCalls llm,
                         @Qualifier("coursewareLlmModel") ModelConfig llmModel,
                         LayoutEngine layout, ObjectMapper objectMapper, CoursewareAssetStorage assets,
                         CoursewareProperties properties) {
        this.structured = structured;
        this.schemas = schemas;
        this.prompts = prompts;
        this.llm = llm;
        this.llmModel = llmModel;
        this.layout = layout;
        this.objectMapper = objectMapper;
        this.llmMapper = LlmOutputMappers.lenient(objectMapper);
        this.assets = assets;
        this.vision = properties.vision();
    }

    /**
     * 生成一页的内容(不含讲稿)。existingBlocks / instruction 仅重生成时给:旧内容作基准按要求调整。
     */
    public Generated generateContent(String apiKey, String stageTitle, SceneBrief brief,
                                     int sceneNumber, int totalScenes, String sceneId, List<ImageInput> images,
                                     List<Block> existingBlocks, String instruction, Consumer<String> trace) {
        if ("interactive".equals(brief.type())) {
            String html = generateInteractiveHtml(apiKey, stageTitle, brief, instruction, trace);
            return new Generated(new Stage.Scene(sceneId, "interactive", brief.title(), "standard", brief.summary(),
                    List.of(), List.of(), List.of(), new Stage.Interactive(html, brief.widgetType(), brief.widgetOutline())),
                    List.of());
        }
        MaterialImages available = images == null || images.isEmpty()
                ? MaterialImages.fromBlocks(existingBlocks) : MaterialImages.of(images);

        Map<String, Object> vars = new HashMap<>();
        vars.put("courseTitle", stageTitle);
        vars.put("sceneNumber", sceneNumber);
        vars.put("totalScenes", totalScenes);
        vars.put("sceneTitle", brief.title());
        vars.put("sceneType", brief.type());
        vars.put("preset", brief.preset());
        vars.put("summary", brief.summary());
        vars.put("keyPoints", keyPointsText(brief));
        vars.put("instruction", instruction == null ? "" : instruction);
        boolean imageElementEnabled = !available.isEmpty();
        vars.put("imageElementEnabled", imageElementEnabled);
        vars.put("assignedImages", available.describeAll(vision && imageElementEnabled));
        vars.put("existingBlocks", existingBlocks == null || existingBlocks.isEmpty()
                ? "" : toPrettyJson(withShortImageIds(existingBlocks, available)));
        vars.put("schemaJson", schemas.rawSchema("scene-blocks"));
        PromptLoader.Prompt prompt = prompts.build(PROMPTS, "scene-blocks", vars);

        int[] refineRound = {0};
        int[] overflowRounds = {0};
        StructuredGenerator.Result<Stage.Scene> result = structured.generate(
                new StructuredGenerator.Request<>(
                        apiKey, llmModel, "scene-blocks", schemas.rawSchema("scene-blocks"),
                        schemas.validator("scene-blocks"), prompt.system(), prompt.user(), BLOCKS_MAX_ROUNDS,
                        parsed -> refineSceneBlocks(parsed, brief, sceneId, refineRound, overflowRounds, available),
                        (round, reason) -> trace.accept("内容第 " + round + " 轮重试:" + reason),
                        vision && imageElementEnabled ? available.visionParts(assets::get) : null));

        List<String> warnings = new ArrayList<>(result.warnings());
        LayoutEngine.PositionedScene positioned = layout.layoutScene(result.value());
        if ("shrunk".equals(positioned.overflow())) {
            warnings.add("内容偏多,已缩小字号(" + trimTrailingZero(positioned.fontScale()) + ")排下");
        } else if ("error".equals(positioned.overflow())) {
            warnings.add("内容超出版面:" + layout.describeOverflow(result.value()));
        }
        if (overflowRounds[0] > 0) {
            // 压缩是页内生成能做的全部;概要要点因此被削减时,拆页是教师的决定
            warnings.add("内容经 " + overflowRounds[0] + " 轮压缩才排下:对照本页概要检查要点是否被删减,"
                    + "被删减可把本页拆成两页(缩小本页概要后重生成,另加一页放另一半)");
        }
        return new Generated(result.value(), warnings);
    }

    private StructuredGenerator.Refined<Stage.Scene> refineSceneBlocks(JsonNode parsed, SceneBrief brief, String sceneId,
                                                                    int[] refineRound, int[] overflowRounds,
                                                                    MaterialImages images) {
        refineRound[0]++;
        List<Block> blocks;
        try {
            blocks = llmMapper.convertValue(parsed.path("blocks"),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Block.class));
        } catch (Exception e) {
            return StructuredGenerator.Refined.errors(List.of("blocks 结构无法解析:" + e.getMessage()));
        }
        List<String> unplaced = images.requiredButUnused(MaterialImages.imageSrcs(blocks));
        blocks = resolveImageIds(blocks, images);

        StageNormalizer.NormalizeResult<List<Block>> normalized = StageNormalizer.normalizeBlocks(blocks);
        List<String> errors = new ArrayList<>(StageProblem.forModel(StageValidator.validateBlocks(normalized.value(), brief.type())));
        errors.addAll(checkPresetRules(normalized.value(), brief));
        for (String id : unplaced) {
            errors.add("图片 " + id + " 是按大纲为本页专门画的,必须放上页面:加一个 image 块,src 填 " + id);
        }
        if (!errors.isEmpty()) {
            return StructuredGenerator.Refined.errors(errors);
        }

        // 整页重做:内容换了,教师之前的排版覆盖不再成立,从全自动排版重新开始
        Stage.Scene scene = new Stage.Scene(sceneId, brief.type(), brief.title(), brief.preset(), brief.summary(),
                normalized.value(), List.of(), List.of(), null);
        String overflowMessage = layout.describeOverflow(scene);
        List<String> warnings = new ArrayList<>(normalized.warnings());
        if (overflowMessage != null && refineRound[0] < BLOCKS_MAX_ROUNDS) {
            overflowRounds[0]++;
            // 页内生成只能压缩不能拆页(拆页是教师的操作),所以只要求压缩,并且不许丢概要要求的知识点
            return StructuredGenerator.Refined.errors(List.of(overflowMessage
                    + "请压缩文字:合并相近条目、缩短句子、去掉重复说明;概要要求的知识点一个都不能丢。"));
        }
        return StructuredGenerator.Refined.value(scene, warnings);
    }

    static List<String> checkPresetRules(List<Block> blocks, SceneBrief brief) {
        List<String> errors = new ArrayList<>();
        if ("quiz".equals(brief.type())) {
            long quizBlocks = blocks.stream().filter(b -> b instanceof Block.QuizChoice).count();
            if (quizBlocks == 0) {
                errors.add("quiz 页必须包含一个 quiz_choice 块(字段:stem/options[{label,text}]/answer/"
                        + "multiple/explanation),不要用 bullets 或 paragraph 表达题目");
            } else if (quizBlocks > 1) {
                errors.add("quiz 页只放恰好 1 个 quiz_choice 块(一页放不下第二题),你给了 " + quizBlocks + " 个,请只保留一题");
            }
        }
        if ("media-right".equals(brief.preset())) {
            long media = blocks.stream().filter(b -> b instanceof Block.Chart || b instanceof Block.Code
                    || b instanceof Block.Table || b instanceof Block.Image).count();
            if (media != 1) {
                errors.add("media-right 页必须包含恰好 1 个媒体块(chart/code/table/image),你给了 " + media + " 个");
            }
        }
        if ("two-column".equals(brief.preset()) && blocks.stream().noneMatch(b -> b instanceof Block.Columns)) {
            errors.add("two-column 页必须包含一个 columns 分栏块,否则会退化成单栏");
        }
        if ("title-cover".equals(brief.preset()) || "section-divider".equals(brief.preset())) {
            boolean onlyOneParagraph = blocks.size() == 1 && blocks.get(0) instanceof Block.Paragraph;
            if (!onlyOneParagraph) {
                errors.add(("title-cover".equals(brief.preset()) ? "封面页" : "章节分隔页")
                        + "只放 1 个 paragraph 块(一两句副标题/引言),不要放其他块");
            }
        }
        return errors;
    }

    public Generated generateSpeech(String apiKey, String stageTitle, Stage.Scene scene,
                                    int sceneNumber, int totalScenes, String nextSceneTitle, Consumer<String> trace) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("courseTitle", stageTitle);
        vars.put("sceneNumber", sceneNumber);
        vars.put("totalScenes", totalScenes);
        vars.put("sceneTitle", scene.title());
        // 讲稿模型看到的题块没有答案与讲解:念不出它不知道的东西
        vars.put("blocksJson", Stage.isBlockless(scene.type())
                ? (scene.summary() == null ? "(本页没有内容块)" : "(本页没有内容块;本页概要:" + scene.summary() + ")")
                : toPrettyJson(StageProjections.withoutQuizSecrets(scene.blocks())));
        vars.put("isInteractive", "interactive".equals(scene.type()));
        vars.put("isVideo", "video".equals(scene.type()));
        vars.put("isQuiz", "quiz".equals(scene.type()));
        vars.put("nextSceneTitle", nextSceneTitle == null ? "" : nextSceneTitle);
        vars.put("schemaJson", schemas.rawSchema("speech"));
        PromptLoader.Prompt prompt = prompts.build(PROMPTS, "scene-speech", vars);

        StructuredGenerator.Result<Stage.Scene> result = structured.generate(
                new StructuredGenerator.Request<>(
                        apiKey, llmModel, "speech", schemas.rawSchema("speech"), schemas.validator("speech"),
                        prompt.system(), prompt.user(), 3,
                        parsed -> refineSpeech(parsed, scene),
                        (round, reason) -> trace.accept("讲稿第 " + round + " 轮重试:" + reason)));
        return new Generated(result.value(), result.warnings());
    }

    private StructuredGenerator.Refined<Stage.Scene> refineSpeech(JsonNode parsed, Stage.Scene scene) {
        List<Stage.SpeechSegment> speech;
        try {
            speech = llmMapper.convertValue(parsed.path("speech"),
                    llmMapper.getTypeFactory().constructCollectionType(List.class, Stage.SpeechSegment.class));
        } catch (Exception e) {
            return StructuredGenerator.Refined.errors(List.of("speech 结构无法解析:" + e.getMessage()));
        }
        StageNormalizer.NormalizeResult<List<Stage.SpeechSegment>> normalized =
                StageNormalizer.normalizeSpeech(speech, scene.type(), scene.blocks());
        if (normalized.value().isEmpty()) {
            return StructuredGenerator.Refined.errors(
                    List.of("讲稿为空(所有段落都被清洗掉了),请引用真实存在的块 id 重写"));
        }
        // 分子只数"动作已丢弃"——"空讲稿段已丢弃"不是动作,混入会虚高丢弃率导致无谓打回
        long dropped = normalized.warnings().stream().filter(w -> w.contains("动作已丢弃")).count();
        long total = speech.stream().mapToLong(s -> s.actions() == null ? 0 : s.actions().size()).sum();
        if (total > 0 && dropped * 2 > total) {
            String ids = scene.blocks().stream().map(Block::id).reduce((a, b) -> a + ", " + b).orElse("(无)");
            return StructuredGenerator.Refined.errors(List.of(
                    "超过一半的动作目标无效被丢弃(" + dropped + "/" + total + ")。可用的块 id 只有:" + ids));
        }
        List<String> errors = new ArrayList<>(
                StageProblem.forModel(StageValidator.validateSpeech(normalized.value(), scene.type(), scene.blocks())));
        if ("quiz".equals(scene.type())) {
            for (int si = 0; si < normalized.value().size(); si++) {
                if (QUIZ_ANSWER_REVEAL.matcher(normalized.value().get(si).text()).find()) {
                    errors.add("speech[" + si + "]: 测验页讲稿在学生作答前播放,不能给出答案或判断选项对错——只读题、点考查点、给不泄底的思路提示");
                }
            }
        }
        if (!errors.isEmpty()) {
            return StructuredGenerator.Refined.errors(errors);
        }
        Stage.Scene updated = new Stage.Scene(scene.id(), scene.type(), scene.title(), scene.preset(),
                scene.summary(), scene.blocks(), normalized.value(), scene.layouts(), scene.interactive(), scene.video());
        return StructuredGenerator.Refined.value(updated, normalized.warnings());
    }

    private static List<Block> resolveImageIds(List<Block> blocks, MaterialImages images) {
        List<Block> resolved = new ArrayList<>();
        for (Block block : blocks) {
            if (block instanceof Block.Image image) {
                MaterialImages.Ref ref = images.get(image.src());
                if (ref == null) {
                    continue;
                }
                resolved.add(new Block.Image(image.id(), ref.objectKey(), ref.width(), ref.height(), image.caption()));
            } else if (block instanceof Block.Columns columns) {
                List<List<Block>> children = new ArrayList<>();
                columns.children().forEach(column -> children.add(resolveImageIds(column, images)));
                resolved.add(new Block.Columns(columns.id(), columns.ratio(), children));
            } else {
                resolved.add(block);
            }
        }
        return resolved;
    }

    private static List<Block> withShortImageIds(List<Block> blocks, MaterialImages images) {
        Map<String, String> idByKey = new HashMap<>();
        for (String id : images.ids()) {
            idByKey.put(images.get(id).objectKey(), id);
        }
        List<Block> out = new ArrayList<>();
        for (Block block : blocks) {
            if (block instanceof Block.Image image) {
                out.add(new Block.Image(image.id(), idByKey.getOrDefault(image.src(), image.src()),
                        image.width(), image.height(), image.caption()));
            } else if (block instanceof Block.Columns columns) {
                List<List<Block>> children = new ArrayList<>();
                columns.children().forEach(column -> children.add(withShortImageIds(column, images)));
                out.add(new Block.Columns(columns.id(), columns.ratio(), children));
            } else {
                out.add(block);
            }
        }
        return out;
    }

    /** 要点列表渲染成提示词里的清单;没有要点给空串(模板按 {{#if keyPoints}} 略过整节) */
    static String keyPointsText(SceneBrief brief) {
        List<String> points = brief.keyPoints() == null ? List.of() : brief.keyPoints();
        return points.stream().map(point -> "- " + point).collect(java.util.stream.Collectors.joining("\n"));
    }

    // ---- 交互页 HTML ----

    String generateInteractiveHtml(String apiKey, String stageTitle, SceneBrief brief,
                                   String instruction, Consumer<String> trace) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("courseTitle", stageTitle);
        vars.put("sceneTitle", brief.title());
        vars.put("summary", brief.summary());
        vars.put("keyPoints", keyPointsText(brief));
        vars.put("instruction", instruction == null ? "" : instruction);
        PromptLoader.Prompt prompt = prompts.build(PROMPTS, widgetTemplate(brief, vars), vars);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(prompt.system()));
        messages.add(UserMessage.from(prompt.user()));

        String lastError = "";
        for (int round = 1; round <= 3; round++) {
            String raw = llm.chatText(apiKey, llmModel, messages, false, null);
            String html = InteractiveHtml.extract(raw);
            List<String> errors;
            if (html == null) {
                errors = List.of("输出中找不到完整的 HTML 文档");
            } else {
                InteractiveHtml.Prepared prepared = InteractiveHtml.prepare(html);
                if (prepared.problems().isEmpty()) {
                    return prepared.html();
                }
                errors = StageProblem.forModel(prepared.problems());
            }
            lastError = String.join(";", errors);
            trace.accept("交互页第 " + round + " 轮未通过:" + lastError);
            messages.add(AiMessage.from(Text.abbreviate(raw, 12000)));
            messages.add(UserMessage.from(
                    "你上一次的输出不合格:" + lastError + "。请修正后重新输出完整 HTML 文档(只输出 HTML)。"));
        }
        throw new IllegalStateException("交互页「" + brief.title() + "」生成 3 轮未通过:" + lastError);
    }

    private String widgetTemplate(SceneBrief brief, Map<String, Object> vars) {
        String type = brief.widgetType();
        if (type == null) {
            return "interactive-html";
        }
        JsonNode spec = brief.widgetOutline() == null || brief.widgetOutline().isNull()
                ? objectMapper.createObjectNode() : brief.widgetOutline();
        switch (type) {
            case "simulation" -> {
                vars.put("concept", specText(spec, "concept", brief.title()));
                vars.put("keyVariables", joinStrings(spec, "keyVariables"));
                return "interactive-simulation";
            }
            case "diagram" -> {
                vars.put("diagramType", specText(spec, "diagramType", "flowchart"));
                JsonNode nodes = spec.path("nodes");
                boolean hasNodes = nodes.isArray() && !nodes.isEmpty();
                int nodeCount = spec.path("nodeCount").asInt(hasNodes ? nodes.size() : 0);
                vars.put("nodeCount", nodeCount);
                vars.put("hasNodeCount", nodeCount > 0);
                vars.put("prescribedNodes", hasNodes ? toPrettyJson(nodes) : "");
                vars.put("hasPrescribedNodes", hasNodes);
                return "interactive-diagram";
            }
            case "game" -> {
                vars.put("gameType", specText(spec, "gameType", "action"));
                vars.put("challenge", specText(spec, "challenge", ""));
                vars.put("playerControls", joinStrings(spec, "playerControls"));
                return "interactive-game";
            }
            case "visualization3d" -> {
                vars.put("visualizationType", specText(spec, "visualizationType", "custom"));
                vars.put("objects", joinStrings(spec, "objects"));
                vars.put("interactions", joinStrings(spec, "interactions"));
                return "interactive-viz3d";
            }
            default -> {
                return "interactive-html";
            }
        }
    }

    private static String specText(JsonNode spec, String field, String fallback) {
        JsonNode node = spec.path(field);
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : fallback;
    }

    private static String joinStrings(JsonNode spec, String field) {
        JsonNode node = spec.path(field);
        if (!node.isArray()) {
            return "";
        }
        List<String> values = new ArrayList<>();
        node.forEach(n -> {
            if (n.isTextual() && !n.asText().isBlank()) {
                values.add(n.asText());
            }
        });
        return String.join("、", values);
    }

    private String toPrettyJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private static String trimTrailingZero(double value) {
        String s = String.valueOf(value);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
