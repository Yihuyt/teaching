package cn.utcy.teaching.ai.document;

import cn.utcy.teaching.ai.infrastructure.AiMineruProperties;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MineruClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 响应包装_code0取data_非0抛出带msg() {
        assertThat(MineruClient.unwrap("{\"code\":0,\"data\":{\"task_id\":\"t1\"}}", mapper)
                .path("task_id").asText()).isEqualTo("t1");
        assertThatThrownBy(() -> MineruClient.unwrap(
                "{\"code\":-60012,\"msg\":\"api-token 已过期\"}", mapper))
                .isInstanceOf(MineruClient.MineruUnavailableException.class)
                .hasMessageContaining("api-token 已过期");
        assertThatThrownBy(() -> MineruClient.unwrap("<html>bad gateway</html>", mapper))
                .isInstanceOf(MineruClient.MineruUnavailableException.class)
                .hasMessageContaining("不是合法 JSON");
    }

    private static byte[] zip(String entryName, String content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("layout.json"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    void 页数上限_超限业务拒绝_未超放行() {
        MineruClient.requirePageLimit(800, 800);
        assertThatThrownBy(() -> MineruClient.requirePageLimit(801, 800))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("教材共 801 页,超过 800 页上限,请拆分为更小的文件再构建(重试原文件仍会失败)");
    }

    @Test
    void 分段与补齐_均分闭区间_缺页补空_多页报错() {
        assertThat(MineruClient.splitRanges(273, 200))
                .containsExactly(new int[]{1, 137}, new int[]{138, 273});
        assertThat(MineruClient.splitRanges(600, 200))
                .containsExactly(new int[]{1, 200}, new int[]{201, 400}, new int[]{401, 600});
        assertThat(MineruClient.splitRanges(150, 200)).containsExactly(new int[]{1, 150});

        assertThat(MineruClient.padToExpected(java.util.List.of("a", "b"), 4))
                .containsExactly("a", "b", "", "");
        assertThatThrownBy(() -> MineruClient.padToExpected(java.util.List.of("a", "b", "c"), 2))
                .isInstanceOf(MineruClient.MineruUnavailableException.class)
                .hasMessageContaining("页码无法对齐");
    }

    @Test
    void 结果包_取顶层或嵌套的fullmd() throws Exception {
        assertThat(MineruClient.extractFullMarkdown(zip("full.md", "# 教材\n正文")))
                .isEqualTo("# 教材\n正文");
        assertThat(MineruClient.extractFullMarkdown(zip("output/full.md", "# 嵌套")))
                .isEqualTo("# 嵌套");
        assertThatThrownBy(() -> MineruClient.extractFullMarkdown(zip("other.md", "x")))
                .isInstanceOf(MineruClient.MineruUnavailableException.class)
                .hasMessageContaining("full.md");
    }

    @Test
    void 轮询预算按页数_每百页5分钟_封顶30分钟() {
        assertThat(MineruClient.pollBudget(0)).isEqualTo(100);
        assertThat(MineruClient.pollBudget(120)).isEqualTo(200);
        assertThat(MineruClient.pollBudget(200)).isEqualTo(200);
        assertThat(MineruClient.pollBudget(2000)).isEqualTo(600);
    }

    @Test
    void 登记里有任务且云端已完成_直接取结果不再提交() throws Exception {
        FakeMineru cloud = new FakeMineru();
        cloud.tasks.put("t-old", "{\"code\":0,\"data\":{\"state\":\"done\",\"full_zip_url\":\"https://zip/old\"}}");
        MineruClient client = new MineruClient(cloud.http(), mapper, new AiMineruProperties("https://mineru.test"));
        java.util.Map<String, String> ledger = new java.util.HashMap<>(java.util.Map.of("all", "t-old"));

        byte[] result = client.runTask("key", "https://oss/book.pdf", null, 50, ledger(ledger), label -> { }, () -> false);

        assertThat(new String(result, StandardCharsets.UTF_8)).isEqualTo("zip:https://zip/old");
        assertThat(cloud.created).isZero();
        assertThat(ledger).containsExactly(java.util.Map.entry("all", "t-old"));
    }

    @Test
    void 登记里的任务已失败或不存在_重新提交并登记新任务() throws Exception {
        FakeMineru cloud = new FakeMineru();
        cloud.tasks.put("t-old", "{\"code\":0,\"data\":{\"state\":\"failed\",\"err_msg\":\"x\"}}");
        cloud.tasks.put("t-1-200", "{\"code\":0,\"data\":{\"state\":\"done\",\"full_zip_url\":\"https://zip/new\"}}");
        MineruClient client = new MineruClient(cloud.http(), mapper, new AiMineruProperties("https://mineru.test"));
        java.util.Map<String, String> ledger = new java.util.HashMap<>(java.util.Map.of("1-200", "t-old"));

        byte[] result = client.runTask("key", "https://oss/book.pdf", "1-200", 200, ledger(ledger), label -> { }, () -> false);

        assertThat(new String(result, StandardCharsets.UTF_8)).isEqualTo("zip:https://zip/new");
        assertThat(cloud.created).isEqualTo(1);
        assertThat(ledger).containsExactly(java.util.Map.entry("1-200", "t-1-200"));
    }

    @Test
    void 超过200页的整本Markdown解析_分段并按序拼接() throws Exception {
        FakeMineru cloud = new FakeMineru();
        cloud.sourceFile = pdfWithPages(201);
        // splitRanges(201,200) → [1-101],[102-201];两段各自完成,结果包里是各段的 full.md
        cloud.tasks.put("t-1-101", "{\"code\":0,\"data\":{\"state\":\"done\",\"full_zip_url\":\"https://zip/a\"}}");
        cloud.tasks.put("t-102-201", "{\"code\":0,\"data\":{\"state\":\"done\",\"full_zip_url\":\"https://zip/b\"}}");
        cloud.zips.put("a", zip("full.md", "# 前半"));
        cloud.zips.put("b", zip("full.md", "# 后半"));
        MineruClient client = new MineruClient(cloud.http(), mapper, new AiMineruProperties("https://mineru.test"));

        String markdown = client.parseToMarkdown("key", "https://oss/book.pdf", label -> { }, () -> false);

        assertThat(markdown).isEqualTo("# 前半\n\n# 后半");
        assertThat(cloud.created).isEqualTo(2);
    }

    private static byte[] pdfWithPages(int pages) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static MineruClient.TaskLedger ledger(java.util.Map<String, String> store) {
        return new MineruClient.TaskLedger() {
            @Override
            public String taskId(String segment) {
                return store.get(segment);
            }

            @Override
            public void record(String segment, String taskId) {
                store.put(segment, taskId);
            }
        };
    }

    /** 假 MinerU 云端:按 URL 应答原件下载 / 任务查询 / 创建 / 结果包下载;分段任务按 page_ranges 命名 */
    private static final class FakeMineru {
        final java.util.Map<String, String> tasks = new java.util.HashMap<>();
        final java.util.Map<String, byte[]> zips = new java.util.HashMap<>();
        byte[] sourceFile;
        int created;

        @SuppressWarnings("unchecked")
        java.net.http.HttpClient http() throws Exception {
            java.net.http.HttpClient http = org.mockito.Mockito.mock(java.net.http.HttpClient.class);
            org.mockito.Mockito.when(http.send(org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                    org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
                java.net.http.HttpRequest request = invocation.getArgument(0);
                String url = request.uri().toString();
                java.net.http.HttpResponse<Object> response = org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
                org.mockito.Mockito.when(response.statusCode()).thenReturn(200);
                Object body;
                if (url.equals("https://oss/book.pdf")) {
                    body = sourceFile;
                } else if (url.startsWith("https://zip/")) {
                    String key = url.substring("https://zip/".length());
                    body = zips.containsKey(key) ? zips.get(key) : ("zip:" + url).getBytes(StandardCharsets.UTF_8);
                } else if (request.method().equals("POST")) {
                    created++;
                    String payload = bodyOf(request);
                    String ranges = payload.contains("page_ranges")
                            ? payload.replaceAll(".*\"page_ranges\":\"([^\"]+)\".*", "$1") : "new";
                    body = "{\"code\":0,\"data\":{\"task_id\":\"t-" + ranges + "\"}}";
                } else {
                    String taskId = url.substring(url.lastIndexOf('/') + 1);
                    body = tasks.getOrDefault(taskId, "{\"code\":-1,\"msg\":\"task not found\"}");
                }
                org.mockito.Mockito.when(response.body()).thenReturn(body);
                return response;
            });
            return http;
        }

        private static String bodyOf(java.net.http.HttpRequest request) {
            var subscriber = java.net.http.HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
            var flow = new java.util.concurrent.SubmissionPublisher<java.nio.ByteBuffer>();
            request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<>() {
                @Override
                public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                    subscriber.onSubscribe(subscription);
                }

                @Override
                public void onNext(java.nio.ByteBuffer item) {
                    subscriber.onNext(java.util.List.of(item));
                }

                @Override
                public void onError(Throwable throwable) {
                    subscriber.onError(throwable);
                }

                @Override
                public void onComplete() {
                    subscriber.onComplete();
                }
            });
            flow.close();
            return subscriber.getBody().toCompletableFuture().join();
        }
    }
}
