package cn.utcy.teaching.knowledgegraph.application;

import cn.utcy.teaching.shared.util.BoundedParallel;
import cn.utcy.teaching.ai.llm.AiUnavailableException;
import cn.utcy.teaching.ai.llm.LlmCalls;
import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.ai.llm.PromptLoader;
import cn.utcy.teaching.ai.structured.SchemaRegistry;
import cn.utcy.teaching.ai.structured.StructuredGenerator;
import cn.utcy.teaching.knowledgegraph.domain.SectionStatus;
import cn.utcy.teaching.knowledgegraph.infrastructure.BuildSectionEntity;
import cn.utcy.teaching.knowledgegraph.infrastructure.BuildSectionMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgegraphProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphExtractionConcurrencyTest {

    private static final ModelConfig MODEL = new ModelConfig("kg", "test", false, 0.2, 0.9, 8000);

    private final ObjectMapper json = new ObjectMapper();
    private final StructuredGenerator structured = mock(StructuredGenerator.class);
    private final SchemaRegistry schemas = mock(SchemaRegistry.class);
    private final BuildSectionMapper sections = mock(BuildSectionMapper.class);
    private final LlmCalls llm = mock(LlmCalls.class);
    private final ExecutorService pool = Executors.newFixedThreadPool(8);
    private final TaskExecutor executor = pool::execute;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();
    /** 前两次调用互相等待:证明确实并行(不靠时序碰运气),之后的调用直接通过 */
    private final CountDownLatch pairGate = new CountDownLatch(2);
    private final AtomicBoolean paired = new AtomicBoolean();

    private GraphExtractionService service() {
        when(schemas.rawSchema(anyString())).thenReturn(json.createObjectNode());
        KnowledgegraphProperties properties = new KnowledgegraphProperties("test", 0.2, 0.9, 8192, false,
                24000, 8_000_000, 800, "text-embedding-v4", 2, Duration.ofMinutes(20));
        return new GraphExtractionService(structured, schemas, new PromptLoader(json), MODEL, sections, json,
                executor, properties, llm);
    }

    /** 伪造模型:记录在途峰值,按 schemaKey 返回摘要或抽取结果;behaviour 可按节标题注入异常 */
    private void fakeModel(Function<String, RuntimeException> failureFor) {
        when(structured.generate(any())).thenAnswer(invocation -> {
            StructuredGenerator.Request<?> request = invocation.getArgument(0);
            calls.incrementAndGet();
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                pairGate.countDown();
                if (pairGate.await(3, TimeUnit.SECONDS)) {
                    paired.set(true);
                }
                Thread.sleep(40);
                RuntimeException failure = failureFor.apply(request.user());
                if (failure != null) {
                    throw failure;
                }
                JsonNode value = request.schemaKey().equals("summarize")
                        ? json.readTree("{\"summary\":\"摘要\",\"keywords\":[]}")
                        : json.readTree("{\"knowledgePoints\":[],\"relations\":[],\"codeExamples\":[]}");
                return new StructuredGenerator.Result<>(value, List.of(), 1);
            } finally {
                inFlight.decrementAndGet();
            }
        });
    }

    private static List<BuildSectionEntity> rows(int count) {
        List<BuildSectionEntity> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new BuildSectionEntity(1L, i, i, "", "第 " + i + " 节", "书 > 第 " + i + " 节", 1, 2));
        }
        return rows;
    }

    private static Map<Integer, String> texts(List<BuildSectionEntity> rows) {
        Map<Integer, String> texts = new LinkedHashMap<>();
        rows.forEach(row -> texts.put(row.getSectionIndex(), "正文 " + row.getTitle()));
        return texts;
    }

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("在途模型调用不超过 sectionConcurrency,全部小节完成即落库为 done,进度按完成数上报")
    void inFlightBounded() {
        fakeModel(user -> null);
        List<BuildSectionEntity> rows = rows(5);
        // 进度回调来自多个工作线程,收集器必须线程安全(裸 ArrayList 并发 add 会丢元素)
        List<Integer> progress = Collections.synchronizedList(new ArrayList<>());

        List<String> failures = service().extractSections("key", "书", rows, texts(rows),
                (done, total, title) -> progress.add(done), () -> false);

        assertThat(failures).isEmpty();
        assertThat(paired).isTrue();
        assertThat(maxInFlight.get()).isEqualTo(2);
        assertThat(rows).allMatch(row -> row.getStatus() == SectionStatus.DONE);
        assertThat(rows.get(0).getSummaryJson()).contains("摘要");
        assertThat(progress).hasSize(5).contains(5);
        assertThat(calls.get()).isEqualTo(10);
    }

    @Test
    @DisplayName("单节失败记录原因后继续,其余小节照常完成,失败清单带标题与原因")
    void failedSectionDoesNotStopOthers() {
        fakeModel(user -> user.contains("第 2 节") ? new IllegalStateException("模型输出不合法") : null);
        List<BuildSectionEntity> rows = rows(4);

        List<String> failures = service().extractSections("key", "书", rows, texts(rows),
                (done, total, title) -> { }, () -> false);

        assertThat(failures).containsExactly("第 2 节 — 模型输出不合法");
        assertThat(rows.get(2).getStatus()).isEqualTo(SectionStatus.FAILED);
        assertThat(rows.get(2).getErrorMessage()).isEqualTo("模型输出不合法");
        assertThat(rows).filteredOn(row -> row.getSectionIndex() != 2)
                .allMatch(row -> row.getStatus() == SectionStatus.DONE);
    }

    @Test
    @DisplayName("取消:不再提交新小节,在途的跑完后以「任务已取消」结束")
    void cancelStopsSubmitting() {
        fakeModel(user -> null);
        AtomicBoolean cancelled = new AtomicBoolean();
        List<BuildSectionEntity> rows = rows(8);

        assertThatThrownBy(() -> service().extractSections("key", "书", rows, texts(rows),
                (done, total, title) -> cancelled.set(true), cancelled::get))
                .isInstanceOf(IllegalStateException.class).hasMessage(BoundedParallel.CANCELLED);

        assertThat(inFlight.get()).isZero();
        assertThat(rows).filteredOn(row -> row.getStatus() == SectionStatus.PENDING).isNotEmpty();
        assertThat(rows).filteredOn(row -> row.getStatus() == SectionStatus.RUNNING).isEmpty();
    }

    @Test
    @DisplayName("重试只跑未完成的小节:已 done 的不再调用模型")
    void doneSectionsSkipped() {
        fakeModel(user -> null);
        List<BuildSectionEntity> rows = rows(3);
        rows.get(0).done("{\"summary\":\"旧\"}", "{}");

        service().extractSections("key", "书", rows, texts(rows), (done, total, title) -> { }, () -> false);

        assertThat(calls.get()).isEqualTo(4);
        assertThat(rows.get(0).getSummaryJson()).isEqualTo("{\"summary\":\"旧\"}");
    }

    @Test
    @DisplayName("去环前合并完全相同的重复边:取最长证据、保首现顺序;跨类型同对不合并;拆环一边只报一次")
    void duplicateRelationsCollapsedBeforeCycleRemoval() {
        var repeatWeak = new cn.utcy.teaching.knowledgegraph.domain.GraphRepairer.Relation(
                "嵌套if语句", "while语句格式", cn.utcy.teaching.knowledgegraph.domain.EdgeKind.PREREQUISITE, "短");
        var repeatStrong = new cn.utcy.teaching.knowledgegraph.domain.GraphRepairer.Relation(
                "嵌套if语句", "while语句格式", cn.utcy.teaching.knowledgegraph.domain.EdgeKind.PREREQUISITE, "更长的证据");
        var reverse = new cn.utcy.teaching.knowledgegraph.domain.GraphRepairer.Relation(
                "while语句格式", "嵌套if语句", cn.utcy.teaching.knowledgegraph.domain.EdgeKind.PREREQUISITE, "很长很长很长的证据");
        var relatedSamePair = new cn.utcy.teaching.knowledgegraph.domain.GraphRepairer.Relation(
                "嵌套if语句", "while语句格式", cn.utcy.teaching.knowledgegraph.domain.EdgeKind.RELATED, "相关");

        var deduped = GraphExtractionService.dedupeRelations(
                java.util.List.of(repeatWeak, relatedSamePair, repeatStrong, repeatWeak, reverse));

        // 同边三份并成一条,取最长证据,位置保留首现;「相关」同对不受影响;反向边是另一条边
        assertThat(deduped).containsExactly(repeatStrong, relatedSamePair, reverse);

        var removal = cn.utcy.teaching.knowledgegraph.domain.GraphRepairer.removePrerequisiteCycles(deduped);
        assertThat(removal.removedEdges()).containsExactly("嵌套if语句 → while语句格式");
        assertThat(removal.relations()).containsExactly(reverse, relatedSamePair);
    }

    @Test
    @DisplayName("代码示例绑定名与关系端点同表重写:合并改名不失联,并成同一知识点去重")
    void codeBindingsRewrittenThroughMergeTable() {
        var code = new cn.utcy.teaching.knowledgegraph.domain.GraphAssembler.CodeExample(
                "累加求和", "c", "int s=0;", "循环累加", java.util.List.of("s += i;"),
                java.util.List.of("累加", "for循环", "循环语句"));
        var rewritten = GraphExtractionService.rewriteCodeBindings(java.util.List.of(code),
                java.util.Map.of("累加", "循环累加求和", "for循环", "for语句", "循环语句", "for语句"));

        assertThat(rewritten).hasSize(1);
        assertThat(rewritten.get(0).bindKpNames()).containsExactly("循环累加求和", "for语句");
        assertThat(rewritten.get(0).title()).isEqualTo("累加求和");
        assertThat(rewritten.get(0).evidenceQuotes()).containsExactly("s += i;");
    }

    @Test
    @DisplayName("传输类错误退避重试,重试成功即完成")
    void transportErrorsRetried() {
        AtomicInteger transportFailures = new AtomicInteger(1);
        fakeModel(user -> transportFailures.getAndDecrement() > 0
                ? new AiUnavailableException("无法连接大模型服务") : null);
        List<BuildSectionEntity> rows = rows(1);

        List<String> failures = service().extractSections("key", "书", rows, texts(rows),
                (done, total, title) -> { }, () -> false);

        assertThat(failures).isEmpty();
        assertThat(rows.get(0).getStatus()).isEqualTo(SectionStatus.DONE);
        assertThat(calls.get()).isEqualTo(3);
    }
}
