package cn.utcy.teaching.courseware.domain;

import cn.utcy.teaching.courseware.domain.layout.Theme;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 语义校验 —— schema 管形状,这里管 schema 表达不了的业务不变量。生成与教师编辑共用这一套规则,空列表即通过。
 */
public final class StageValidator {

    public static List<StageProblem> validateBlocks(List<Block> blocks, String sceneType) {
        List<StageProblem> errors = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (int index = 0; index < blocks.size(); index++) {
            Block block = blocks.get(index);
            String path = "blocks[" + index + "](" + block.id() + ")";
            String where = "第 " + (index + 1) + " 个内容块";

            if (seenIds.contains(block.id())) {
                errors.add(StageProblem.structural(path + ": 块 id \"" + block.id() + "\" 重复"));
            }
            seenIds.add(block.id());
            // 生成期就拦超长 id(学习记录列宽 64):放到 validateBlocks 让 refine 反馈重试,
            // 而不是等终检/保存才发现
            if (block.id() != null && block.id().length() > 64) {
                errors.add(StageProblem.structural(path + ": 块 id 过长(最多 64 字符)"));
            }

            if (block instanceof Block.QuizChoice quiz) {
                if (!"quiz".equals(sceneType)) {
                    errors.add(StageProblem.structural(path + ": quiz_choice 块只允许出现在 quiz 页"));
                }
                Set<String> labels = new LinkedHashSet<>();
                for (Block.QuizOption option : quiz.options()) {
                    labels.add(option.label());
                }
                if (labels.size() != quiz.options().size()) {
                    errors.add(StageProblem.structural(path + ": 选项 label 重复"));
                }
                if (quiz.answer().isEmpty()) {
                    errors.add(StageProblem.structural(path + ": answer 不能为空"));
                }
                for (String a : quiz.answer()) {
                    if (!labels.contains(a)) {
                        errors.add(StageProblem.structural(path + ": answer \"" + a + "\" 不在选项 label 中"));
                    }
                }
                if (!quiz.multiple() && quiz.answer().size() > 1) {
                    errors.add(StageProblem.structural(path + ": 单选题(multiple=false)却有 " + quiz.answer().size() + " 个答案"));
                }
                continue;
            }

            if (block instanceof Block.Columns columns) {
                if (columns.children().size() < 2 || columns.children().size() > 3) {
                    errors.add(StageProblem.structural(path + ": columns 只支持 2~3 列,当前 " + columns.children().size() + " 列"));
                }
                if (columns.ratio() != null && columns.ratio().size() != columns.children().size()) {
                    errors.add(StageProblem.structural(path + ": ratio 长度 " + columns.ratio().size()
                            + " 与列数 " + columns.children().size() + " 不一致"));
                }
                for (int ci = 0; ci < columns.children().size(); ci++) {
                    List<Block> col = columns.children().get(ci);
                    if (col.isEmpty()) {
                        errors.add(StageProblem.structural(path + ": 第 " + (ci + 1) + " 列为空"));
                    }
                    for (int bi = 0; bi < col.size(); bi++) {
                        Block child = col.get(bi);
                        // Java 类型上 children 元素是 Block,运行时再挡一次嵌套容器
                        if (child instanceof Block.Columns || child instanceof Block.QuizChoice) {
                            errors.add(StageProblem.structural(path + ".children[" + ci + "][" + bi + "]: columns 内不允许 "
                                    + child.type()));
                            continue;
                        }
                        if (seenIds.contains(child.id())) {
                            errors.add(StageProblem.structural(path + ".children[" + ci + "][" + bi + "]: 块 id \""
                                    + child.id() + "\" 重复"));
                        }
                        seenIds.add(child.id());
                        checkLeaf(child, path + ".children[" + ci + "][" + bi + "](" + child.id() + ")",
                                where + "第 " + (ci + 1) + " 栏", errors);
                    }
                }
                continue;
            }

            checkLeaf(block, path, where, errors);
        }

        return errors;
    }

