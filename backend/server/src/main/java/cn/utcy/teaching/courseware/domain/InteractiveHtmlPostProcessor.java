package cn.utcy.teaching.courseware.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 交互页 HTML 后处理:
 * 1) LaTeX 定界符归一:$$…$$ → \[…\],$…$ → \(…\)(script 块受保护不改写);
 * 2) 页面确有公式定界符且尚未引入 KaTeX 时,注入 KaTeX CDN 资源与自动渲染脚本。
 * 只在真出现公式时注入,不给无公式的自包含页面平白加 CDN 依赖。
 */
final class InteractiveHtmlPostProcessor {

    private InteractiveHtmlPostProcessor() {
    }

    private static final Pattern SCRIPT_BLOCK =
            Pattern.compile("<script[^>]*>[\\s\\S]*?</script>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISPLAY_MATH = Pattern.compile("\\$\\$([^$]+)\\$\\$");
    private static final Pattern INLINE_MATH = Pattern.compile("\\$([^$\\n]+?)\\$");
    private static final Pattern PLACEHOLDER = Pattern.compile("__SCRIPT_BLOCK_(\\d+)__");

    private static final String KATEX_INJECTION = """

            <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.css">
            <script src="https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.js"></script>
            <script src="https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/contrib/auto-render.min.js"></script>
            <script>
            document.addEventListener("DOMContentLoaded", function() {
                const katexOptions = {
                    delimiters: [
                        {left: '\\\\[', right: '\\\\]', display: true},
                        {left: '\\\\(', right: '\\\\)', display: false},
                        {left: '$$', right: '$$', display: true},
                        {left: '$', right: '$', display: false}
                    ],
                    throwOnError: false,
                    strict: false,
                    trust: true
                };

                let renderTimeout;
                function safeRender() {
                    if (renderTimeout) clearTimeout(renderTimeout);
                    renderTimeout = setTimeout(() => {
                        renderMathInElement(document.body, katexOptions);
                    }, 100);
                }

                renderMathInElement(document.body, katexOptions);

                const observer = new MutationObserver((mutations) => {
                    let shouldRender = false;
                    mutations.forEach((mutation) => {
                        if (mutation.target &&
                            mutation.target.className &&
                            typeof mutation.target.className === 'string' &&
                            mutation.target.className.includes('katex')) {
                            return;
                        }
                        shouldRender = true;
                    });
                    if (shouldRender) {
                        safeRender();
                    }
                });
                observer.observe(document.body, {
                    childList: true,
                    subtree: true,
                    characterData: true
                });
            });
            </script>""";

    static String postProcess(String html) {
        String processed = convertLatexDelimiters(html);
        if (!containsMathOutsideScripts(processed)
                || processed.toLowerCase().contains("katex")) {
            return processed;
        }
        return injectKatex(processed);
    }

    static String convertLatexDelimiters(String html) {
        List<String> scriptBlocks = new ArrayList<>();
        Matcher scripts = SCRIPT_BLOCK.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (scripts.find()) {
            scriptBlocks.add(scripts.group());
            scripts.appendReplacement(sb, "__SCRIPT_BLOCK_" + (scriptBlocks.size() - 1) + "__");
        }
        scripts.appendTail(sb);

        String processed = DISPLAY_MATH.matcher(sb).replaceAll("\\\\[$1\\\\]");
        processed = INLINE_MATH.matcher(processed).replaceAll("\\\\($1\\\\)");

        Matcher placeholders = PLACEHOLDER.matcher(processed);
        StringBuilder restored = new StringBuilder();
        while (placeholders.find()) {
            String block = scriptBlocks.get(Integer.parseInt(placeholders.group(1)));
            placeholders.appendReplacement(restored, Matcher.quoteReplacement(block));
        }
        placeholders.appendTail(restored);
        return restored.toString();
    }

    static boolean containsMathOutsideScripts(String html) {
        String withoutScripts = SCRIPT_BLOCK.matcher(html).replaceAll("");
        return withoutScripts.contains("\\(") || withoutScripts.contains("\\[")
                || withoutScripts.contains("$$");
    }

    private static String injectKatex(String html) {
        int headClose = html.indexOf("</head>");
        if (headClose != -1) {
            return html.substring(0, headClose) + KATEX_INJECTION + "\n" + html.substring(headClose);
        }
        int bodyClose = html.indexOf("</body>");
        if (bodyClose != -1) {
            return html.substring(0, bodyClose) + KATEX_INJECTION + "\n" + html.substring(bodyClose);
        }
        return html + KATEX_INJECTION;
    }
}
