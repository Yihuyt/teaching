# 课程信息

课程:{{courseTitle}}
本页:{{sceneTitle}}

# 仿真概念

{{concept}}

# 本页大纲(内容依据)

{{#if summary}}
{{summary}}
{{/if}}
{{#if keyPoints}}

# 要点(学生要探索或做到的事,逐条落实)

{{keyPoints}}
{{/if}}

{{#if keyVariables}}
# 需要暴露给学生调节的变量

{{keyVariables}}

每个变量做一个滑块(标注名称、当前值、单位),变化实时反映到可视化。
{{/if}}

{{#if instruction}}
# 教师的调整要求

{{instruction}}

{{/if}}
请输出完整 HTML 文档(控件面板 + Canvas/SVG 可视化 + 预设按钮 + `reset-btn` 重置按钮)。
