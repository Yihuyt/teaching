package cn.utcy.teaching.knowledgegraph.domain;

import cn.utcy.teaching.knowledgegraph.domain.BuildPreview.PreviewEdge;
import cn.utcy.teaching.knowledgegraph.domain.BuildPreview.PreviewNode;

import java.util.ArrayDeque;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管线产物 → 树模型(纯函数):目录条目 → 章节(层级栈定父子,兄弟顺序即 position,汇总摘要为说明);
 * 知识点挂首现小节;代码示例挂第一个绑定知识点;关系去重、相关按键序规范化;端点缺失只警告不失败。
 * 输出满足 {@link GraphRules}(入库时整图复核)。
 */
public final class GraphAssembler {

    public record CodeExample(String title, String language, String code, String explanation,
                              List<String> evidenceQuotes, List<String> bindKpNames) {
    }

    private GraphAssembler() {
    }

    /**
     * @param sectionSummaries 目录条目下标 → 摘要(叶为本文摘要,父为自底向上聚合)
     * @param kpFirstEntry     知识点下标 → 首现目录条目下标
     * @param extractedLeaves  参与了抽取的叶条目下标(用于"未抽取到知识点"警告)
     */
    public static BuildPreview assemble(List<TocEntry> entries,
                                        Map<Integer, String> sectionSummaries,
                                        List<EntityMerger.MergedKp> knowledgePoints,
                                        Map<Integer, Integer> kpFirstEntry,
                                        Set<Integer> extractedLeaves,
                                        List<GraphRepairer.Relation> relations,
                                        List<CodeExample> codeExamples) {
        List<PreviewNode> nodes = new ArrayList<>();
        List<PreviewEdge> edges = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Integer> childCount = new HashMap<>();

        String[] unitKeys = new String[entries.size()];
        Deque<Integer> ancestry = new ArrayDeque<>();
        for (int i = 0; i < entries.size(); i++) {
            TocEntry entry = entries.get(i);
            while (!ancestry.isEmpty() && entries.get(ancestry.peek()).level() >= entry.level()) {
                ancestry.pop();
            }
            String parentKey = ancestry.isEmpty() ? null : unitKeys[ancestry.peek()];
            unitKeys[i] = "u" + i;
            nodes.add(new PreviewNode(unitKeys[i], parentKey, nextPosition(childCount, parentKey), NodeKind.UNIT,
                    null, GraphRules.truncate(TocOutline.display(entry), GraphRules.MAX_LABEL),
                    GraphRules.truncate(blankToNull(sectionSummaries.get(i)), GraphRules.MAX_TEXT),
                    null, null, List.of(), null, null, null, null));
            ancestry.push(i);
        }

        Map<String, String> kpKeys = new LinkedHashMap<>();
        Set<Integer> entriesWithKp = new LinkedHashSet<>();
        for (int k = 0; k < knowledgePoints.size(); k++) {
            EntityMerger.MergedKp kp = knowledgePoints.get(k);
            Integer entryIndex = kpFirstEntry.get(k);
            if (entryIndex == null) {
                throw new IllegalStateException("知识点「" + kp.name() + "」无法定位首现小节");
            }
            String key = "k" + k;
            String label = GraphRules.truncate(kp.name(), GraphRules.MAX_LABEL);
            kpKeys.put(kp.name(), key);
            entriesWithKp.add(entryIndex);
            nodes.add(new PreviewNode(key, unitKeys[entryIndex], nextPosition(childCount, unitKeys[entryIndex]),
                    NodeKind.KNOWLEDGE_POINT, KpType.fromValue(kp.kpType()), label, null,
                    GraphRules.truncate(blankToNull(kp.definition()), GraphRules.MAX_TEXT), null,
                    aliases(kp.aliases(), label), null, null,
                    GraphRules.truncate(TocOutline.display(entries.get(entryIndex)), GraphRules.MAX_SOURCE_TITLE),
                    firstQuote(kp.evidenceQuotes())));
        }
        for (int leaf : TocOutline.leafIndexes(entries)) {
            if (extractedLeaves.contains(leaf) && !entriesWithKp.contains(leaf)) {
                warnings.add("小节「" + TocOutline.display(entries.get(leaf)) + "」未抽取到知识点");
            }
        }

        Map<String, EdgeKind> pairs = new HashMap<>();
        int skipped = 0;
        for (GraphRepairer.Relation relation : relations) {
            String sourceKey = kpKeys.get(relation.sourceName());
            String targetKey = kpKeys.get(relation.targetName());
            if (sourceKey == null || targetKey == null || sourceKey.equals(targetKey)) {
                skipped++;
                continue;
            }
            String pairKey = sourceKey.compareTo(targetKey) <= 0
                    ? sourceKey + "|" + targetKey : targetKey + "|" + sourceKey;
            EdgeKind existing = pairs.get(pairKey);
            if (existing != null) {
                // 同一对只能有一种关系;前置已去环,同向重复与"相关 + 前置"并存都只保留先出现的前置
                if (existing == EdgeKind.RELATED && relation.kind() == EdgeKind.PREREQUISITE) {
                    edges.removeIf(edge -> pairKey.equals(pairKeyOf(edge)));
                } else {
                    continue;
                }
            }
            pairs.put(pairKey, relation.kind());
            if (relation.kind() == EdgeKind.RELATED) {
                String[] ordered = pairKey.split("\\|");
                edges.add(new PreviewEdge(ordered[0], ordered[1], EdgeKind.RELATED, evidence(relation.evidence())));
            } else {
                edges.add(new PreviewEdge(sourceKey, targetKey, EdgeKind.PREREQUISITE, evidence(relation.evidence())));
            }
        }
        if (skipped > 0) {
            warnings.add(skipped + " 条关系的端点不在知识点集合中，已跳过");
        }

        int codeIndex = 0;
        for (CodeExample code : codeExamples) {
            List<String> boundKeys = code.bindKpNames().stream()
                    .map(kpKeys::get)
                    .filter(key -> key != null)
                    .distinct()
                    .toList();
            if (boundKeys.isEmpty()) {
                warnings.add("代码示例「" + code.title() + "」未能关联到任何知识点，已剔除");
                continue;
            }
            String parentKey = boundKeys.get(0);
            if (boundKeys.size() > 1) {
                warnings.add("代码示例「" + code.title() + "」绑定了多个知识点，已挂到「"
                        + labelOf(nodes, parentKey) + "」");
            }
            nodes.add(new PreviewNode("c" + codeIndex++, parentKey, nextPosition(childCount, parentKey),
                    NodeKind.CODE_EXAMPLE, null, GraphRules.truncate(code.title(), GraphRules.MAX_LABEL),
                    null, null, GraphRules.truncate(blankToNull(code.explanation()), GraphRules.MAX_TEXT), List.of(),
                    GraphRules.truncate(code.code(), GraphRules.MAX_CODE),
                    GraphRules.truncate(code.language(), GraphRules.MAX_LANGUAGE),
                    null, firstQuote(code.evidenceQuotes())));
        }

        return new BuildPreview(nodes, edges, warnings);
    }

