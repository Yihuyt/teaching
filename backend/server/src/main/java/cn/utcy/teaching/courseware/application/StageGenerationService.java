package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.ai.llm.AiUnavailableException;
import cn.utcy.teaching.shared.util.BoundedParallel;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.ai.structured.LlmOutputMappers;
import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.ai.llm.PromptLoader;
import cn.utcy.teaching.ai.structured.SchemaRegistry;
import cn.utcy.teaching.shared.sse.SseSupport;
import cn.utcy.teaching.ai.structured.StructuredGenerator;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.courseware.application.CoursewareMaterialBundleService.Bundle;
import cn.utcy.teaching.courseware.application.CoursewareMaterialBundleService.BundleImage;
import cn.utcy.teaching.courseware.domain.Block;
import cn.utcy.teaching.courseware.domain.SceneBrief;
import cn.utcy.teaching.courseware.domain.Stage;
import cn.utcy.teaching.courseware.domain.StageCommands;
import cn.utcy.teaching.courseware.infrastructure.CoursewareAssetStorage;
import cn.utcy.teaching.courseware.infrastructure.CoursewareProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Service
public class StageGenerationService {

    public static final int MIN_SCENE_COUNT = 3;
    public static final int MAX_SCENE_COUNT = StageCommands.MAX_SCENES;
    public static final int MAX_QUIZ_COUNT = 10;
    public static final int MAX_INTERACTIVE_COUNT = 5;
    /** 配比预留:封面 1 页 + 至少 1 页讲解 */
    public static final int RESERVED_SCENES = 2;
    public static final int MAX_SCENE_IMAGES = 8;

    private static final String PROMPTS = "courseware/prompts";
    private static final int OUTLINE_MAX_ROUNDS = 3;

    private final StructuredGenerator structured;
    private final SchemaRegistry schemas;
    private final PromptLoader prompts;
    private final ModelConfig llmModel;
    private final ObjectMapper objectMapper;
    private final ObjectMapper llmMapper;
    private final TaskExecutor executor;
    private final CoursewareApplicationService coursewares;
    private final StageEditService edits;
    private final SceneGenerator scenes;
    private final CoursewareMaterialBundleService bundles;
    private final CoursewareImageService images;
    private final CoursewareAssetStorage assets;
    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;
    private final CourseAiKeys aiKeys;
    private final boolean vision;

    public StageGenerationService(StructuredGenerator structured,
                                  @Qualifier("coursewareSchemas") SchemaRegistry schemas,
                                  PromptLoader prompts,
                                  @Qualifier("coursewareLlmModel") ModelConfig llmModel,
                                  ObjectMapper objectMapper,
                                  @Qualifier("sseTaskExecutor") TaskExecutor executor,
                                  CoursewareApplicationService coursewares, StageEditService edits,
                                  SceneGenerator scenes, CoursewareMaterialBundleService bundles,
                                  CoursewareImageService images, CoursewareAssetStorage assets,
                                  CourseAccess courseAccess, CurrentActor currentActor, CourseAiKeys aiKeys,
                                  CoursewareProperties properties) {
        this.structured = structured;
        this.schemas = schemas;
        this.prompts = prompts;
        this.llmModel = llmModel;
        this.objectMapper = objectMapper;
        this.llmMapper = LlmOutputMappers.lenient(objectMapper);
        this.executor = executor;
        this.coursewares = coursewares;
        this.edits = edits;
        this.scenes = scenes;
        this.bundles = bundles;
        this.images = images;
        this.assets = assets;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
        this.aiKeys = aiKeys;
        this.vision = properties.vision();
    }

    /** 素材图片的稳定引用:素材包 id + 包内图片 id(短 id 只在一次大纲对话里有效,不跨请求) */
    public record MaterialImageRef(long materialId, String imageId) {
    }

    public record SceneOutline(String title, String type, String preset, String summary, List<String> keyPoints,
                               String widgetType, JsonNode widgetOutline, List<MaterialImageRef> images,
                               SceneBrief.Illustration illustration) {
    }

