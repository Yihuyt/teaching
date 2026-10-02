@org.springframework.modulith.ApplicationModule(
        displayName = "智能课堂",
        allowedDependencies = {"shared", "course::application", "identity::application", "ai::document", "ai::llm", "ai::media", "ai::structured"})
package cn.utcy.teaching.courseware;
