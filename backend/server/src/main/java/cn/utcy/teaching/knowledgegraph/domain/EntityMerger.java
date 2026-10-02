package cn.utcy.teaching.knowledgegraph.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨节知识点合并(纯函数容器,LLM 裁决由调用方完成后回填):
 * 规范化名称精确合并(同名一律合并,不看小类——同名不同类只会让图里出现重名节点)
 * → 跨簇候选对交调用方保守裁决(bigram Dice ≥ 0.70,或两簇"名字∪别名"有交集——
 *   "主函数"与"main函数"这类中英同义词字面相似度为零,但模型常已互给别名)
 * → 并查集收敛 → canonical 取最长释义、小类取首次出现,别名 / 引文 / 出处节合并
 * → 生成 原始名→规范名 重写表。
 */
public final class EntityMerger {

    public record RawKp(String name, String kpType, String definition,
                        List<String> aliases, List<String> evidenceQuotes, int sectionIndex) {
    }

    /** 待 LLM 裁决的候选对(两簇代表名与释义,附双方别名——别名互指是最强的合并信号) */
    public record CandidatePair(int pairId, int leftRoot, int rightRoot,
                                String leftName, String leftDefinition, List<String> leftAliases,
                                String rightName, String rightDefinition, List<String> rightAliases) {
    }

    public record MergedKp(String name, String kpType, String definition,
                           List<String> aliases, List<String> evidenceQuotes, List<Integer> sectionIndexes) {
    }

    /** 候选门槛宽进(0.70):误召回由 LLM 保守裁决兜住,漏召回则永远无法合并 */
    private static final double DICE_THRESHOLD = 0.70;

    private final List<RawKp> items = new ArrayList<>();
    private int[] parent;
    private List<RootRef> recallRoots = List.of();

    public record RootRef(int root, String name, String definition, List<String> aliases) {
    }

    public List<RootRef> recallRoots() {
        return recallRoots;
    }

    public void add(RawKp kp) {
        items.add(kp);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public List<CandidatePair> exactMergeAndCollectCandidates() {
        parent = new int[items.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        Map<String, Integer> byNormalized = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            Integer existing = byNormalized.putIfAbsent(normalize(items.get(i).name()), i);
            if (existing != null) {
                union(existing, i);
            }
        }

        // 跨簇相似候选:名字 bigram Dice ≥ 阈值,或两簇"名字∪别名"有交集;
        // 小类不同也交裁决(同一知识点在不同节可能被判成不同小类)
        Map<Integer, Set<String>> labelsByRoot = new HashMap<>();
        Map<Integer, Set<String>> rawAliasesByRoot = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            int root = find(i);
            Set<String> labels = labelsByRoot.computeIfAbsent(root, key -> new HashSet<>());
            labels.add(normalize(items.get(i).name()));
            items.get(i).aliases().forEach(alias -> labels.add(normalize(alias)));
            Set<String> raw = rawAliasesByRoot.computeIfAbsent(root, key -> new LinkedHashSet<>());
            raw.addAll(items.get(i).aliases());
        }
        List<Integer> roots = new ArrayList<>(new LinkedHashSet<>(rootsOfAll()));
        List<RootRef> refs = new ArrayList<>(roots.size());
        for (int root : roots) {
            refs.add(new RootRef(root, items.get(root).name(), items.get(root).definition(),
                    List.copyOf(rawAliasesByRoot.get(root))));
        }
        this.recallRoots = List.copyOf(refs);
        List<CandidatePair> candidates = new ArrayList<>();
        int pairId = 0;
        for (int a = 0; a < roots.size(); a++) {
            for (int b = a + 1; b < roots.size(); b++) {
                RawKp left = items.get(roots.get(a));
                RawKp right = items.get(roots.get(b));
                if (dice(normalize(left.name()), normalize(right.name())) >= DICE_THRESHOLD
                        || sharesLabel(labelsByRoot.get(roots.get(a)), labelsByRoot.get(roots.get(b)))) {
                    candidates.add(new CandidatePair(pairId++, roots.get(a), roots.get(b),
                            left.name(), left.definition(),
                            List.copyOf(rawAliasesByRoot.get(roots.get(a))),
                            right.name(), right.definition(),
                            List.copyOf(rawAliasesByRoot.get(roots.get(b)))));
                }
            }
        }
        return candidates;
    }

    public void applyMerge(CandidatePair pair) {
        union(pair.leftRoot(), pair.rightRoot());
    }

