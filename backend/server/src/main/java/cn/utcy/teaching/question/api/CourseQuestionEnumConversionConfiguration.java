package cn.utcy.teaching.question.api;

import cn.utcy.teaching.shared.web.StrictContractPropertyEditor;
import cn.utcy.teaching.question.domain.CourseQuestionType;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ControllerAdvice(basePackageClasses = CourseQuestionController.class)
class CourseQuestionEnumConversionConfiguration implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(
                String.class,
                CourseQuestionType.class,
                CourseQuestionType::fromValue);
    }

    @InitBinder
    void bindEnums(WebDataBinder binder) {
        binder.registerCustomEditor(
                CourseQuestionType.class,
                new StrictContractPropertyEditor<>(CourseQuestionType::fromValue));
    }
}
