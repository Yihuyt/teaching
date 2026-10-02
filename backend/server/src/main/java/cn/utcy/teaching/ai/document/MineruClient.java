package cn.utcy.teaching.ai.document;

import cn.utcy.teaching.ai.infrastructure.AiMineruProperties;

import cn.utcy.teaching.shared.util.Text;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * MinerU 云端文档解析客户端(mineru.net API v4):
 * 建任务(传文件的预签名 URL)→ 轮询直到 done → 下载结果 zip → 取 full.md。
 * 密钥由调用方按用户级配置解密传入,本类不持有任何密钥。
 */
@Component
public class MineruClient {

    /** 轮询间隔;等待预算按页数给:每 100 页 5 分钟,最少 5 分钟、最多 30 分钟(云端排队高峰不该把大书判成超时) */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(3);
    private static final int POLLS_PER_100_PAGES = 100;
    private static final int MAX_POLLS = 600;
    /** MinerU 云端单次解析的页数上限:超过则服务端切块逐块解析 */
    private static final int MAX_PAGES_PER_PARSE = 200;
    /** 分块解析的并行度:云端任务独立,但别把限流打满 */
    private static final int CHUNK_PARALLELISM = 2;
    private static final long MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024;
    /** 单请求墙钟:API 调用与文件下载分别设限,挂死的连接不能永久占住任务 */
    private static final Duration API_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public static class MineruUnavailableException extends RuntimeException {
        public MineruUnavailableException(String message) {
            super(message);
        }

        public MineruUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 已提交任务的登记:段键(整本 "all" / 页区间 "start-end")→ MinerU 任务 id。
     * 调用方持久化,构建被接管续跑时直接续接云端仍在跑或已完成的任务,不重复提交。
     */
    public interface TaskLedger {
        TaskLedger NONE = new TaskLedger() {
            @Override
            public String taskId(String segment) {
                return null;
            }

            @Override
            public void record(String segment, String taskId) {
            }
        };

        String taskId(String segment);

        void record(String segment, String taskId);
    }

    public MineruClient(HttpClient aiHttpClient, ObjectMapper objectMapper,
                        AiMineruProperties properties) {
        this.httpClient = aiHttpClient;
        this.objectMapper = objectMapper;
        String base = properties.baseUrl();
        this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /**
     * 解析文档为 Markdown(知识库入库、出题附件用)。fileUrl 必须是 MinerU 服务端可访问的地址(OSS 预签名 URL)。
     * 超过 MinerU 单次 200 页上限的 PDF 自动按 page_ranges 分段并行解析,各段 Markdown 按页序拼接。
     * onProgress 收人话进度;cancelled 为真时尽快停止轮询并抛出取消。
     */
    public String parseToMarkdown(String apiKey, String fileUrl,
                                  Consumer<String> onProgress, BooleanSupplier cancelled) {
        int pageCount = PdfPages.pageCount(downloadFile(fileUrl));
        if (pageCount <= MAX_PAGES_PER_PARSE) {
            return extractFullMarkdown(runTask(apiKey, fileUrl, null, pageCount, TaskLedger.NONE,
                    onProgress, cancelled));
        }
        List<int[]> ranges = splitRanges(pageCount, MAX_PAGES_PER_PARSE);
        onProgress.accept("全文 " + pageCount + " 页超过单次解析上限(" + MAX_PAGES_PER_PARSE
                + " 页),按 page_ranges 分 " + ranges.size() + " 段并行解析…");
        return String.join("\n\n", parseSegmented(apiKey, fileUrl, ranges, TaskLedger.NONE,
                onProgress, cancelled, (zip, range) -> extractFullMarkdown(zip)));
    }

    /**
     * 解析出的一张文档图片:字节、结果包内路径、页码(1 起)、MinerU 图注(可空)、像素尺寸(读不出为 0)。
     */
    public record ParsedImage(byte[] bytes, String path, String contentType, int pageNumber,
                              String description, int width, int height) {
    }

    public record ParsedDocument(String markdown, List<ParsedImage> images, int pageCount) {
    }

    /**
     * 解析文档为 Markdown 并带出图片(图片的页码 / 图注来自结果包 *_content_list.json,
     * 找不到对应条目时页码为 0、图注为空)。
     */
    public ParsedDocument parseDocument(String apiKey, String fileUrl,
                                        Consumer<String> onProgress, BooleanSupplier cancelled) {
        return extractDocument(runTask(apiKey, fileUrl, null, 0, TaskLedger.NONE, onProgress, cancelled), objectMapper);
    }

    /**
     * 解析文档为分页纯文本(页码 = 列表下标 + 1,来自结果包 layout.json)。
     * 教材类流程用它:页码是目录确认与出处定位的锚点。
     * 超过 MinerU 单次 200 页上限的文档由服务端按页切块、逐块经文件上传通道解析,
     * 分页文本按原页序拼接——页码锚点不受切块影响,教师无感知。
     */
    public List<String> parseToPages(String apiKey, String fileUrl, int maxPages, TaskLedger ledger,
                                     Consumer<String> onProgress, BooleanSupplier cancelled) {
        byte[] pdf = downloadFile(fileUrl);
        int pageCount = PdfPages.pageCount(pdf);
        requirePageLimit(pageCount, maxPages);
        if (pageCount <= MAX_PAGES_PER_PARSE) {
            return padToExpected(MineruLayoutPages.fromLayoutJson(
                    extractLayoutJson(runTask(apiKey, fileUrl, null, pageCount, ledger, onProgress, cancelled)),
                    objectMapper), pageCount);
        }
        List<int[]> ranges = splitRanges(pageCount, MAX_PAGES_PER_PARSE);
        onProgress.accept("全书 " + pageCount + " 页超过单次解析上限(" + MAX_PAGES_PER_PARSE
                + " 页),按 page_ranges 分 " + ranges.size() + " 段并行解析…");
        List<String> pages = new ArrayList<>();
        parseSegmented(apiKey, fileUrl, ranges, ledger, onProgress, cancelled,
                (zip, range) -> padToExpected(MineruLayoutPages.fromLayoutJson(
                        extractLayoutJson(zip), objectMapper), range[1] - range[0] + 1))
                .forEach(pages::addAll);
        return pages;
    }

    private interface SegmentExtractor<T> {
        T extract(byte[] zipBytes, int[] range);
    }

    private <T> List<T> parseSegmented(String apiKey, String fileUrl, List<int[]> ranges, TaskLedger ledger,
                                       Consumer<String> onProgress, BooleanSupplier cancelled,
                                       SegmentExtractor<T> extractor) {
        Semaphore parallelism = new Semaphore(CHUNK_PARALLELISM);
        try (ExecutorService segmentPool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < ranges.size(); i++) {
                int index = i + 1;
                int[] range = ranges.get(i);
                futures.add(segmentPool.submit(() -> {
                    parallelism.acquire();
                    try {
                        byte[] zip = runTask(apiKey, fileUrl, range[0] + "-" + range[1], range[1] - range[0] + 1,
                                ledger, label -> onProgress.accept("第 " + index + "/" + ranges.size() + " 段:" + label),
                                cancelled);
                        return extractor.extract(zip, range);
                    } finally {
                        parallelism.release();
                    }
                }));
            }
            List<T> results = new ArrayList<>(futures.size());
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MineruUnavailableException("等待分段解析时线程被中断", exception);
        } catch (ExecutionException exception) {
            throw exception.getCause() instanceof RuntimeException runtime
                    ? runtime : new MineruUnavailableException("分段解析失败", exception.getCause());
        }
    }

    /** 总页数均分为 ≤maxPer 的闭区间段(1 起):600 页 → 200/200/200,不出现失衡尾段 */
    static List<int[]> splitRanges(int totalPages, int maxPer) {
        int segments = (totalPages + maxPer - 1) / maxPer;
        int per = (totalPages + segments - 1) / segments;
        List<int[]> ranges = new ArrayList<>(segments);
        for (int start = 1; start <= totalPages; start += per) {
            ranges.add(new int[]{start, Math.min(start + per - 1, totalPages)});
        }
        return ranges;
    }

    /** 解析结果补齐到期望页数:MinerU 可能略去末尾空白页,缺的补空串保住页码锚点;多了即契约破裂,明确报错 */
    static List<String> padToExpected(List<String> pages, int expected) {
        if (pages.size() > expected) {
            throw new MineruUnavailableException("MinerU 返回页数(" + pages.size()
                    + ")超过请求页数(" + expected + "),页码无法对齐");
        }
        List<String> padded = new ArrayList<>(pages);
        while (padded.size() < expected) {
            padded.add("");
        }
        return padded;
    }

    byte[] runTask(String apiKey, String fileUrl, String pageRanges, int pages, TaskLedger ledger,
                   Consumer<String> onProgress, BooleanSupplier cancelled) {
        String segment = pageRanges == null ? "all" : pageRanges;
        int maxPolls = pollBudget(pages);
        String previous = ledger.taskId(segment);
        if (previous != null) {
            JsonNode current = null;
            try {
                current = pollTask(apiKey, previous);
            } catch (MineruUnavailableException exception) {
                // 任务已不存在(或此刻查不到):重新提交
            }
            if (current != null && !"failed".equals(current.path("state").asText(""))) {
                onProgress.accept("续接上次提交的解析任务…");
                byte[] result = settled(current, onProgress);
                return result != null ? result
                        : awaitResult(() -> pollTask(apiKey, previous), maxPolls, onProgress, cancelled);
            }
        }
        String taskId = createTask(apiKey, fileUrl, pageRanges);
        ledger.record(segment, taskId);
        onProgress.accept("解析任务已提交,等待 MinerU 处理…");
        return awaitResult(() -> pollTask(apiKey, taskId), maxPolls, onProgress, cancelled);
    }

    static int pollBudget(int pages) {
        int hundreds = Math.max(1, (pages + 99) / 100);
        return Math.min(hundreds * POLLS_PER_100_PAGES, MAX_POLLS);
    }

    /** 轮询直到 done(返回结果包字节)/failed/超时;解析任务的统一状态机。
     * 轮询是幂等 GET,瞬时网络故障重试而非放弃——任务在云端仍在跑,
     * 一次抖动不该杀掉跑了十分钟的解析(连续 3 次失败才算真不可用)。 */
    private byte[] awaitResult(java.util.function.Supplier<JsonNode> poller, int maxPolls,
                               Consumer<String> onProgress, BooleanSupplier cancelled) {
        int consecutivePollFailures = 0;
        for (int i = 0; i < maxPolls; i++) {
            if (cancelled.getAsBoolean()) {
                throw new MineruUnavailableException("解析已取消");
            }
            sleep();
            JsonNode data;
            try {
                data = poller.get();
                consecutivePollFailures = 0;
            } catch (MineruUnavailableException exception) {
                if (++consecutivePollFailures >= 3) {
                    throw exception;
                }
                continue;
            }
            byte[] result = settled(data, onProgress);
            if (result != null) {
                return result;
            }
        }
        throw new MineruUnavailableException("解析超时(超过 " + maxPolls * POLL_INTERVAL.toSeconds()
                + " 秒未完成),请稍后重试");
    }

    private byte[] settled(JsonNode data, Consumer<String> onProgress) {
        String state = data.path("state").asText("");
        switch (state) {
            case "done" -> {
                String zipUrl = data.path("full_zip_url").asText("");
                if (zipUrl.isBlank()) {
                    throw new MineruUnavailableException("解析完成但未返回结果地址");
                }
                onProgress.accept("解析完成,正在获取结果…");
                return downloadZip(zipUrl);
            }
            case "failed" -> throw new MineruUnavailableException(
                    failureMessage(data.path("err_msg").asText("未知原因")));
            case "" -> throw new MineruUnavailableException("MinerU 返回了无法识别的任务状态");
            default -> {
                onProgress.accept(progressLabel(state, data));
                return null;
            }
        }
    }

    /** 页数上限在花任何解析费用之前检查:超限是业务拒绝,不是服务故障 */
    static void requirePageLimit(int pageCount, int maxPages) {
        if (pageCount > maxPages) {
            throw new IllegalArgumentException("教材共 " + pageCount + " 页,超过 " + maxPages
                    + " 页上限,请拆分为更小的文件再构建(重试原文件仍会失败)");
        }
    }

    private byte[] downloadFile(String fileUrl) {
        try {
            HttpResponse<byte[]> response = httpClient.send(HttpRequest.newBuilder()
                            .uri(URI.create(fileUrl)).timeout(DOWNLOAD_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new MineruUnavailableException("下载教材文件失败 HTTP " + response.statusCode());
            }
            if (response.body().length > MAX_DOWNLOAD_BYTES) {
                throw new MineruUnavailableException("教材文件超过 " + (MAX_DOWNLOAD_BYTES / 1024 / 1024)
                        + "MB,请压缩或拆分后重试");
            }
            return response.body();
        } catch (IOException e) {
            throw new MineruUnavailableException("下载教材文件失败:" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MineruUnavailableException("下载教材文件时线程被中断", e);
        }
    }

    private String createTask(String apiKey, String fileUrl, String pageRanges) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("url", fileUrl);
        body.put("is_ocr", true);
        body.put("enable_formula", true);
        // vlm = MinerU 新一代视觉语言模型解析引擎(默认 pipeline):复杂版面/表格/公式更准,
        // 结果包结构与 pipeline 同款(2026-08-29 真机实测),转换与分段逻辑不受影响
        body.put("model_version", "vlm");
        if (pageRanges != null) {
            body.put("page_ranges", pageRanges);
        }
        JsonNode data = exchange(apiKey, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v4/extract/task"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)));
        String taskId = data.path("task_id").asText("");
        if (taskId.isBlank()) {
            throw new MineruUnavailableException("MinerU 未返回任务 id");
        }
        return taskId;
    }

    private JsonNode pollTask(String apiKey, String taskId) {
        return exchange(apiKey, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v4/extract/task/" + taskId))
                .GET());
    }

    private JsonNode exchange(String apiKey, HttpRequest.Builder builder) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(
                    builder.timeout(API_TIMEOUT).header("Authorization", "Bearer " + apiKey).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new MineruUnavailableException("无法连接 MinerU 服务:" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MineruUnavailableException("等待 MinerU 服务时线程被中断", e);
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new MineruUnavailableException("MinerU 密钥无效或无权限(HTTP "
                    + response.statusCode() + "),请检查课程配置里的密钥");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new MineruUnavailableException("MinerU 请求失败 HTTP " + response.statusCode()
                    + ": " + Text.abbreviate(response.body(), 300));
        }
        return unwrap(response.body(), objectMapper);
    }

    static JsonNode unwrap(String body, ObjectMapper mapper) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException e) {
            throw new MineruUnavailableException("MinerU 响应不是合法 JSON: " + Text.abbreviate(body, 300), e);
        }
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            throw new MineruUnavailableException("MinerU 返回错误(code=" + code + "): "
                    + root.path("msg").asText("无说明"));
        }
        return root.path("data");
    }