    public record ConfirmedOutline(String title, List<SceneOutline> scenes) {
    }

    record GeneratedOutline(String title, List<SceneBrief> scenes) {
    }

    private record CatalogEntry(String id, Bundle bundle, BundleImage image) {
    }

    // ---- 大纲 ----

    /**
     * 大纲(SSE):事件 trace{message} / outline{title, scenes[], images[]} / error{message}。
     * scenes[].imageIds 引用 images[] 里的短 id;确认时前端把它们换成 {materialId, imageId}。
     * 数量约束矛盾在调用模型之前拦截(400)。
     */
    public SseEmitter outlineSse(long courseId, long coursewareId, String requirement,
                                 Integer sceneCount, Integer quizCount, Integer interactiveCount) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        requireConsistentCounts(sceneCount, quizCount, interactiveCount);
        String apiKey = aiKeys.llmKeyForCourse(courseId);
        Stage stage = coursewares.getInternal(courseId, coursewareId);
        List<Bundle> materials = bundles.listForCourseware(courseId, coursewareId);
        List<CatalogEntry> catalog = catalog(materials);
        MaterialImages available = MaterialImages.of(catalog.stream().map(StageGenerationService::toImageInput).toList());
        return SseSupport.run(executor, objectMapper, sink -> {
            boolean attachVision = vision && !available.isEmpty();
            Map<String, Object> vars = new HashMap<>();
            vars.put("stageTitle", stage.title());
            vars.put("requirement", requirement);
            vars.put("materialText", materialText(materials));
            vars.put("availableImages", available.describeAll(attachVision));
            vars.put("hasSourceImages", !available.isEmpty());
            vars.put("visionNote", attachVision ? ",其中标有 [见附图] 的图已随本消息附上" : "");
            vars.put("sceneCount", sceneCount == null ? "" : sceneCount);
            vars.put("quizCount", quizCount == null ? "" : quizCount);
            vars.put("interactiveCount", interactiveCount == null ? "" : interactiveCount);
            vars.put("schemaJson", schemas.rawSchema("outline"));
            PromptLoader.Prompt prompt = prompts.build(PROMPTS, "outline", vars);

            StructuredGenerator.Result<GeneratedOutline> result = structured.generate(
                    new StructuredGenerator.Request<>(
                            apiKey, llmModel, "outline", schemas.rawSchema("outline"), schemas.validator("outline"),
                            prompt.system(), prompt.user(), OUTLINE_MAX_ROUNDS,
                            parsed -> refineOutline(parsed, available, sceneCount, quizCount, interactiveCount),
                            (round, reason) -> sink.emit(trace(0, "大纲第 " + round + " 轮重试:" + reason)),
                            attachVision ? available.visionParts(assets::get) : null));

            GeneratedOutline outline = result.value();
            List<Map<String, Object>> imageTable = new ArrayList<>();
            for (CatalogEntry entry : catalog) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", entry.id());
                row.put("materialId", entry.bundle().id());
                row.put("imageId", entry.image().id());
                imageTable.add(row);
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "outline");
            event.put("title", outline.title());
            event.put("scenes", outline.scenes());
            event.put("images", imageTable);
            sink.emit(event);
        });
    }

    private static void requireConsistentCounts(Integer sceneCount, Integer quizCount, Integer interactiveCount) {
        if (sceneCount == null) {
            return;
        }
        int quiz = quizCount == null ? 0 : quizCount;
        int interactive = interactiveCount == null ? 0 : interactiveCount;
        if (quiz + interactive + RESERVED_SCENES > sceneCount) {
            throw new BadRequestException("页数配比矛盾:测验 " + quiz + " 页 + 交互 " + interactive
                    + " 页 + 封面与至少 1 页讲解,已超过总页数 " + sceneCount);
        }
    }

    StructuredGenerator.Refined<GeneratedOutline> refineOutline(JsonNode parsed, MaterialImages available,
                                                                        Integer sceneCount, Integer quizCount,
                                                                        Integer interactiveCount) {
        GeneratedOutline outline;
        try {
            outline = llmMapper.treeToValue(parsed, GeneratedOutline.class);
        } catch (Exception e) {
            return StructuredGenerator.Refined.errors(List.of("大纲结构无法解析:" + e.getMessage()));
        }
        if (outline.scenes() == null || outline.scenes().isEmpty()) {
            return StructuredGenerator.Refined.errors(List.of("大纲没有任何页面"));
        }
        List<String> errors = new ArrayList<>();
        List<SceneBrief> corrected = new ArrayList<>();
        for (int i = 0; i < outline.scenes().size(); i++) {
            SceneBrief item = outline.scenes().get(i);
            String preset = item.preset();
            if (!Stage.PRESET_NAMES.contains(preset)) {
                errors.add("scenes[" + i + "].preset \"" + preset + "\" 不在可用预设中");
                continue;
            }
            if ("quiz".equals(item.type())) {
                preset = "quiz";
            } else if ("interactive".equals(item.type())) {
                preset = "standard";
            } else if ("quiz".equals(preset)) {
                errors.add("scenes[" + i + "] 是讲解页,不能用 quiz 预设");
                continue;
            }
            String widgetType = item.widgetType();
            JsonNode widgetOutline = item.widgetOutline() == null || item.widgetOutline().isNull() ? null : item.widgetOutline();
            if ("interactive".equals(item.type())) {
                if (widgetType == null || !Stage.WIDGET_TYPES.contains(widgetType)) {
                    errors.add("scenes[" + i + "] 是交互页,必须给出合法的 widgetType("
                            + String.join("/", Stage.WIDGET_TYPES) + ")和配套 widgetOutline");
                    continue;
                }
            } else {
                widgetType = null;
                widgetOutline = null;
            }
            List<String> imageIds = new ArrayList<>();
            if ("content".equals(item.type()) && item.imageIds() != null) {
                for (String id : item.imageIds()) {
                    if (id != null && available.get(id) != null && !imageIds.contains(id) && imageIds.size() < MAX_SCENE_IMAGES) {
                        imageIds.add(id);
                    }
                }
            }
            SceneBrief.Illustration illustration = "content".equals(item.type())
                    ? SceneBrief.Illustration.normalize(item.illustration()) : null;
            corrected.add(new SceneBrief(item.title(), item.type(), preset, item.summary(),
                    SceneBrief.normalizeKeyPoints(item.keyPoints()), widgetType, widgetOutline,
                    imageIds.isEmpty() ? null : imageIds, illustration));
        }
        if (!errors.isEmpty()) {
            return StructuredGenerator.Refined.errors(errors);
        }
        if (sceneCount != null && corrected.size() != sceneCount) {
            errors.add("要求共 " + sceneCount + " 页,你给了 " + corrected.size() + " 页,必须严格等于要求页数");
        }
        long quiz = corrected.stream().filter(s -> "quiz".equals(s.type())).count();
        long interactive = corrected.stream().filter(s -> "interactive".equals(s.type())).count();
        if (quizCount != null && quiz != quizCount) {
            errors.add("要求恰好 " + quizCount + " 页测验(type=quiz),你给了 " + quiz + " 页");
        }
        if (interactiveCount != null && interactive != interactiveCount) {
            errors.add("要求恰好 " + interactiveCount + " 页交互(type=interactive),你给了 " + interactive + " 页");
        }
        if (!errors.isEmpty()) {
            return StructuredGenerator.Refined.errors(errors);
        }
        return StructuredGenerator.Refined.value(new GeneratedOutline(outline.title(), corrected));
    }

    // ---- 按大纲生成 ----

    /**
     * 按确认后的大纲生成整份课件(SSE):
     * 事件 scene_start{order,total,title} / trace{order,message} / scene_done{order,sceneId,version,warnings[]} /
     * scene_failed{order,title,sceneId,message} / done{version,total,failed} / error{message}。
     * 先清空课件并写入标题,再逐页落库;取消或断线保住已落库的页。
     */
    public SseEmitter generateSse(long courseId, long coursewareId, ConfirmedOutline outline) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        validateOutline(outline);
        String apiKey = aiKeys.llmKeyForCourse(courseId);
        Map<MaterialImageRef, BundleImage> resolved = resolveMaterialImages(courseId, coursewareId, outline);
        return SseSupport.run(executor, objectMapper, sink -> {
            edits.resetInternal(courseId, coursewareId, outline.title());
            int total = outline.scenes().size();
            List<CompletableFuture<SceneGenerator.Generated>> futures = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                final int index = i;
                futures.add(CompletableFuture.supplyAsync(
                        () -> generateScene(apiKey, coursewareId, outline, index, resolved, sink), executor));
            }

            long version = 0;
            int failed = 0;
            boolean interrupted = false;
            for (int i = 0; i < total; i++) {
                if (sink.cancelled()) {
                    interrupted = true;
                    break;
                }
                int order = i + 1;
                SceneOutline item = outline.scenes().get(i);
                sink.emit(Map.of("type", "scene_start", "order", order, "total", total, "title", item.title()));
                String failure = null;
                CoursewareApplicationService.VersionedStage applied = null;
                List<String> warnings = List.of();
                try {
                    SceneGenerator.Generated generated = futures.get(i).join();
                    warnings = generated.warnings();
                    applied = edits.putSceneInternal(courseId, coursewareId, order, generated.scene());
                } catch (CompletionException | StageCommands.Rejected e) {
                    Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                    if (sink.cancelled()) {
                        interrupted = true;
                        break;
                    }
                    failure = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                }
                if (failure != null) {
                    failed++;
                    applied = edits.putSceneInternal(courseId, coursewareId, order, placeholder(item));
                }
                version = applied.version();
                String sceneId = applied.stage().scenes().get(order - 1).id();
                if (failure == null) {
                    sink.emit(Map.of("type", "scene_done", "order", order, "sceneId", sceneId,
                            "version", version, "warnings", warnings));
                } else {
                    sink.emit(Map.of("type", "scene_failed", "order", order, "title", item.title(),
                            "sceneId", sceneId, "message", failure));
                }
            }
            if (interrupted) {
                futures.forEach(future -> future.cancel(false));
                sink.emit(Map.of("type", "error", "message", "生成已中断,已完成的页已保存"));
                return;
            }
            sink.emit(Map.of("type", "done", "version", version, "total", total, "failed", failed));
        });
    }

    private SceneGenerator.Generated generateScene(String apiKey, long coursewareId, ConfirmedOutline outline, int index,
                                                   Map<MaterialImageRef, BundleImage> resolved,
                                                   SseSupport.EventSink sink) {
        BoundedParallel.requireNotCancelled(sink::cancelled);
        SceneOutline item = outline.scenes().get(index);
        int order = index + 1;
        int total = outline.scenes().size();
        Consumer<String> trace = text -> sink.emit(trace(order, text));
        List<SceneGenerator.ImageInput> adopted = new ArrayList<>();
        for (MaterialImageRef ref : item.images()) {
            BundleImage image = resolved.get(ref);
            Block.Image copy = images.adoptMaterialImage(coursewareId, image, "blk-image-pending", null);
            adopted.add(new SceneGenerator.ImageInput(copy.src(), image.description(), image.width(), image.height(),
                    materialSource(image), false));
        }
        // 大纲要的配图先画出来,内容模型见到的是一张真实尺寸的图;画不出来这页照常生成,只是没这张图
        List<String> warnings = new ArrayList<>();
        SceneBrief.Illustration illustration = item.illustration();
        if (illustration != null) {
            trace.accept("正在按大纲画配图…");
            try {
                CoursewareImageService.Picked picked = images.generateInternal(apiKey, coursewareId,
                        illustration.prompt(), illustration.aspectRatio());
                adopted.add(new SceneGenerator.ImageInput(picked.src(), illustration.prompt(), picked.width(),
                        picked.height(), "按大纲为本页专门画的配图", true));
            } catch (AiUnavailableException e) {
                warnings.add("配图没画出来:" + e.getMessage() + ";本页先不配图,可在内容面板里手动插图");
            }
        }
        SceneBrief brief = new SceneBrief(item.title(), item.type(), item.preset(), item.summary(),
                SceneBrief.normalizeKeyPoints(item.keyPoints()), item.widgetType(), item.widgetOutline(), List.of(), null);
        SceneGenerator.Generated generated = scenes.generateContent(apiKey, outline.title(), brief, order, total,
                "scene-" + order, adopted, null, null, trace);
        if (warnings.isEmpty()) {
            return generated;
        }
        warnings.addAll(generated.warnings());
        return new SceneGenerator.Generated(generated.scene(), warnings);
    }

    // ---- 生成讲稿与动作 ----

    static List<Integer> speechTargets(Stage stage, String scope) {
        List<Integer> targets = new ArrayList<>();
        for (int i = 0; i < stage.scenes().size(); i++) {
            Stage.Scene scene = stage.scenes().get(i);
            boolean missing = scene.speech() == null || scene.speech().isEmpty();
            if ("all".equals(scope) || missing) {
                targets.add(i);
            }
        }
        return targets;
    }

    /**
     * 生成讲稿与动作(SSE):按库里当前的内容(教师改过的),各页并行生成讲稿,按页序逐页落库;
     * 某页失败不拖累其余页,那一页保持原样。事件 scene_start / trace / scene_done / scene_failed / done{version,total,failed} / error。
     */
    public SseEmitter generateSpeechSse(long courseId, long coursewareId, String scope) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        String apiKey = aiKeys.llmKeyForCourse(courseId);
        Stage stage = coursewares.getInternal(courseId, coursewareId);
        List<Integer> targets = speechTargets(stage, scope);
        if (targets.isEmpty()) {
            throw new BadRequestException(stage.scenes().isEmpty() ? "课件还没有任何页面" : "每一页都已有讲稿;要重写就选全部重写");
        }
        int total = stage.scenes().size();
        return SseSupport.run(executor, objectMapper, sink -> {
            List<CompletableFuture<SceneGenerator.Generated>> futures = new ArrayList<>();
            for (int index : targets) {
                Stage.Scene scene = stage.scenes().get(index);
                int order = index + 1;
                String nextTitle = index + 1 < total ? stage.scenes().get(index + 1).title() : null;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    BoundedParallel.requireNotCancelled(sink::cancelled);
                    return scenes.generateSpeech(apiKey, stage.title(), scene, order, total, nextTitle,
                            text -> sink.emit(trace(order, text)));
                }, executor));
            }
            long version = 0;
            int failed = 0;
            for (int t = 0; t < targets.size(); t++) {
                if (sink.cancelled()) {
                    futures.forEach(future -> future.cancel(false));
                    sink.emit(Map.of("type", "error", "message", "生成已中断,已完成的页已保存"));
                    return;
                }
                Stage.Scene scene = stage.scenes().get(targets.get(t));
                int order = targets.get(t) + 1;
                sink.emit(Map.of("type", "scene_start", "order", order, "total", total, "title", scene.title()));
                try {
                    SceneGenerator.Generated spoken = futures.get(t).join();
                    CoursewareApplicationService.VersionedStage applied = edits.replaceSceneInternal(courseId, coursewareId, scene.id(), spoken.scene());
                    version = applied.version();
                    sink.emit(Map.of("type", "scene_done", "order", order, "sceneId", scene.id(),
                            "version", version, "warnings", spoken.warnings()));
                } catch (CompletionException | StageCommands.Rejected | NotFoundException e) {
                    Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                    if (sink.cancelled()) {
                        futures.forEach(future -> future.cancel(false));
                        sink.emit(Map.of("type", "error", "message", "生成已中断,已完成的页已保存"));
                        return;
                    }
                    failed++;
                    sink.emit(Map.of("type", "scene_failed", "order", order, "title", scene.title(), "sceneId", scene.id(),
                            "message", cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
                }
            }
            sink.emit(Map.of("type", "done", "version", version, "total", targets.size(), "failed", failed));
        });
    }

    static Stage.Scene placeholder(SceneOutline item) {
        String note = "本页生成失败,请重生成本页";
        return switch (item.type()) {
            case "quiz" -> new Stage.Scene("scene-pending", "quiz", item.title(), "quiz", item.summary(),
                    List.of(new Block.QuizChoice("blk-quiz_choice-1", item.summary(),
                            List.of(new Block.QuizOption("A", note), new Block.QuizOption("B", note)),
                            List.of("A"), false, note)),
                    List.of(), List.of(), null);
            case "interactive" -> new Stage.Scene("scene-pending", "interactive", item.title(), "standard", item.summary(),
                    List.of(), List.of(), List.of(),
                    new Stage.Interactive("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"></head>"
                            + "<body style=\"font-family:sans-serif;padding:32px\"><h2>" + escapeHtml(item.title())
                            + "</h2><p>" + escapeHtml(item.summary()) + "</p><p>" + note + "</p></body></html>",
                            item.widgetType(), item.widgetOutline()));
            default -> new Stage.Scene("scene-pending", "content", item.title(), item.preset(), item.summary(),
                    List.of(new Block.Paragraph("blk-paragraph-1", item.summary())),
                    List.of(), List.of(), null);
        };
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static void validateOutline(ConfirmedOutline outline) {
        List<SceneOutline> items = outline.scenes();
        if (items == null || items.isEmpty() || items.size() > MAX_SCENE_COUNT) {
            throw new BadRequestException("大纲页数必须在 1~" + MAX_SCENE_COUNT + " 之间");
        }
        for (int i = 0; i < items.size(); i++) {
            SceneOutline item = items.get(i);
            String label = "第 " + (i + 1) + " 页";
            if (item.title() == null || item.title().isBlank()) {
                throw new BadRequestException(label + "缺少标题");
            }
            if (item.summary() == null || item.summary().isBlank()) {
                throw new BadRequestException(label + "「" + item.title() + "」缺少内容概要(生成以概要为依据,不能为空)");
            }
            if (item.keyPoints() != null && item.keyPoints().size() > SceneBrief.MAX_KEY_POINTS) {
                throw new BadRequestException(label + "的要点最多 " + SceneBrief.MAX_KEY_POINTS + " 条");
            }
            if (item.keyPoints() != null && item.keyPoints().stream().anyMatch(k -> k != null && k.length() > SceneBrief.MAX_KEY_POINT_CHARS)) {
                throw new BadRequestException(label + "的要点每条最多 " + SceneBrief.MAX_KEY_POINT_CHARS + " 字");
            }
            if (!List.of("content", "quiz", "interactive").contains(item.type())) {
                throw new BadRequestException(label + "的类型 \"" + item.type() + "\" 非法(流水线只生成讲解 / 测验 / 交互页,视频页由教师上传)");
            }
            String presetError = StageCommands.validatePreset(item.type(), item.preset());
            if (presetError != null) {
                throw new BadRequestException(label + ":" + presetError);
            }
            if ("interactive".equals(item.type())) {
                if (item.widgetType() == null || !Stage.WIDGET_TYPES.contains(item.widgetType())) {
                    throw new BadRequestException(label + "是交互页,widgetType 必须是 "
                            + String.join("/", Stage.WIDGET_TYPES) + " 之一");
                }
            } else if (item.widgetType() != null || item.widgetOutline() != null) {
                throw new BadRequestException(label + "不是交互页,不能携带 widgetType/widgetOutline");
            }
            if (!item.images().isEmpty() && !"content".equals(item.type())) {
                throw new BadRequestException(label + "不是讲解页,不能配图");
            }
            if (item.illustration() != null) {
                if (!"content".equals(item.type())) {
                    throw new BadRequestException(label + "不是讲解页,不能要 AI 配图");
                }
                if (item.illustration().prompt() == null || item.illustration().prompt().isBlank()) {
                    throw new BadRequestException(label + "的 AI 配图缺少画面描述");
                }
            }
            if (item.images().size() > MAX_SCENE_IMAGES) {
                throw new BadRequestException(label + "最多配 " + MAX_SCENE_IMAGES + " 张图");
            }
        }
    }

    /** 大纲引用的素材图片在请求线程解析:素材包或图片不存在立刻 404,不进 SSE */
    private Map<MaterialImageRef, BundleImage> resolveMaterialImages(long courseId, long coursewareId,
                                                                     ConfirmedOutline outline) {
        Map<Long, Bundle> byId = new HashMap<>();
        for (Bundle bundle : bundles.listForCourseware(courseId, coursewareId)) {
            byId.put(bundle.id(), bundle);
        }
        Map<MaterialImageRef, BundleImage> resolved = new HashMap<>();
        for (SceneOutline item : outline.scenes()) {
            for (MaterialImageRef ref : item.images()) {
                Bundle bundle = byId.get(ref.materialId());
                if (bundle == null) {
                    throw new NotFoundException("素材不存在:" + ref.materialId());
                }
                BundleImage image = bundle.imagesById().get(ref.imageId());
                if (image == null) {
                    throw new NotFoundException("素材图片不存在:" + ref.imageId());
                }
                resolved.put(ref, image);
            }
        }
        return resolved;
    }

    // ---- 单页重生成 ----

    /**
     * 单页重生成(SSE):scope=content 整页内容按调整要求重做(讲稿清空、排版覆盖归零,内容改定后再生成讲稿);scope=speech 只重讲稿。
     * 事件 scene_start / trace / scene_done / done{version} / error。完成即落库。
     */
    public SseEmitter regenerateSceneSse(long courseId, long coursewareId, String sceneId, String scope,
                                         String rawInstruction) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        String apiKey = aiKeys.llmKeyForCourse(courseId);
        Stage stage = coursewares.getInternal(courseId, coursewareId);
        int index = sceneIndex(stage, sceneId);
        Stage.Scene scene = stage.scenes().get(index);
        if ("video".equals(scene.type()) && "content".equals(scope)) {
            throw new BadRequestException("视频页的内容是教师上传的视频,只能重写讲稿");
        }
        int order = index + 1;
        int total = stage.scenes().size();
        return SseSupport.run(executor, objectMapper, sink -> {
            sink.emit(Map.of("type", "scene_start", "order", order, "total", total, "title", scene.title()));
            Regenerated result = regenerateScene(apiKey, courseId, coursewareId, sceneId, scope, rawInstruction,
                    text -> sink.emit(trace(order, text)), sink::cancelled);
            sink.emit(Map.of("type", "scene_done", "order", order, "sceneId", sceneId,
                    "version", result.version(), "warnings", result.warnings()));
            sink.emit(Map.of("type", "done", "version", result.version()));
        });
    }

    public record Regenerated(Stage.Scene scene, long version, List<String> warnings) {
    }

    /**
     * 单页重生成(调用方已授权):以库中当前的这一页为基准。scope=content 整页内容重做(讲稿清空、排版覆盖归零),
     * scope=speech 只重讲稿;按 id 定位落库。
     */
    public Regenerated regenerateScene(String apiKey, long courseId, long coursewareId, String sceneId, String scope,
                                       String rawInstruction, Consumer<String> trace, BooleanSupplier cancelled) {
        Stage stage = coursewares.getInternal(courseId, coursewareId);
        int index = sceneIndex(stage, sceneId);
        Stage.Scene scene = stage.scenes().get(index);
        if ("video".equals(scene.type()) && "content".equals(scope)) {
            throw new BadRequestException("视频页的内容是教师上传的视频,只能重写讲稿");
        }
        String instruction = rawInstruction == null || rawInstruction.isBlank() ? null : rawInstruction.strip();
        int order = index + 1;
        int total = stage.scenes().size();
        String nextTitle = index + 1 < total ? stage.scenes().get(index + 1).title() : null;
        List<String> warnings = new ArrayList<>();
        Stage.Scene updated;
        if ("content".equals(scope)) {
            Stage.Interactive interactive = scene.interactive();
            SceneBrief brief = new SceneBrief(scene.title(), scene.type(), scene.preset(), scene.summary(), List.of(),
                    interactive == null ? null : interactive.widgetType(),
                    interactive == null ? null : interactive.widgetOutline(), List.of(), null);
            List<Block> existing = "interactive".equals(scene.type()) ? null : scene.blocks();
            SceneGenerator.Generated content = scenes.generateContent(apiKey, stage.title(), brief,
                    order, total, scene.id(), List.of(), existing, instruction, trace);
            warnings.addAll(content.warnings());
            updated = content.scene();
        } else {
            SceneGenerator.Generated spoken = scenes.generateSpeech(apiKey, stage.title(),
                    scene, order, total, nextTitle, trace);
            warnings.addAll(spoken.warnings());
            updated = spoken.scene();
        }
        CoursewareApplicationService.VersionedStage applied = edits.replaceSceneInternal(courseId, coursewareId, sceneId, updated);
        return new Regenerated(applied.stage().scenes().get(index), applied.version(), warnings);
    }

    private static int sceneIndex(Stage stage, String sceneId) {
        for (int i = 0; i < stage.scenes().size(); i++) {
            if (stage.scenes().get(i).id().equals(sceneId)) {
                return i;
            }
        }
        throw new NotFoundException("页面不存在");
    }

    // ---- 素材 ----

    private static List<CatalogEntry> catalog(List<Bundle> materials) {
        record Pair(Bundle bundle, BundleImage image, int bundleOrder) {
        }
        List<Pair> pairs = new ArrayList<>();
        for (int b = 0; b < materials.size(); b++) {
            for (BundleImage image : materials.get(b).images()) {
                pairs.add(new Pair(materials.get(b), image, b));
            }
        }
        pairs.sort(Comparator.comparingInt((Pair p) -> -p.image().visionPriority())
                .thenComparingInt(Pair::bundleOrder)
                .thenComparingInt(p -> p.image().pageNumber()));
        List<CatalogEntry> entries = new ArrayList<>();
        for (int i = 0; i < pairs.size(); i++) {
            entries.add(new CatalogEntry("img_" + (i + 1), pairs.get(i).bundle(), pairs.get(i).image()));
        }
        return entries;
    }

    private static SceneGenerator.ImageInput toImageInput(CatalogEntry entry) {
        BundleImage image = entry.image();
        return new SceneGenerator.ImageInput(image.objectKey(), image.description(), image.width(), image.height(),
                materialSource(image), false);
    }

    private static String materialSource(BundleImage image) {
        String source = "素材「" + image.sourceDocumentName() + "」";
        return image.pageNumber() > 0 ? source + " 第 " + image.pageNumber() + " 页" : source;
    }

    private static String materialText(List<Bundle> materials) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < materials.size(); i++) {
            Bundle bundle = materials.get(i);
            if (bundle.text() == null || bundle.text().isBlank()) {
                continue;
            }
            parts.add(materials.size() == 1 ? bundle.text()
                    : "# 素材 " + (i + 1) + ":" + bundle.name() + "\n\n" + bundle.text());
        }
        return String.join("\n\n", parts);
    }

    private static Map<String, Object> trace(int order, String message) {
        return Map.of("type", "trace", "order", order, "message", message);
    }
}
