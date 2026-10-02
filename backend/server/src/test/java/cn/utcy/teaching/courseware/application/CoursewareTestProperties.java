package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.courseware.infrastructure.CoursewareProperties;


/** 测试用的课件配置(与 application.yml 的默认值同形) */
final class CoursewareTestProperties {

    private CoursewareTestProperties() {
    }

    static CoursewareProperties defaults() {
        return new CoursewareProperties("qwen-test", 0.3, 0.9, 4096, false, "qwen3-tts-flash", "Cherry",
                "qwen-image-2.0", false);
    }
}