    public MergeResult finalizeMerge() {
        Map<Integer, List<RawKp>> clusters = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            clusters.computeIfAbsent(find(i), key -> new ArrayList<>()).add(items.get(i));
        }
        List<MergedKp> merged = new ArrayList<>();
        Map<String, String> nameToCanonical = new LinkedHashMap<>();
        for (List<RawKp> cluster : clusters.values()) {
            RawKp canonical = cluster.stream()
                    .max(Comparator.comparingInt((RawKp kp) -> kp.definition().length()))
                    .orElseThrow();
            String kpType = cluster.get(0).kpType();
            Set<String> aliases = new LinkedHashSet<>();
            Set<String> quotes = new LinkedHashSet<>();
            List<Integer> sections = new ArrayList<>();
            for (RawKp kp : cluster) {
                if (!kp.name().equals(canonical.name())) {
                    aliases.add(kp.name());
                }
                aliases.addAll(kp.aliases());
                quotes.addAll(kp.evidenceQuotes());
                sections.add(kp.sectionIndex());
                nameToCanonical.put(kp.name(), canonical.name());
                kp.aliases().forEach(alias -> nameToCanonical.putIfAbsent(alias, canonical.name()));
            }
            aliases.remove(canonical.name());
            merged.add(new MergedKp(canonical.name(), kpType, canonical.definition(),
                    List.copyOf(aliases), List.copyOf(quotes),
                    sections.stream().distinct().sorted().toList()));
        }
        return new MergeResult(merged, nameToCanonical);
    }

    public record MergeResult(List<MergedKp> knowledgePoints, Map<String, String> nameToCanonical) {
    }

    // ---- 并查集与相似度 -------------------------------------------------------

    private List<Integer> rootsOfAll() {
        List<Integer> roots = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            roots.add(find(i));
        }
        return roots;
    }

    private int find(int index) {
        while (parent[index] != index) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    }

    private void union(int a, int b) {
        int rootA = find(a);
        int rootB = find(b);
        if (rootA != rootB) {
            parent[Math.max(rootA, rootB)] = Math.min(rootA, rootB);
        }
    }

    static String normalize(String name) {
        StringBuilder out = new StringBuilder();
        for (char c : name.strip().toCharArray()) {
            char mapped = c;
            if (c >= 0xFF01 && c <= 0xFF5E) {
                mapped = (char) (c - 0xFEE0);
            }
            if (Character.isWhitespace(mapped)) {
                continue;
            }
            if ("，。:：;；、()（）[]【】《》\"'`·….*•—–_-".indexOf(mapped) >= 0) {
                continue;
            }
            out.append(Character.toLowerCase(mapped));
        }
        return out.toString();
    }

    /**
     * 语义召回(纯函数,向量由调用方提供):每个簇根取余弦 top-K 邻居且 ≥ 阈值,
     * 去掉已被字面/别名网捞过的,按相似度取前 maxPairs 条。只捞不判——
     * 平行兄弟概念(for/while 循环)必然高分入围,由 LLM 裁决把关。
     */
    public static List<CandidatePair> semanticCandidates(List<RootRef> roots, List<float[]> vectors,
                                                         List<CandidatePair> existing, double threshold,
                                                         int topK, int maxPairs, int nextPairId) {
        Set<Long> seen = new HashSet<>();
        for (CandidatePair pair : existing) {
            seen.add(pairKey(pair.leftRoot(), pair.rightRoot()));
        }
        record Scored(int a, int b, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (int a = 0; a < roots.size(); a++) {
            List<Scored> neighbors = new ArrayList<>();
            for (int b = 0; b < roots.size(); b++) {
                if (a == b) {
                    continue;
                }
                double score = cosine(vectors.get(a), vectors.get(b));
                if (score >= threshold) {
                    neighbors.add(new Scored(a, b, score));
                }
            }
            neighbors.sort(Comparator.comparingDouble(Scored::score).reversed());
            for (Scored neighbor : neighbors.subList(0, Math.min(topK, neighbors.size()))) {
                long key = pairKey(roots.get(neighbor.a()).root(), roots.get(neighbor.b()).root());
                if (seen.add(key)) {
                    scored.add(neighbor);
                }
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<CandidatePair> pairs = new ArrayList<>();
        int pairId = nextPairId;
        for (Scored item : scored.subList(0, Math.min(maxPairs, scored.size()))) {
            RootRef left = roots.get(item.a());
            RootRef right = roots.get(item.b());
            pairs.add(new CandidatePair(pairId++, left.root(), right.root(),
                    left.name(), left.definition(), left.aliases(),
                    right.name(), right.definition(), right.aliases()));
        }
        return pairs;
    }

    private static long pairKey(int a, int b) {
        return (long) Math.min(a, b) << 32 | Math.max(a, b);
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0;
        double normLeft = 0;
        double normRight = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            normLeft += left[i] * left[i];
            normRight += right[i] * right[i];
        }
        return normLeft == 0 || normRight == 0 ? 0 : dot / (Math.sqrt(normLeft) * Math.sqrt(normRight));
    }

    private static boolean sharesLabel(Set<String> left, Set<String> right) {
        for (String label : left) {
            if (right.contains(label)) {
                return true;
            }
        }
        return false;
    }

    static double dice(String left, String right) {
        if (left.equals(right)) {
            return 1;
        }
        if (left.length() < 2 || right.length() < 2) {
            return 0;
        }
        Set<String> leftGrams = bigrams(left);
        Set<String> rightGrams = bigrams(right);
        int overlap = 0;
        for (String gram : leftGrams) {
            if (rightGrams.contains(gram)) {
                overlap++;
            }
        }
        return 2.0 * overlap / (leftGrams.size() + rightGrams.size());
    }

    private static Set<String> bigrams(String text) {
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 1 < text.length(); i++) {
            grams.add(text.substring(i, i + 2));
        }
        return grams;
    }
}
