package cn.utcy.teaching.courseware.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InteractiveHtmlPostProcessorTest {

    @Test
    void 定界符转换_多个script块原样还原() {
        String html = "<html><head><script>let a = '$x$';</script></head>"
                + "<body><p>$a+b$</p><script>let b = \"$$y$$\";</script></body></html>";
        String out = InteractiveHtmlPostProcessor.convertLatexDelimiters(html);
        assertThat(out).contains("\\(a+b\\)");
        assertThat(out).contains("let a = '$x$';");
        assertThat(out).contains("let b = \"$$y$$\";");
    }

    @Test
    void 已引入katex的页面不重复注入() {
        String html = "<html><head><script src=\"https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.js\">"
                + "</script></head><body><p>$a$</p></body></html>";
        String out = InteractiveHtmlPostProcessor.postProcess(html);
        assertThat(out.split("katex", -1).length - 1)
                .isEqualTo(html.split("katex", -1).length - 1);
    }

    @Test
    void 无head时注入到body末尾() {
        String html = "<html><body><p>$a+b$</p></body></html>";
        String out = InteractiveHtmlPostProcessor.postProcess(html);
        assertThat(out).contains("katex.min.css");
        assertThat(out.indexOf("katex.min.css")).isLessThan(out.indexOf("</body>"));
    }

    @Test
    void 反斜杠定界已存在时同样触发注入() {
        String html = "<html><head></head><body><p>\\(E=mc^2\\)</p></body></html>";
        assertThat(InteractiveHtmlPostProcessor.postProcess(html)).contains("katex.min.js");
    }
}
