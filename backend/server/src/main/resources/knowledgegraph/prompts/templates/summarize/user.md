# 元数据

- 教材:{{bookTitle}}
- 位置:{{path}}

{{#if sectionText}}
# 小节正文

{{sectionText}}
{{/if}}
{{#if childSummaries}}
# 子节点摘要(自底向上聚合模式)

{{childSummaries}}
{{/if}}

输出摘要 JSON。
