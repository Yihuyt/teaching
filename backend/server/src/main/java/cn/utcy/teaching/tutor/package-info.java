@org.springframework.modulith.ApplicationModule(
        displayName = "智能问答",
        allowedDependencies = {"shared", "course::application", "knowledgebase::application", "retrieval::application", "identity::application", "ai::agent", "ai::llm"}
)
package cn.utcy.teaching.tutor;
