package cn.utcy.teaching.courseware.domain;

import cn.utcy.teaching.shared.util.Text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 交互页网页的格式契约——模型生成的与教师自己放进来的走同一套规则:
 * 一份自包含的完整 HTML 文档(恰好一个 &lt;/html&gt;),不联网取数(禁 fetch / XHR / WebSocket / 动态 import / @import),
 * 不嵌套文档(禁 iframe / embed / object),外部资源只许来自白名单 CDN;通过后做 LaTeX 定界归一与 KaTeX 注入。
 * 播放端把它放进 allow-scripts 的沙箱 iframe;白名单是质量约束(只依赖可靠 CDN),沙箱本身不拦网络。
 */
public final class InteractiveHtml {

    private InteractiveHtml() {
    }

    private static final Pattern HTML_FENCE = Pattern.compile("```html\\s*([\\s\\S]*?)```");
    private static final Pattern HTML_TAG = Pattern.compile("<html[\\s>]", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORBIDDEN_CAPABILITY = Pattern.compile(
            "@import\\b"
                    + "|\\bfetch\\s*\\(|\\bimport\\s*\\(|\\bXMLHttpRequest\\b|\\bWebSocket\\b"
                    + "|\\bEventSource\\b|\\bsendBeacon\\b|\\bnew\\s+(?:Shared)?Worker\\b"
                    + "|<(?:iframe|embed|object)\\b",
            Pattern.CASE_INSENSITIVE);
    /** 文档任意位置出现的绝对 URL(含协议相对):importmap、属性外链、url() 全覆盖 */
    private static final Pattern ABSOLUTE_URL = Pattern.compile(
            "(?:https?:)?//([a-z0-9.-]+\\.[a-z]{2,})(?=[/\"'\\s)]|$)",
            Pattern.CASE_INSENSITIVE);
    /** Three.js、KaTeX 等外部库只许从这些 CDN 加载 */
    private static final List<String> CDN_HOSTS = List.of("cdn.jsdelivr.net", "unpkg.com", "cdnjs.cloudflare.com");
    /** w3.org 是 SVG/XHTML 命名空间 URL,不产生请求 */
    private static final String NAMESPACE_HOST = "www.w3.org";

    public static String extract(String raw) {
        int doctype = raw.indexOf("<!DOCTYPE html>");
        int htmlOpen = doctype != -1 ? doctype : indexOfHtmlTag(raw);
        int htmlClose = raw.lastIndexOf("</html>");
        if (htmlOpen != -1 && htmlClose > htmlOpen) {
            return raw.substring(htmlOpen, htmlClose + "</html>".length());
        }
        Matcher fenced = HTML_FENCE.matcher(raw);
        if (fenced.find() && fenced.group(1).contains("</html>")) {
            return fenced.group(1).trim();
        }
        return null;
    }

    private static int indexOfHtmlTag(String raw) {
        Matcher m = HTML_TAG.matcher(raw);
        return m.find() ? m.start() : -1;
    }

    public static List<StageProblem> validate(String html) {
        List<StageProblem> errors = new ArrayList<>();
        int count = 0;
        int idx = 0;
        while ((idx = html.indexOf("</html>", idx)) != -1) {
            count++;
            idx += "</html>".length();
        }
        if (count != 1) {
            errors.add(new StageProblem("文档必须恰好包含一个 </html>", count == 0
                    ? "网页不完整,需要从 <html> 开始、到 </html> 结束的完整网页"
                    : "只能放一份网页,内容里出现了多个 </html>"));
        }
        Matcher forbidden = FORBIDDEN_CAPABILITY.matcher(html);
        if (forbidden.find()) {
            String found = Text.abbreviate(forbidden.group(), 80);
            errors.add(new StageProblem(
                    "使用了被禁能力(" + found + "…):禁止 @import/fetch/XHR/WebSocket/iframe,逻辑全部内联实现",
                    "网页里不能联网读取数据或嵌入其他网页(出现了:" + found + ")"));
        }
        Matcher url = ABSOLUTE_URL.matcher(html);
        Set<String> badHosts = new LinkedHashSet<>();
        while (url.find()) {
            String host = url.group(1).toLowerCase();
            if (!CDN_HOSTS.contains(host) && !NAMESPACE_HOST.equals(host)) {
                badHosts.add(host);
            }
        }
        if (!badHosts.isEmpty()) {
            errors.add(new StageProblem("引用了白名单之外的外部主机(" + String.join(", ", badHosts)
                    + "):只允许 " + String.join("/", CDN_HOSTS)
                    + ",其余资源一律内联,正文里也不要写无关网址",
                    "网页引用了不允许的外部地址:" + String.join("、", badHosts)
                            + "。外部库只能来自 " + String.join("、", CDN_HOSTS)));
        }
        return errors;
    }

    public record Prepared(String html, List<StageProblem> problems) {
    }

    public static Prepared prepare(String html) {
        if (html == null || html.isBlank()) {
            return new Prepared(null, List.of(new StageProblem("网页内容不能为空", "网页内容不能为空")));
        }
        List<StageProblem> errors = validate(html);
        if (!errors.isEmpty()) {
            return new Prepared(null, errors);
        }
        String processed = InteractiveHtmlPostProcessor.postProcess(html);
        List<StageProblem> processedErrors = validate(processed);
        return processedErrors.isEmpty() ? new Prepared(processed, List.of()) : new Prepared(null, processedErrors);
    }
}
