# 课程信息

课程:{{courseTitle}}

# 本页大纲

- 页码:第 {{sceneNumber}} 页 / 共 {{totalScenes}} 页
- 标题:{{sceneTitle}}
- 页面类型:{{sceneType}}
- 布局预设:{{preset}}
{{#if summary}}
- 内容概要:{{summary}}
{{/if}}
{{#if keyPoints}}
- 要点(逐条落实,每条都要在本页内容里体现):
{{keyPoints}}
{{/if}}

{{#if imageElementEnabled}}
# 本页可用图片(教师素材里的图或按大纲画的配图,只能用这些 id)

{{assignedImages}}
{{/if}}

{{#if prevSummary}}
# 上一页概要(衔接用,不要重复其内容)

{{prevSummary}}
{{/if}}

{{#if existingBlocks}}
# 本页现有内容(重生成基准)

这是本页当前的块内容。请**在此基础上修改**:与调整要求无关的块,
内容和顺序尽量原样保留(id 也保留);只重写需要变的部分。

{{existingBlocks}}
{{/if}}

{{#if instruction}}
# 调整要求

{{instruction}}
{{/if}}

请输出本页的 blocks JSON。