    private static void checkLeaf(Block block, String path, String where, List<StageProblem> errors) {
        switch (block) {
            case Block.Heading heading -> {
                if (heading.text().contains("\n")) {
                    errors.add(StageProblem.structural(path + ": heading 文本不允许换行"));
                }
            }
            case Block.Paragraph paragraph -> {
                // 多行 paragraph 几乎总是被塞进了代码/列表 —— 这是 LLM 最常见的类型误用
                if (paragraph.text().contains("\n")) {
                    errors.add(new StageProblem(path + ": paragraph 不允许多行文本 —— 代码必须用 code 块,"
                            + "多段文字拆成多个 paragraph,列表用 bullets",
                            where + ":段落里不能换行,要分段请再加一个段落"));
                }
            }
            case Block.Bullets bullets -> {
                if (bullets.items().isEmpty()) {
                    errors.add(StageProblem.structural(path + ": bullets 至少需要一条条目"));
                }
                for (int i = 0; i < bullets.items().size(); i++) {
                    if (bullets.items().get(i).text().contains("\n")) {
                        errors.add(StageProblem.structural(path + ": 第 " + (i + 1)
                                + " 条条目含换行 —— 代码用 code 块,补充说明用 sub 子条目"));
                    }
                }
            }
            case Block.Table table -> {
                for (int i = 0; i < table.rows().size(); i++) {
                    List<String> row = table.rows().get(i);
                    if (row.size() != table.headers().size()) {
                        errors.add(StageProblem.structural(path + ": 表格第 " + (i + 1) + " 行有 " + row.size()
                                + " 列,与表头 " + table.headers().size() + " 列不一致"));
                    }
                }
            }
            case Block.Chart chart -> {
                if (chart.series().isEmpty()) {
                    errors.add(StageProblem.structural(path + ": chart 至少需要一个 series"));
                }
                for (int i = 0; i < chart.series().size(); i++) {
                    Block.ChartSeries s = chart.series().get(i);
                    if (s.data().size() != chart.categories().size()) {
                        errors.add(StageProblem.structural(path + ": series[" + i + "] \"" + s.name() + "\" 有 "
                                + s.data().size() + " 个数据点,与 categories "
                                + chart.categories().size() + " 项不一致"));
                    }
                }
            }
            case Block.Emphasis emphasis -> {
                if (isBlankOrNull(emphasis.text())) {
                    errors.add(new StageProblem(path + ": emphasis 的 text 不能为空", where + ":强调文字不能为空"));
                }
                if (emphasis.text() != null && emphasis.text().contains("\n")) {
                    errors.add(StageProblem.structural(path + ": emphasis 文本不允许换行(一句话强调)"));
                }
            }
            case Block.Image image -> {
                if (isBlankOrNull(image.src())) {
                    errors.add(StageProblem.structural(path + ": image 必须提供 src(图片对象键)"));
                }
                if (image.width() < 0 || image.height() < 0) {
                    errors.add(StageProblem.structural(path + ": image 的 width/height 不能为负"));
                }
            }
            default -> {
            }
        }
    }

    /** 收集页面上全部块 id → bullets 条目数(非 bullets 记 0),供动作目标校验 */
    public static Map<String, Integer> collectBlockIds(List<Block> blocks) {
        Map<String, Integer> ids = new LinkedHashMap<>();
        for (Block block : blocks) {
            addBlockId(ids, block);
            if (block instanceof Block.Columns columns) {
                for (List<Block> col : columns.children()) {
                    for (Block child : col) {
                        addBlockId(ids, child);
                    }
                }
            }
        }
        return ids;
    }

    private static void addBlockId(Map<String, Integer> ids, Block block) {
        ids.put(block.id(), block instanceof Block.Bullets bullets ? bullets.items().size() : 0);
    }

    public static String validateActionTarget(String target, Map<String, Integer> blockIds) {
        int hashIndex = target.indexOf('#');
        String blockId = hashIndex == -1 ? target : target.substring(0, hashIndex);
        Integer itemCount = blockIds.get(blockId);
        if (itemCount == null) {
            return "目标块 \"" + blockId + "\" 不存在";
        }
        if (hashIndex != -1) {
            Integer n = parseIntOrNull(target.substring(hashIndex + 1));
            if (n == null || n < 1) {
                return "目标 \"" + target + "\" 的条目序号无效(应为 1 起的整数)";
            }
            if (itemCount == 0) {
                return "目标块 \"" + blockId + "\" 不是 bullets,不支持 #条目定位";
            }
            if (n > itemCount) {
                return "目标 \"" + target + "\" 超出条目数(共 " + itemCount + " 条)";
            }
        }
        return null;
    }

    /** 讲稿是念出来的:LaTeX 定界 / 命令与 markdown 记号一律不许 */
    private static final java.util.regex.Pattern NOT_SPOKEN =
            java.util.regex.Pattern.compile("[$`]|\\*\\*|\\\\[a-zA-Z]+\\{|\\\\\\(|\\\\\\[");

