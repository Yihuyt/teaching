package cn.utcy.teaching.programming.api;

import cn.utcy.teaching.shared.web.StrictContractPropertyEditor;
import cn.utcy.teaching.programming.domain.ProblemDifficulty;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ControllerAdvice(basePackageClasses = CourseProgrammingProblemController.class)
class ProgrammingEnumConversionConfiguration implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, ProblemDifficulty.class, ProblemDifficulty::fromValue);
    }

    @InitBinder
    void bindEnums(WebDataBinder binder) {
        binder.registerCustomEditor(
                ProblemDifficulty.class,
                new StrictContractPropertyEditor<>(ProblemDifficulty::fromValue));
    }
}