    private static int nextPosition(Map<String, Integer> childCount, String parentKey) {
        return childCount.merge(parentKey == null ? "" : parentKey, 1, Integer::sum);
    }

    private static List<String> aliases(List<String> raw, String label) {
        Set<String> unique = new LinkedHashSet<>();
        for (String alias : raw) {
            String value = GraphRules.truncate(alias, GraphRules.MAX_ALIAS);
            if (value != null && !value.isEmpty() && !value.equals(label)) {
                unique.add(value);
            }
            if (unique.size() == GraphRules.MAX_ALIASES) {
                break;
            }
        }
        return List.copyOf(unique);
    }

    /** "程序如下:"这类引导语没有定位价值,不配当出处凭据 */
    private static final Pattern LEAD_IN_QUOTE = Pattern.compile(
            "^(程序|代码|示例|例子|例题|解|答|分析|运行结果|输出结果|格式)?(如下|见下|如上|如图)(所示)?[:：。]?$");

    /** 取第一条有定位价值的引文;全是引导语则不留引文(出处若存在必可对账) */
    private static String firstQuote(List<String> quotes) {
        for (String quote : quotes) {
            String value = blankToNull(quote);
            if (value != null && !LEAD_IN_QUOTE.matcher(value.strip()).matches()) {
                return GraphRules.truncate(value, GraphRules.MAX_QUOTE);
            }
        }
        return null;
    }

    private static String evidence(String evidence) {
        return GraphRules.truncate(blankToNull(evidence), GraphRules.MAX_EVIDENCE);
    }

    private static String pairKeyOf(PreviewEdge edge) {
        return edge.sourceKey().compareTo(edge.targetKey()) <= 0
                ? edge.sourceKey() + "|" + edge.targetKey() : edge.targetKey() + "|" + edge.sourceKey();
    }

    private static String labelOf(List<PreviewNode> nodes, String key) {
        return nodes.stream().filter(node -> node.key().equals(key)).map(PreviewNode::label).findFirst().orElse(key);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
