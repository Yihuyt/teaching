package cn.utcy.teaching.knowledgegraph.domain;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 构建管线的产物 = 待入库的图谱文档(键是构建内的临时标识,入库时换成节点 id)。
 * 节点按父先子后排列,同父兄弟 position 从 1 连续;既是预览接口的响应,也是 preview_json 的持久化形态。
 */
public record BuildPreview(List<PreviewNode> nodes, List<PreviewEdge> edges, List<String> warnings) {

    public record PreviewNode(
            String key,
            @Schema(nullable = true) String parentKey,
            int position,
            NodeKind kind,
            @Schema(nullable = true) KpType kpType,
            String label,
            @Schema(nullable = true) String summary,
            @Schema(nullable = true) String definition,
            @Schema(nullable = true) String explanation,
            List<String> aliases,
            @Schema(nullable = true) String code,
            @Schema(nullable = true) String language,
            @Schema(nullable = true) String sourceSectionTitle,
            @Schema(nullable = true) String quote
    ) {
        public NodeContent content() {
            // 构建产物宽容截断(教师手工路径超长则 400)
            return new NodeContent(kind, label, kpType, summary, definition, explanation, aliases, code, language,
                    GraphRules.truncate(sourceSectionTitle, GraphRules.MAX_SOURCE_TITLE),
                    GraphRules.truncate(quote, GraphRules.MAX_QUOTE));
        }
    }

    public record PreviewEdge(
            String sourceKey,
            String targetKey,
            EdgeKind kind,
            @Schema(nullable = true) String evidence
    ) {
    }
}