    public static List<StageProblem> validateSpeech(List<Stage.SpeechSegment> speech, String sceneType,
                                                    List<Block> blocks) {
        List<StageProblem> errors = new ArrayList<>();
        Map<String, Integer> blockIds = collectBlockIds(blocks);

        for (int si = 0; si < speech.size(); si++) {
            Stage.SpeechSegment segment = speech.get(si);
            String where = "第 " + (si + 1) + " 段讲稿";
            if (segment.text().trim().isEmpty()) {
                errors.add(new StageProblem("speech[" + si + "]: 讲稿文本为空", where + "没有文字"));
            }
            if (NOT_SPOKEN.matcher(segment.text()).find()) {
                errors.add(new StageProblem(
                        "speech[" + si + "]: 讲稿是念出来的,不能含 LaTeX 或 markdown 记号——公式用口语表述(如\"F 等于 m 乘以 a\")",
                        where + "会被朗读,不能写公式或排版符号($、**、` 等),请改成口语,比如「F 等于 m 乘以 a」"));
            }
            for (int ai = 0; ai < segment.actions().size(); ai++) {
                String path = "speech[" + si + "].actions[" + ai + "]";
                validateAction(segment.actions().get(ai), sceneType, blockIds, path, errors);
            }
        }

        return errors;
    }

    private static void validateAction(Action action, String sceneType,
                                       Map<String, Integer> blockIds, String path,
                                       List<StageProblem> errors) {
        if (action instanceof Action.Pause pause) {
            if (pause.ms() < 200 || pause.ms() > 5000) {
                errors.add(StageProblem.structural(path + ": pause.ms=" + pause.ms() + " 超出 [200, 5000]"));
            }
            return;
        }
        if (Stage.isBlockless(sceneType)) {
            errors.add(StageProblem.structural(path + ": " + sceneType + " 页没有内容块,不支持 " + action.type() + "(只允许 pause)"));
            return;
        }
        String err = validateActionTarget(actionTarget(action), blockIds);
        if (err != null) {
            errors.add(StageProblem.structural(path + ": " + err));
        }
    }

    static String actionTarget(Action action) {
        return switch (action) {
            case Action.Highlight a -> a.target();
            case Action.Reveal a -> a.target();
            case Action.Pause a -> null;
        };
    }

    public static List<StageProblem> validateLayouts(List<Stage.BlockLayout> layouts, String sceneType,
                                                     List<Block> blocks) {
        List<StageProblem> errors = new ArrayList<>();
        if (layouts == null || layouts.isEmpty()) return errors;
        if (Stage.isBlockless(sceneType)) {
            errors.add(StageProblem.structural(sceneType + " 页没有内容块,不应有排版覆盖"));
            return errors;
        }
        Map<String, Block> topLevel = new LinkedHashMap<>();
        for (Block block : blocks) topLevel.put(block.id(), block);
        Set<String> seen = new HashSet<>();
        double canvasWidth = Theme.CANVAS_WIDTH;
        double canvasHeight = Theme.CANVAS_HEIGHT;

        for (int i = 0; i < layouts.size(); i++) {
            Stage.BlockLayout layout = layouts.get(i);
            if (layout == null) {
                errors.add(StageProblem.structural("layouts[" + i + "]: 排版覆盖为空"));
                continue;
            }
            String path = "layouts[" + i + "](" + layout.blockId() + ")";
            Block block = layout.blockId() == null ? null : topLevel.get(layout.blockId());
            if (block == null) {
                errors.add(StageProblem.structural(path + ": 排版覆盖引用的块不存在(只能引用页面顶层块,columns 内的子块不能单独覆盖)"));
                continue;
            }
            if (seen.contains(layout.blockId())) {
                errors.add(StageProblem.structural(path + ": 块 \"" + layout.blockId() + "\" 的排版覆盖重复"));
            }
            seen.add(layout.blockId());
            if (layout.frame() == null && layout.size() == null) {
                errors.add(StageProblem.structural(path + ": 排版覆盖至少要给 frame 或 size 之一"));
            }
            if (layout.size() != null && !Stage.BLOCK_SIZES.contains(layout.size())) {
                errors.add(StageProblem.structural(path + ": 字号档 \"" + layout.size() + "\" 非法(small / large / xlarge)"));
            }
            Stage.PinFrame frame = layout.frame();
            if (frame == null) continue;
            if (block instanceof Block.Columns) {
                errors.add(StageProblem.structural(path + ": columns 不能钉住(分栏的位置由引擎排;可以只改字号档)"));
                continue;
            }
            if (!Double.isFinite(frame.x()) || !Double.isFinite(frame.y()) || !Double.isFinite(frame.w())) {
                errors.add(StageProblem.structural(path + ": 钉住帧的 x / y / w 必须是数字"));
                continue;
            }
            if (frame.x() < 0 || frame.y() < 0 || frame.x() + frame.w() > canvasWidth || frame.y() >= canvasHeight) {
                errors.add(StageProblem.structural(path + ": 钉住帧超出页面(页面 " + (long) canvasWidth + "×" + (long) canvasHeight + ")"));
            }
            if (frame.w() < Stage.MIN_PIN_WIDTH) {
                errors.add(StageProblem.structural(path + ": 钉住帧宽度不能小于 " + (long) Stage.MIN_PIN_WIDTH));
            }
            if (Stage.FREE_HEIGHT_TYPES.contains(block.type())) {
                if (frame.h() == null || !Double.isFinite(frame.h())) {
                    errors.add(StageProblem.structural(path + ": " + block.type() + " 块钉住时必须给高度 h"));
                } else if (frame.h() < Stage.MIN_PIN_HEIGHT) {
                    errors.add(StageProblem.structural(path + ": 钉住帧高度不能小于 " + (long) Stage.MIN_PIN_HEIGHT));
                } else if (frame.y() + frame.h() > canvasHeight) {
                    errors.add(StageProblem.structural(path + ": 钉住帧超出页面(页面 " + (long) canvasWidth + "×" + (long) canvasHeight + ")"));
                }
            } else if (frame.h() != null) {
                errors.add(StageProblem.structural(path + ": " + block.type() + " 块的高度由内容决定,钉住时不能给 h"));
            }
        }
        return errors;
    }

