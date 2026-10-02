package cn.utcy.teaching.courseware.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 编辑操作的应用内核(纯函数):对一份课件按序应用 {@link EditOp},逐操作校验,被改动的页统一清洗讲稿动作
 * 与排版覆盖并按保存端同一校验器整页校验;任一错误即整批拒绝(抛 {@link Rejected},不改动任何东西)。
 * 教师手工编辑(逐操作端点)与撤销 / 重做都走这一条路径——一套语义,一处校验。
 */
public final class StageCommands {

    public static final int MAX_SCENES = 20;
    private static final String MISSING_SCENE = "这一页已不存在,请刷新后重试";
    private static final String MISSING_BLOCK = "这个内容块已不存在,请刷新后重试";

    private StageCommands() {
    }

    public static final class Rejected extends RuntimeException {
        private final List<String> errors;

        public Rejected(List<String> errors) {
            super(String.join(";", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() {
            return errors;
        }
    }

    /** 编辑结果含编辑界面不会产生的内容(前端缺陷或绕过界面的请求);明细只进日志 */
    public static final class Malformed extends RuntimeException {
        public Malformed(List<String> details) {
            super(String.join(";", details));
        }
    }

    public static Stage apply(Stage stage, List<EditOp> ops) {
        List<Stage.Scene> scenes = new ArrayList<>(stage.scenes());
        String title = stage.title();
        List<String> errors = new ArrayList<>();
        Set<String> touchedIds = new LinkedHashSet<>();

        for (EditOp op : ops) {
            switch (op) {
                case EditOp.UpdateStageMeta o -> {
                    if (isBlank(o.title())) {
                        errors.add("课件标题不能为空");
                        continue;
                    }
                    title = o.title().trim();
                }
                case EditOp.AddScene o -> {
                    List<String> opErrors = validateAddScene(o, scenes.size());
                    if (!opErrors.isEmpty()) {
                        errors.addAll(opErrors);
                        continue;
                    }
                    int at = clamp(o.index(), 0, scenes.size());
                    Stage.Scene scene = new Stage.Scene(newSceneId(scenes), o.type(), o.title().trim(), o.preset(),
                            trimToNull(o.summary()), List.copyOf(o.blocks()), List.of(), List.of(), null);
                    scenes.add(at, scene);
                    touchedIds.add(scene.id());
                }
                case EditOp.AddInteractiveScene o -> {
                    List<String> opErrors = new ArrayList<>();
                    if (scenes.size() + 1 > MAX_SCENES) {
                        opErrors.add("一份课件最多 " + MAX_SCENES + " 页");
                    }
                    if (isBlank(o.title())) {
                        opErrors.add("请填写页面标题");
                    }
                    if (o.widgetType() != null && !Stage.WIDGET_TYPES.contains(o.widgetType())) {
                        opErrors.add("组件类型不正确");
                    }
                    InteractiveHtml.Prepared prepared = InteractiveHtml.prepare(o.html());
                    prepared.problems().forEach(problem -> opErrors.add(problem.forTeacher()));
                    if (!opErrors.isEmpty()) {
                        errors.addAll(opErrors);
                        continue;
                    }
                    int at = clamp(o.index(), 0, scenes.size());
                    Stage.Scene scene = new Stage.Scene(newSceneId(scenes), "interactive", o.title().trim(), "standard",
                            trimToNull(o.summary()), List.of(), List.of(), List.of(),
                            new Stage.Interactive(prepared.html(), o.widgetType(), null));
                    scenes.add(at, scene);
                    touchedIds.add(scene.id());
                }
                case EditOp.AddVideoScene o -> {
                    List<String> opErrors = new ArrayList<>();
                    if (scenes.size() + 1 > MAX_SCENES) {
                        opErrors.add("一份课件最多 " + MAX_SCENES + " 页");
                    }
                    if (isBlank(o.title())) {
                        opErrors.add("请填写页面标题");
                    }
                    if (isBlank(o.src())) {
                        opErrors.add("请先上传视频");
                    }
                    if (!opErrors.isEmpty()) {
                        errors.addAll(opErrors);
                        continue;
                    }
                    int at = clamp(o.index(), 0, scenes.size());
                    Stage.Scene scene = new Stage.Scene(newSceneId(scenes), "video", o.title().trim(), "standard",
                            trimToNull(o.summary()), List.of(), List.of(), List.of(), null, new Stage.Video(o.src().trim()));
                    scenes.add(at, scene);
                    touchedIds.add(scene.id());
                }
                case EditOp.SetVideo o -> {
                    int idx = sceneIndex(scenes, o.sceneId());
                    if (idx < 0) {
                        errors.add(MISSING_SCENE);
                        continue;
                    }
                    Stage.Scene scene = scenes.get(idx);
                    if (!"video".equals(scene.type())) {
                        errors.add("这一页不是视频页");
                        continue;
                    }
                    if (isBlank(o.src())) {
                        errors.add("请先上传视频");
                        continue;
                    }
                    scenes.set(idx, new Stage.Scene(scene.id(), scene.type(), scene.title(), scene.preset(), scene.summary(),
                            scene.blocks(), scene.speech(), scene.layouts(), null, new Stage.Video(o.src().trim())));
                    touchedIds.add(scene.id());
                }
                case EditOp.SetInteractiveHtml o -> {
                    int idx = sceneIndex(scenes, o.sceneId());
                    if (idx < 0) {
                        errors.add(MISSING_SCENE);
                        continue;
                    }
                    Stage.Scene scene = scenes.get(idx);
                    if (!"interactive".equals(scene.type()) || scene.interactive() == null) {
                        errors.add("这一页不是交互页");
                        continue;
                    }
                    InteractiveHtml.Prepared prepared = InteractiveHtml.prepare(o.html());
                    if (!prepared.problems().isEmpty()) {
                        prepared.problems().forEach(problem -> errors.add(problem.forTeacher()));
                        continue;
                    }
                    scenes.set(idx, new Stage.Scene(scene.id(), scene.type(), scene.title(), scene.preset(), scene.summary(),
                            scene.blocks(), scene.speech(), scene.layouts(), new Stage.Interactive(prepared.html(),
                            scene.interactive().widgetType(), scene.interactive().widgetOutline())));
                    touchedIds.add(scene.id());
                }
                case EditOp.DeleteScene o -> {
                    int idx = sceneIndex(scenes, o.sceneId());
                    if (idx < 0) {
                        errors.add(MISSING_SCENE);
                        continue;
                    }
                    Stage.Scene removed = scenes.remove(idx);
                    touchedIds.remove(removed.id());
                }
                case EditOp.MoveScene o -> {
                    int idx = sceneIndex(scenes, o.sceneId());
                    if (idx < 0) {
                        errors.add(MISSING_SCENE);
                        continue;
                    }
                    Stage.Scene scene = scenes.remove(idx);
                    int at = clamp(o.toIndex(), 0, scenes.size());
                    scenes.add(at, scene);
                }
                case EditOp.UpdateSceneMeta o -> {
                    int idx = sceneIndex(scenes, o.sceneId());
                    if (idx < 0) {
                        errors.add(MISSING_SCENE);
                        continue;
                    }
                    Stage.Scene scene = scenes.get(idx);
                    if (o.title() == null && o.preset() == null && o.summary() == null) {
                        errors.add("没有要修改的内容");
                        continue;
                    }
                    if (o.title() != null && isBlank(o.title())) {
                        errors.add("页面标题不能为空");
                        continue;
                    }
                    String preset = o.preset() == null ? scene.preset() : o.preset();
                    if (validatePreset(scene.type(), preset) != null) {
                        errors.add("这个布局不适用于这类页面");
                        continue;
                    }
                    scenes.set(idx, new Stage.Scene(scene.id(), scene.type(),
                            o.title() == null ? scene.title() : o.title().trim(), preset,
                            o.summary() == null ? scene.summary() : trimToNull(o.summary()),
                            scene.blocks(), scene.speech(), scene.layouts(), scene.interactive(), scene.video()));
                }
                case EditOp.AddBlock o -> blockOp(scenes, o.sceneId(), errors, (idx, scene) -> {
                    if (o.block() == null) {
                        errors.add("内容块不能为空");
                        return;
                    }
                    List<Block> blocks = new ArrayList<>(scene.blocks());
                    int at = clamp(o.index(), 0, blocks.size());
                    blocks.add(at, o.block());
                    scenes.set(idx, withBlocks(scene, blocks));
                    touchedIds.add(scene.id());
                });
                case EditOp.ReplaceBlock o -> blockOp(scenes, o.sceneId(), errors, (idx, scene) -> {
                    if (o.block() == null) {
                        errors.add("内容块不能为空");
                        return;
                    }
                    int bi = blockIndex(scene.blocks(), o.blockId());
                    if (bi < 0) {
                        errors.add(MISSING_BLOCK);
                        return;
                    }
                    List<Block> blocks = new ArrayList<>(scene.blocks());
                    blocks.set(bi, o.block());
                    // 块换了 id:它的排版覆盖跟着改名(内容变了但位置与字号档是教师定的)
                    List<Stage.BlockLayout> layouts = Objects.equals(o.blockId(), o.block().id())
                            ? scene.layoutsOrEmpty()
                            : renameLayout(scene.layoutsOrEmpty(), o.blockId(), o.block().id());
                    scenes.set(idx, withBlocks(scene, blocks, layouts));
                    touchedIds.add(scene.id());
                });
                case EditOp.DeleteBlock o -> blockOp(scenes, o.sceneId(), errors, (idx, scene) -> {
                    int bi = blockIndex(scene.blocks(), o.blockId());
                    if (bi < 0) {
                        errors.add(MISSING_BLOCK);
                        return;
                    }
                    List<Block> blocks = new ArrayList<>(scene.blocks());
                    Block removed = blocks.remove(bi);
                    scenes.set(idx, withBlocks(scene, blocks, withoutLayout(scene.layoutsOrEmpty(), o.blockId())));
                    touchedIds.add(scene.id());
                });
                case EditOp.MoveBlock o -> blockOp(scenes, o.sceneId(), errors, (idx, scene) -> {
                    int bi = blockIndex(scene.blocks(), o.blockId());
                    if (bi < 0) {
                        errors.add(MISSING_BLOCK);
                        return;
                    }
                    List<Block> blocks = new ArrayList<>(scene.blocks());
                    Block block = blocks.remove(bi);
                    int at = clamp(o.toIndex(), 0, blocks.size());
                    blocks.add(at, block);
                    scenes.set(idx, withBlocks(scene, blocks));
                    touchedIds.add(scene.id());
                });
                case EditOp.SetSpeech o -> {
                    int idx = sceneIndex(scenes, o.sceneId());
                    if (idx < 0) {
                        errors.add(MISSING_SCENE);
                        continue;
                    }
                    Stage.Scene scene = scenes.get(idx);
                    if (o.speech() == null || o.speech().isEmpty()) {
                        errors.add("讲稿至少要有一段");
                        continue;
                    }
                    if (o.speech().stream().anyMatch(s -> s == null || isBlank(s.text()))) {
                        errors.add("讲稿的每一段都要有文字");
                        continue;
                    }
                    // 文本未变的段保留原音频(调用方不给 audioPath)
                    List<Stage.SpeechSegment> merged = new ArrayList<>();
                    for (EditOp.SpeechSegmentInput segment : o.speech()) {
                        String audio = scene.speech().stream()
                                .filter(old -> old.text().equals(segment.text()) && old.audioPath() != null)
                                .map(Stage.SpeechSegment::audioPath)
                                .findFirst().orElse(null);
                        merged.add(new Stage.SpeechSegment(segment.text(),
                                segment.actions() == null ? List.of() : segment.actions(), audio));
                    }
                    scenes.set(idx, new Stage.Scene(scene.id(), scene.type(), scene.title(), scene.preset(),
                            scene.summary(), scene.blocks(), List.copyOf(merged), scene.layouts(), scene.interactive(), scene.video()));
                    touchedIds.add(scene.id());
                }
                case EditOp.PinBlock o -> blockOp(scenes, o.sceneId(), errors, (idx, scene) -> {
                    int bi = blockIndex(scene.blocks(), o.blockId());
                    if (bi < 0) {
                        errors.add(MISSING_BLOCK);
                        return;
                    }
                    if (o.x() == null || o.y() == null || o.w() == null) {
                        errors.add("缺少钉住的位置");
                        return;
                    }
                    Stage.PinFrame frame = new Stage.PinFrame(o.x(), o.y(), o.w(), o.h());
                    Stage.BlockLayout existing = findLayout(scene.layoutsOrEmpty(), o.blockId());
                    Stage.BlockLayout next = new Stage.BlockLayout(o.blockId(), frame,
                            existing == null ? null : existing.size());
                    scenes.set(idx, withBlocks(scene, scene.blocks(), putLayout(scene.layoutsOrEmpty(), next)));
                    touchedIds.add(scene.id());
                });
                case EditOp.UnpinBlock o -> blockOp(scenes, o.sceneId(), errors, (idx, scene) -> {
                    Stage.BlockLayout existing = findLayout(scene.layoutsOrEmpty(), o.blockId());
                    if (existing == null || existing.frame() == null) {
                        errors.add("这个内容块没有被钉住");
                        return;
                    }
                    List<Stage.BlockLayout> layouts = existing.size() == null
                            ? withoutLayout(scene.layoutsOrEmpty(), o.blockId())
                            : putLayout(scene.layoutsOrEmpty(), new Stage.BlockLayout(o.blockId(), null, existing.size()));
                    scenes.set(idx, withBlocks(scene, scene.blocks(), layouts));
                    touchedIds.add(scene.id());
                });
                case EditOp.SetBlockSize o -> blockOp(scenes, o.sceneId(), errors, (idx, scene) -> {
                    int bi = blockIndex(scene.blocks(), o.blockId());
                    if (bi < 0) {
                        errors.add(MISSING_BLOCK);
                        return;
                    }
                    if (o.size() == null || !("normal".equals(o.size()) || Stage.BLOCK_SIZES.contains(o.size()))) {
                        errors.add("字号档不正确");
                        return;
                    }
                    Stage.BlockLayout existing = findLayout(scene.layoutsOrEmpty(), o.blockId());
                    Stage.PinFrame frame = existing == null ? null : existing.frame();
                    String size = "normal".equals(o.size()) ? null : o.size();
                    List<Stage.BlockLayout> layouts = frame == null && size == null
                            ? withoutLayout(scene.layoutsOrEmpty(), o.blockId())
                            : putLayout(scene.layoutsOrEmpty(), new Stage.BlockLayout(o.blockId(), frame, size));
                    scenes.set(idx, withBlocks(scene, scene.blocks(), layouts));
                    touchedIds.add(scene.id());
                });
            }
        }
        if (!errors.isEmpty()) {
            throw new Rejected(errors);
        }

        List<String> sceneErrors = new ArrayList<>();
        List<String> malformed = new ArrayList<>();
        for (int i = 0; i < scenes.size(); i++) {
            Stage.Scene scene = scenes.get(i);
            if (!touchedIds.contains(scene.id())) {
                continue;
            }
            StageNormalizer.NormalizeResult<List<Block>> normalized =
                    StageNormalizer.normalizeBlocks(scene.blocks());
            StageNormalizer.NormalizeResult<List<Stage.SpeechSegment>> cleaned =
                    StageNormalizer.normalizeSpeech(scene.speech(), scene.type(), normalized.value());
            StageNormalizer.NormalizeResult<List<Stage.BlockLayout>> layouts =
                    StageNormalizer.normalizeLayouts(scene.layouts(), normalized.value());
            scene = new Stage.Scene(scene.id(), scene.type(), scene.title(), scene.preset(), scene.summary(),
                    normalized.value(), cleaned.value(), layouts.value(), scene.interactive(), scene.video());
            scenes.set(i, scene);
            String page = "第 " + (i + 1) + " 页「" + scene.title() + "」";
            for (StageProblem problem : StageValidator.validateScene(scene)) {
                if (problem.teacherFacing()) {
                    sceneErrors.add(page + ":" + problem.forTeacher());
                } else {
                    malformed.add(page + ":" + problem.forModel());
                }
            }
        }
        if (!malformed.isEmpty()) {
            throw new Malformed(malformed);
        }
        if (!sceneErrors.isEmpty()) {
            throw new Rejected(sceneErrors);
        }
        return new Stage(title, stage.theme(), List.copyOf(scenes));
    }

    private static List<String> validateAddScene(EditOp.AddScene o, int currentCount) {
        List<String> errors = new ArrayList<>();
        if (currentCount + 1 > MAX_SCENES) {
            errors.add("一份课件最多 " + MAX_SCENES + " 页");
        }
        if (isBlank(o.title())) {
            errors.add("请填写页面标题");
        }
        if ("interactive".equals(o.type())) {
            errors.add("页面类型不正确");
        } else if ("video".equals(o.type())) {
            errors.add("页面类型不正确");
        } else if (o.type() == null || !Stage.SCENE_TYPES.contains(o.type())) {
            errors.add("页面类型不正确");
        } else {
            if (validatePreset(o.type(), o.preset()) != null) {
                errors.add("这个布局不适用于这类页面");
            }
        }
        if (o.blocks() == null || o.blocks().isEmpty()) {
            errors.add("新页面至少要有一个内容块");
        } else if (o.blocks().stream().anyMatch(Objects::isNull)) {
            errors.add("内容块不能为空");
        }
        return errors;
    }

    public static String validatePreset(String sceneType, String preset) {
        if (preset == null || !Stage.PRESET_NAMES.contains(preset)) {
            return "布局预设 \"" + preset + "\" 非法";
        }
        if ("quiz".equals(sceneType) && !"quiz".equals(preset)) {
            return "quiz 页的预设固定为 \"quiz\"";
        }
        if (Stage.isBlockless(sceneType) && !"standard".equals(preset)) {
            return sceneType + " 页的预设固定为 \"standard\"";
        }
        if ("content".equals(sceneType) && "quiz".equals(preset)) {
            return "content 页不能使用 \"quiz\" 预设";
        }
        return null;
    }

    private static void blockOp(List<Stage.Scene> scenes, String sceneId, List<String> errors,
                                BlockMutation mutation) {
        int idx = sceneIndex(scenes, sceneId);
        if (idx < 0) {
            errors.add(MISSING_SCENE);
            return;
        }
        Stage.Scene scene = scenes.get(idx);
        if (Stage.isBlockless(scene.type())) {
            errors.add(sceneTypeLabel(scene.type()) + "没有内容块");
            return;
        }
        mutation.run(idx, scene);
    }

    @FunctionalInterface
    private interface BlockMutation {
        void run(int sceneIndex, Stage.Scene scene);
    }

    public static String newSceneId(List<Stage.Scene> scenes) {
        int max = 0;
        for (Stage.Scene scene : scenes) {
            String id = scene.id();
            if (id != null && id.startsWith("scene-")) {
                try {
                    max = Math.max(max, Integer.parseInt(id.substring("scene-".length())));
                } catch (NumberFormatException ignored) {
                    // 非数字后缀的 id 不参与编号
                }
            }
        }
        return "scene-" + (max + 1);
    }

    public static int sceneIndex(List<Stage.Scene> scenes, String sceneId) {
        for (int i = 0; i < scenes.size(); i++) {
            if (Objects.equals(scenes.get(i).id(), sceneId)) {
                return i;
            }
        }
        return -1;
    }

    private static int blockIndex(List<Block> blocks, String blockId) {
        for (int i = 0; i < blocks.size(); i++) {
            if (Objects.equals(blocks.get(i).id(), blockId)) {
                return i;
            }
        }
        return -1;
    }

    private static Stage.Scene withBlocks(Stage.Scene scene, List<Block> blocks) {
        return withBlocks(scene, blocks, scene.layoutsOrEmpty());
    }

    private static Stage.Scene withBlocks(Stage.Scene scene, List<Block> blocks, List<Stage.BlockLayout> layouts) {
        return new Stage.Scene(scene.id(), scene.type(), scene.title(), scene.preset(), scene.summary(),
                List.copyOf(blocks), scene.speech(), List.copyOf(layouts), scene.interactive(), scene.video());
    }

    private static Stage.BlockLayout findLayout(List<Stage.BlockLayout> layouts, String blockId) {
        for (Stage.BlockLayout layout : layouts) {
            if (layout != null && Objects.equals(layout.blockId(), blockId)) {
                return layout;
            }
        }
        return null;
    }

    private static List<Stage.BlockLayout> putLayout(List<Stage.BlockLayout> layouts, Stage.BlockLayout next) {
        List<Stage.BlockLayout> out = new ArrayList<>(withoutLayout(layouts, next.blockId()));
        out.add(next);
        return out;
    }

    private static List<Stage.BlockLayout> withoutLayout(List<Stage.BlockLayout> layouts, String blockId) {
        List<Stage.BlockLayout> out = new ArrayList<>();
        for (Stage.BlockLayout layout : layouts) {
            if (layout != null && !Objects.equals(layout.blockId(), blockId)) {
                out.add(layout);
            }
        }
        return out;
    }

    private static List<Stage.BlockLayout> renameLayout(List<Stage.BlockLayout> layouts, String from, String to) {
        List<Stage.BlockLayout> out = new ArrayList<>();
        for (Stage.BlockLayout layout : layouts) {
            if (layout == null) {
                continue;
            }
            out.add(Objects.equals(layout.blockId(), from)
                    ? new Stage.BlockLayout(to, layout.frame(), layout.size()) : layout);
        }
        return out;
    }

    private static String sceneTypeLabel(String type) {
        return switch (type) {
            case "quiz" -> "测验页";
            case "interactive" -> "交互页";
            case "video" -> "视频页";
            default -> "讲解页";
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String trimToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
