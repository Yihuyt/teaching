@org.springframework.modulith.ApplicationModule(
        displayName = "知识图谱",
        allowedDependencies = {"shared", "course::application", "resource::application", "ai::document", "ai::llm", "ai::structured"}
)
package cn.utcy.teaching.knowledgegraph;