    public static List<StageProblem> validateScene(Stage.Scene scene) {
        List<StageProblem> errors = new ArrayList<>();
        List<Block> blocks = scene.blocks() == null ? List.of() : scene.blocks();
        List<Stage.SpeechSegment> speech = scene.speech() == null ? List.of() : scene.speech();

        // id 缺失/超长会在落库(学习记录列宽 scene 32)时炸成 500,这里前置成校验错误;
        // 块 id 长度在 validateBlocks 里查(生成期 refine 即可拦截)
        if (scene.id() == null || scene.id().isBlank()) {
            errors.add(StageProblem.structural("页面缺少 id"));
        } else if (scene.id().length() > 32) {
            errors.add(StageProblem.structural("页面 id 过长(最多 32 字符):" + scene.id()));
        }

        if ("interactive".equals(scene.type())) {
            if (scene.interactive() == null || scene.interactive().html() == null
                    || scene.interactive().html().trim().isEmpty()) {
                errors.add(StageProblem.structural("interactive 页缺少 interactive.html"));
            }
            if (scene.interactive() != null && scene.interactive().widgetType() != null
                    && !Stage.WIDGET_TYPES.contains(scene.interactive().widgetType())) {
                errors.add(StageProblem.structural("interactive 页的 widgetType \"" + scene.interactive().widgetType() + "\" 非法"));
            }
        } else if (scene.interactive() != null) {
            errors.add(StageProblem.structural(scene.type() + " 页不应携带 interactive 内容"));
        }
        if ("video".equals(scene.type())) {
            if (scene.video() == null || scene.video().src() == null || scene.video().src().isBlank()) {
                errors.add(StageProblem.structural("video 页缺少 video.src"));
            }
        } else if (scene.video() != null) {
            errors.add(StageProblem.structural(scene.type() + " 页不应携带 video 内容"));
        }
        if (Stage.isBlockless(scene.type())) {
            if (!blocks.isEmpty()) {
                errors.add(StageProblem.structural(scene.type() + " 页的 blocks 必须为空"));
            }
        } else if (blocks.isEmpty()) {
            errors.add(new StageProblem("页面没有任何块", "这一页至少要保留一个内容块"));
        }
        if ("quiz".equals(scene.type())
                && blocks.stream().noneMatch(b -> b instanceof Block.QuizChoice)) {
            errors.add(new StageProblem("quiz 页至少需要一个 quiz_choice 块", "测验页至少要保留一道选择题"));
        }
        errors.addAll(validateBlocks(blocks, scene.type()));
        errors.addAll(validateSpeech(speech, scene.type(), blocks));
        errors.addAll(validateLayouts(scene.layouts(), scene.type(), blocks));
        return errors;
    }

    private static boolean isBlankOrNull(String s) {
        return s == null || s.isEmpty();
    }

    private static Integer parseIntOrNull(String s) {
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
