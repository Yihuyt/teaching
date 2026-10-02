package cn.utcy.teaching.knowledgegraph.domain;

import java.util.List;

/**
 * 一个节点的内容(不含结构位置):手工编辑与构建入库共用;
 * 文字栏按类型各归其名——章节的摘要 / 知识点的释义 / 代码示例的说明,互斥。
 * 出处(来源小节 + 原文引文)是知识点 / 代码示例的可选内容:构建入库自动填,教师可改可清。
 * 经 {@link GraphRules#normalizeContent} 规范化后才能写入。
 */
public record NodeContent(
        NodeKind kind,
        String label,
        KpType kpType,
        String summary,
        String definition,
        String explanation,
        List<String> aliases,
        String code,
        String language,
        String sourceSectionTitle,
        String quote
) {

    public static NodeContent unit(String label, String summary) {
        return new NodeContent(NodeKind.UNIT, label, null, summary, null, null, List.of(), null, null, null, null);
    }

    public static NodeContent knowledgePoint(String label, KpType kpType, String definition, List<String> aliases,
                                             String sourceSectionTitle, String quote) {
        return new NodeContent(NodeKind.KNOWLEDGE_POINT, label, kpType, null, definition, null, aliases, null, null,
                sourceSectionTitle, quote);
    }

    public static NodeContent codeExample(String label, String explanation, String code, String language,
                                          String sourceSectionTitle, String quote) {
        return new NodeContent(NodeKind.CODE_EXAMPLE, label, null, null, null, explanation, List.of(), code, language,
                sourceSectionTitle, quote);
    }
}
