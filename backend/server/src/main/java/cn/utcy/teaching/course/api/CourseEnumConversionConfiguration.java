package cn.utcy.teaching.course.api;

import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.web.StrictContractPropertyEditor;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ControllerAdvice(basePackageClasses = CourseController.class)
class CourseEnumConversionConfiguration implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(
                String.class,
                CourseOutlineItemType.class,
                CourseOutlineItemType::fromValue);
    }

    @InitBinder
    void bindEnums(WebDataBinder binder) {
        binder.registerCustomEditor(
                CourseOutlineItemType.class,
                new StrictContractPropertyEditor<>(CourseOutlineItemType::fromValue));
    }
}
