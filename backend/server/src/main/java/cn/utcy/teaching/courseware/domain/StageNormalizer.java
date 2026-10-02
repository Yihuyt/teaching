package cn.utcy.teaching.courseware.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规范化 —— 对 LLM 产出做确定性修整,能修的修,修不了的以 warning 报告。
 *
 * <p>与 validate 的分工:validate 只报告不改动;normalize 做安全的自动修整
 * (补 id、截齐表格、钳制数值、丢弃指向不存在块的动作),并把每处修整记录
 * 为 warning 供生成管线回喂或日志观察。修整后的产物应能通过 validate。
 */
public final class StageNormalizer {

    public record NormalizeResult<T>(T value, List<String> warnings) {
    }

    public static NormalizeResult<List<Block>> normalizeBlockIds(List<Block> blocks) {
        List<String> warnings = new ArrayList<>();
        // 先登记全部已声明的 id:新分配的 id 不得与任何已声明 id 撞车,
        // 否则"补空 id"可能抢走后面块的合法 id,造成连环重命名。
        Set<String> taken = new HashSet<>();
        for (Block block : blocks) {
            registerId(taken, block);
            if (block instanceof Block.Columns columns) {
                for (List<Block> col : columns.children()) {
                    for (Block child : col) {
                        registerId(taken, child);
                    }
                }
            }
        }

        Set<String> seen = new HashSet<>();
        Map<String, Integer> counters = new HashMap<>();
        IdFixer fixer = new IdFixer(taken, seen, counters, warnings);

        List<Block> result = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            Block fixed = fixer.fix(blocks.get(i), "blocks[" + i + "]");
            if (fixed instanceof Block.Columns columns) {
                List<List<Block>> children = new ArrayList<>(columns.children().size());
                for (int ci = 0; ci < columns.children().size(); ci++) {
                    List<Block> col = columns.children().get(ci);
                    List<Block> fixedCol = new ArrayList<>(col.size());
                    for (int bi = 0; bi < col.size(); bi++) {
                        fixedCol.add(fixer.fix(col.get(bi),
                                "blocks[" + i + "].children[" + ci + "][" + bi + "]"));
                    }
                    children.add(fixedCol);
                }
                fixed = new Block.Columns(columns.id(), columns.ratio(), children);
            }
            result.add(fixed);
        }

