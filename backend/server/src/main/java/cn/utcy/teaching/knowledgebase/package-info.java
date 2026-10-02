@org.springframework.modulith.ApplicationModule(
        displayName = "知识库",
        allowedDependencies = {"shared", "course::application", "resource::application", "ai::document", "ai::llm"})
package cn.utcy.teaching.knowledgebase;
