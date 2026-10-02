# 课程信息

课程:{{courseTitle}}
本页:{{sceneTitle}}

# 图解形态

{{diagramType}}

# 本页大纲(内容依据)

{{#if summary}}
{{summary}}
{{/if}}
{{#if keyPoints}}

# 要点(学生要探索或做到的事,逐条落实)

{{keyPoints}}
{{/if}}

{{#if hasNodeCount}}
# 节点数约束

- 节点数不超过 {{nodeCount}} 个(给了指定节点清单时以清单为准)
{{/if}}

{{#if hasPrescribedNodes}}
# 指定节点清单

{{prescribedNodes}}

- 清单里的每个节点恰好用一次,保留其 `id`/`label`/`icon`/`details`
- 不得增删或替换清单节点;有 `parentId` 时据此推导层级连线
{{/if}}

{{#if instruction}}
# 教师的调整要求

{{instruction}}

{{/if}}
请输出完整 HTML 文档(SVG 节点图 + 箭头连线 + 下一步/上一步逐步揭示 + 点击看详情)。