        return new NormalizeResult<>(result, warnings);
    }

    private static void registerId(Set<String> taken, Block block) {
        String id = block.id() == null ? "" : block.id().trim();
        if (!id.isEmpty()) {
            taken.add(id);
        }
    }

    /** 补 id 的可变状态收拢在一处,避免方法间传四个集合 */
    private static final class IdFixer {
        private final Set<String> taken;
        private final Set<String> seen;
        private final Map<String, Integer> counters;
        private final List<String> warnings;

        IdFixer(Set<String> taken, Set<String> seen, Map<String, Integer> counters,
                List<String> warnings) {
            this.taken = taken;
            this.seen = seen;
            this.counters = counters;
            this.warnings = warnings;
        }

        Block fix(Block block, String path) {
            String id = block.id() == null ? "" : block.id().trim();
            if (id.isEmpty() || seen.contains(id)) {
                String fresh = nextId(block.type());
                warnings.add(id.isEmpty()
                        ? path + ": 块缺少 id,已补为 \"" + fresh + "\""
                        : path + ": 块 id \"" + id + "\" 重复,已改为 \"" + fresh + "\"");
                seen.add(fresh);
                return withId(block, fresh);
            }
            seen.add(id);
            return block;
        }

        private String nextId(String type) {
            int n = counters.getOrDefault(type, 0) + 1;
            while (taken.contains("blk-" + type + "-" + n)) {
                n += 1;
            }
            counters.put(type, n);
            String id = "blk-" + type + "-" + n;
            taken.add(id);
            return id;
        }
    }

    private static Block withId(Block block, String id) {
        return switch (block) {
            case Block.Heading b -> new Block.Heading(id, b.level(), b.text());
            case Block.Paragraph b -> new Block.Paragraph(id, b.text());
            case Block.Bullets b -> new Block.Bullets(id, b.ordered(), b.items());
            case Block.Formula b -> new Block.Formula(id, b.latex(), b.caption());
            case Block.Code b -> new Block.Code(id, b.language(), b.code(), b.caption());
            case Block.Table b -> new Block.Table(id, b.headers(), b.rows(), b.caption());
            case Block.Chart b -> new Block.Chart(id, b.chartType(), b.categories(), b.series(),
                    b.caption());
            case Block.Emphasis b -> new Block.Emphasis(id, b.text(), b.caption());
            case Block.Image b -> new Block.Image(id, b.src(), b.width(), b.height(), b.caption());
            case Block.Callout b -> new Block.Callout(id, b.variant(), b.title(), b.text());
            case Block.Columns b -> new Block.Columns(id, b.ratio(), b.children());
            case Block.QuizChoice b -> new Block.QuizChoice(id, b.stem(), b.options(), b.answer(),
                    b.multiple(), b.explanation());
        };
    }

    private static Block normalizeLeaf(Block block, String path, List<String> warnings) {
        if (block instanceof Block.Table table) {
            int width = table.headers().size();
            List<List<String>> rows = new ArrayList<>(table.rows().size());
            for (int i = 0; i < table.rows().size(); i++) {
                List<String> row = table.rows().get(i);
                if (row.size() == width) {
                    rows.add(row);
                    continue;
                }
                warnings.add(path + ": 表格第 " + (i + 1) + " 行列数 " + row.size()
                        + " ≠ " + width + ",已截齐/补空");
                List<String> fixed = new ArrayList<>(row.subList(0, Math.min(row.size(), width)));
                while (fixed.size() < width) {
                    fixed.add("");
                }
                rows.add(fixed);
            }
            return new Block.Table(table.id(), table.headers(), rows, table.caption());
        }
        if (block instanceof Block.Chart chart) {
            int len = chart.categories().size();
            List<Block.ChartSeries> series = new ArrayList<>(chart.series().size());
            for (Block.ChartSeries s : chart.series()) {
                if (s.data().size() == len) {
                    series.add(s);
                    continue;
                }
                warnings.add(path + ": series \"" + s.name() + "\" 数据点 " + s.data().size()
                        + " ≠ " + len + ",已截齐/补 0");
                List<Double> data = new ArrayList<>(s.data().subList(0, Math.min(s.data().size(), len)));
                while (data.size() < len) {
                    data.add(0.0);
                }
                series.add(new Block.ChartSeries(s.name(), data));
            }
            return new Block.Chart(chart.id(), chart.chartType(), chart.categories(), series,
                    chart.caption());
        }
        return block;
    }

    public static NormalizeResult<List<Block>> normalizeBlocks(List<Block> blocks) {
        NormalizeResult<List<Block>> idResult = normalizeBlockIds(blocks);
        List<String> warnings = new ArrayList<>(idResult.warnings());

        List<Block> value = new ArrayList<>(idResult.value().size());
        for (int i = 0; i < idResult.value().size(); i++) {
            Block block = idResult.value().get(i);
            String path = "blocks[" + i + "](" + block.id() + ")";
            if (block instanceof Block.Columns columns) {
                List<List<Block>> children = new ArrayList<>(columns.children().size());
                for (int ci = 0; ci < columns.children().size(); ci++) {
                    List<Block> col = columns.children().get(ci);
                    List<Block> fixedCol = new ArrayList<>(col.size());
                    for (int bi = 0; bi < col.size(); bi++) {
                        fixedCol.add(normalizeLeaf(col.get(bi),
                                path + ".children[" + ci + "][" + bi + "]", warnings));
                    }
                    children.add(fixedCol);
                }
                value.add(new Block.Columns(columns.id(), columns.ratio(), children));
                continue;
            }
            if (block instanceof Block.QuizChoice quiz) {
                List<String> answer = new ArrayList<>(new LinkedHashSet<>(quiz.answer()));
                if (answer.size() != quiz.answer().size()) {
                    warnings.add(path + ": answer 有重复,已去重");
                }
                boolean multiple = answer.size() > 1 || quiz.multiple();
                if (multiple != quiz.multiple()) {
                    warnings.add(path + ": 有 " + answer.size()
                            + " 个答案但 multiple=false,已改为 true");
                }
                value.add(new Block.QuizChoice(quiz.id(), quiz.stem(), quiz.options(), answer,
                        multiple, quiz.explanation()));
                continue;
            }
            value.add(normalizeLeaf(block, path, warnings));
        }

        return new NormalizeResult<>(value, warnings);
    }

    public static NormalizeResult<List<Stage.SpeechSegment>> normalizeSpeech(
            List<Stage.SpeechSegment> speech, String sceneType, List<Block> blocks) {
        List<String> warnings = new ArrayList<>();
        Map<String, Integer> blockIds = StageValidator.collectBlockIds(blocks);

        // 与 TS 一致:丢空段的 warning 用原始下标,动作路径用过滤后的下标
        List<Stage.SpeechSegment> kept = new ArrayList<>();
        for (int si = 0; si < speech.size(); si++) {
            Stage.SpeechSegment segment = speech.get(si);
            if (segment.text().trim().isEmpty()) {
                warnings.add("speech[" + si + "]: 空讲稿段已丢弃");
                continue;
            }
            kept.add(segment);
        }

        List<Stage.SpeechSegment> value = new ArrayList<>(kept.size());
        for (int si = 0; si < kept.size(); si++) {
            Stage.SpeechSegment segment = kept.get(si);
            List<Action> actions = new ArrayList<>();
            for (Action action : segment.actions()) {
                String path = "speech[" + si + "].actions(" + action.type() + ")";
                if (action instanceof Action.Pause pause) {
                    int ms = Math.min(5000, Math.max(200, pause.ms()));
                    if (ms != pause.ms()) {
                        warnings.add(path + ": ms=" + pause.ms() + " 已钳制为 " + ms);
                    }
                    actions.add(new Action.Pause(ms));
                    continue;
                }
                if (Stage.isBlockless(sceneType)) {
                    warnings.add(path + ": " + sceneType + " 页的 " + action.type()
                            + " 动作已丢弃(没有内容块的页只允许 pause)");
                    continue;
                }
                String err = StageValidator.validateActionTarget(
                        StageValidator.actionTarget(action), blockIds);
                if (err != null) {
                    warnings.add(path + ": " + err + ",动作已丢弃");
                    continue;
                }
                actions.add(action);
            }
            // 空白 audioPath 一律清成 null:audioPath 只能由 TTS 回填,空串是"没有音频"
            String audioPath = segment.audioPath() != null && !segment.audioPath().isBlank()
                    ? segment.audioPath() : null;
            value.add(new Stage.SpeechSegment(segment.text().trim(), actions, audioPath));
        }

        return new NormalizeResult<>(value, warnings);
    }

    /**
     * 清洗排版覆盖:丢弃指向不存在顶层块的条目、丢弃既无 frame 也无 size 的空条目、同块重复取后者。
     * 与讲稿动作清洗同一思路——块被删了,挂在它身上的东西跟着走,不让整批操作因此被拒。
     */
    public static NormalizeResult<List<Stage.BlockLayout>> normalizeLayouts(List<Stage.BlockLayout> layouts,
                                                                          List<Block> blocks) {
        List<String> warnings = new ArrayList<>();
        if (layouts == null || layouts.isEmpty()) {
            return new NormalizeResult<>(List.of(), warnings);
        }
        Set<String> topLevel = new HashSet<>();
        for (Block block : blocks) topLevel.add(block.id());
        Map<String, Stage.BlockLayout> byId = new LinkedHashMap<>();
        for (int i = 0; i < layouts.size(); i++) {
            Stage.BlockLayout layout = layouts.get(i);
            String path = "layouts[" + i + "](" + (layout == null ? "null" : layout.blockId()) + ")";
            if (layout == null || !topLevel.contains(layout.blockId())) {
                warnings.add(path + ": 排版覆盖指向不存在的顶层块,已丢弃");
                continue;
            }
            if (layout.frame() == null && layout.size() == null) {
                warnings.add(path + ": 空的排版覆盖已丢弃");
                continue;
            }
            if (byId.containsKey(layout.blockId())) {
                warnings.add(path + ": 块 \"" + layout.blockId() + "\" 的排版覆盖重复,以后者为准");
            }
            byId.put(layout.blockId(), layout);
        }
        return new NormalizeResult<>(List.copyOf(byId.values()), warnings);
    }
}