    private byte[] downloadZip(String zipUrl) {
        try {
            HttpResponse<byte[]> response = httpClient.send(
                    HttpRequest.newBuilder().uri(URI.create(zipUrl)).timeout(DOWNLOAD_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new MineruUnavailableException("解析结果下载失败 HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new MineruUnavailableException("解析结果下载失败:" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MineruUnavailableException("下载解析结果时线程被中断", e);
        }
    }

    /** 从结果 zip 中取出 layout.json 的原始字节(流式转换,不在这里建树;供单测直测) */
    static byte[] extractLayoutJson(byte[] zipBytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory()
                        && (name.equals("layout.json") || name.endsWith("/layout.json"))) {
                    return zip.readAllBytes();
                }
            }
        } catch (IOException e) {
            throw new MineruUnavailableException("解析结果包读取失败:" + e.getMessage(), e);
        }
        throw new MineruUnavailableException("解析结果包中没有 layout.json");
    }

    static String extractFullMarkdown(byte[] zipBytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory() && (name.equals("full.md") || name.endsWith("/full.md"))) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            throw new MineruUnavailableException("解析结果包读取失败:" + e.getMessage(), e);
        }
        throw new MineruUnavailableException("解析结果包中没有 full.md");
    }

    static ParsedDocument extractDocument(byte[] zipBytes, ObjectMapper mapper) {
        String markdown = null;
        JsonNode contentList = null;
        java.util.Map<String, byte[]> imageBytes = new java.util.LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (base.equals("full.md")) {
                    markdown = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                } else if (base.endsWith("_content_list.json")) {
                    contentList = mapper.readTree(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                } else if (name.contains("images/") && isImageName(base)) {
                    imageBytes.put("images/" + base, zip.readAllBytes());
                }
            }
        } catch (IOException e) {
            throw new MineruUnavailableException("解析结果包读取失败:" + e.getMessage(), e);
        }
        if (markdown == null) {
            throw new MineruUnavailableException("解析结果包中没有 full.md");
        }
        // content_list 条目按 img_path(全路径与文件名两种键)索引,取页码与图注
        java.util.Map<String, JsonNode> byPath = new java.util.HashMap<>();
        java.util.Set<Integer> pages = new java.util.HashSet<>();
        if (contentList != null && contentList.isArray()) {
            for (JsonNode item : contentList) {
                if (item.has("page_idx")) {
                    pages.add(item.path("page_idx").asInt());
                }
                if (!"image".equals(item.path("type").asText(""))) {
                    continue;
                }
                String path = item.path("img_path").asText("");
                if (path.isEmpty()) {
                    continue;
                }
                byPath.put(path, item);
                byPath.put(path.substring(path.lastIndexOf('/') + 1), item);
            }
        }
        List<ParsedImage> images = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, byte[]> entry : imageBytes.entrySet()) {
            String path = entry.getKey();
            String base = path.substring(path.lastIndexOf('/') + 1);
            JsonNode meta = byPath.getOrDefault(path, byPath.get(base));
            int page = meta == null ? 0 : meta.path("page_idx").asInt(-1) + 1;
            String caption = "";
            if (meta != null && meta.path("image_caption").isArray() && !meta.path("image_caption").isEmpty()) {
                caption = meta.path("image_caption").get(0).asText("");
            }
            int[] size = imageSize(entry.getValue());
            images.add(new ParsedImage(entry.getValue(), path, contentTypeOf(base), page, caption,
                    size[0], size[1]));
        }
        return new ParsedDocument(markdown, images, pages.size());
    }

    private static boolean isImageName(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    private static String contentTypeOf(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/jpeg";
    }

    /** 读像素尺寸(ImageIO 不认的格式返回 0×0,调用方按未知尺寸处理) */
    private static int[] imageSize(byte[] bytes) {
        try (javax.imageio.stream.ImageInputStream in =
                     javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            java.util.Iterator<javax.imageio.ImageReader> readers = javax.imageio.ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return new int[]{0, 0};
            }
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            return new int[]{0, 0};
        }
    }

    private static String failureMessage(String errMsg) {
        if (errMsg.contains("exceeds limit") && errMsg.contains("page")) {
            return "文档超过 MinerU 云端 200 页上限,请拆分文档后重试(重试原文件仍会失败)";
        }
        return "MinerU 解析失败:" + errMsg;
    }

    private static String progressLabel(String state, JsonNode data) {
        if ("running".equals(state)) {
            JsonNode progress = data.path("extract_progress");
            int extracted = progress.path("extracted_pages").asInt(-1);
            int total = progress.path("total_pages").asInt(-1);
            if (extracted >= 0 && total > 0) {
                return "正在解析… " + extracted + "/" + total + " 页";
            }
            return "正在解析…";
        }
        if ("converting".equals(state)) {
            return "正在转换文档格式…";
        }
        return "排队等待解析…";
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MineruUnavailableException("等待解析时线程被中断", e);
        }
    }
}
