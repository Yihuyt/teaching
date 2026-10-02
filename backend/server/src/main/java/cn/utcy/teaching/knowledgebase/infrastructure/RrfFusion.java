package cn.utcy.teaching.knowledgebase.infrastructure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 倒数排名融合(Reciprocal Rank Fusion):对 BM25 与向量两路各自的排名列表,
 * score(id) = Σ 1/(K + rank),按融合分降序截断。K=60 是文献标准值。
 * 纯函数,不依赖 ES 返回的原始分值(两路分值量纲不可比,只用名次)。
 */
public final class RrfFusion {

    private static final int K = 60;

    private RrfFusion() {
    }

    /** rankings 中每个列表按各自检索器的相关性降序排列,元素为 chunk id */
    public static List<String> fuse(List<List<String>> rankings, int limit) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<String> ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                scores.merge(ranking.get(rank), 1.0 / (K + rank + 1), Double::sum);
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }
}
