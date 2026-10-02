package cn.utcy.teaching.knowledgegraph.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 前置依赖去环(纯函数):Tarjan 找强连通分量,环内删 evidence 最短的边(同长按端点字典序),
 * 重复至无环;相关关系原样保留。
 */
public final class GraphRepairer {

    public record Relation(String sourceName, String targetName, EdgeKind kind, String evidence) {
    }

    public record RemovalResult(List<Relation> relations, List<String> removedEdges) {
    }

    private GraphRepairer() {
    }

    public static RemovalResult removePrerequisiteCycles(List<Relation> relations) {
        List<Relation> prerequisites = new ArrayList<>();
        List<Relation> others = new ArrayList<>();
        for (Relation relation : relations) {
            if (relation.kind() == EdgeKind.PREREQUISITE) {
                prerequisites.add(relation);
            } else {
                others.add(relation);
            }
        }
        List<String> removed = new ArrayList<>();
        while (true) {
            List<Set<String>> cycles = stronglyConnectedComponents(prerequisites);
            if (cycles.isEmpty()) {
                break;
            }
            for (Set<String> component : cycles) {
                Relation weakest = prerequisites.stream()
                        .filter(r -> component.contains(r.sourceName())
                                && component.contains(r.targetName()))
                        .min(Comparator
                                .comparingInt((Relation r) ->
                                        r.evidence() == null ? 0 : r.evidence().length())
                                .thenComparing(Relation::sourceName)
                                .thenComparing(Relation::targetName))
                        .orElseThrow();
                prerequisites.remove(weakest);
                removed.add(weakest.sourceName() + " → " + weakest.targetName());
            }
        }
        List<Relation> kept = new ArrayList<>(prerequisites);
        kept.addAll(others);
        return new RemovalResult(kept, removed);
    }

    /** Tarjan;只返回节点数 ≥2 的分量(单节点无自环——自环在上游已剔除) */
    private static List<Set<String>> stronglyConnectedComponents(List<Relation> relations) {
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (Relation relation : relations) {
            adjacency.computeIfAbsent(relation.sourceName(), key -> new ArrayList<>())
                    .add(relation.targetName());
            adjacency.computeIfAbsent(relation.targetName(), key -> new ArrayList<>());
        }
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> lowLink = new HashMap<>();
        Set<String> onStack = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        List<Set<String>> components = new ArrayList<>();
        int[] counter = {0};

        // 迭代式 Tarjan:显式栈避免深图递归溢出
        for (String start : adjacency.keySet()) {
            if (index.containsKey(start)) {
                continue;
            }
            Deque<Object[]> work = new ArrayDeque<>();
            work.push(new Object[]{start, 0});
            while (!work.isEmpty()) {
                Object[] frame = work.peek();
                String node = (String) frame[0];
                int childIndex = (int) frame[1];
                if (childIndex == 0) {
                    index.put(node, counter[0]);
                    lowLink.put(node, counter[0]);
                    counter[0]++;
                    stack.push(node);
                    onStack.add(node);
                }
                List<String> neighbors = adjacency.get(node);
                if (childIndex < neighbors.size()) {
                    frame[1] = childIndex + 1;
                    String next = neighbors.get(childIndex);
                    if (!index.containsKey(next)) {
                        work.push(new Object[]{next, 0});
                    } else if (onStack.contains(next)) {
                        lowLink.merge(node, index.get(next), Math::min);
                    }
                } else {
                    work.pop();
                    if (!work.isEmpty()) {
                        lowLink.merge((String) work.peek()[0], lowLink.get(node), Math::min);
                    }
                    if (lowLink.get(node).equals(index.get(node))) {
                        Set<String> component = new LinkedHashSet<>();
                        String member;
                        do {
                            member = stack.pop();
                            onStack.remove(member);
                            component.add(member);
                        } while (!member.equals(node));
                        if (component.size() >= 2) {
                            components.add(component);
                        }
                    }
                }
            }
        }
        return components;
    }
}
