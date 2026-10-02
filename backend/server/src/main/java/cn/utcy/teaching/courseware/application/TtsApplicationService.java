package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.ai.media.DashScopeTtsClient;
import cn.utcy.teaching.ai.media.DashScopeTtsClient.TtsResult;
import cn.utcy.teaching.shared.sse.SseSupport;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.courseware.domain.Stage;
import cn.utcy.teaching.courseware.infrastructure.CoursewareAssetStorage;
import cn.utcy.teaching.courseware.infrastructure.CoursewareEntity;
import cn.utcy.teaching.courseware.infrastructure.CoursewareProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * 讲稿语音合成:遍历缺音频的段,逐段合成写入 OSS 并回填 audioPath(对象键)。
 * 回写以库中最新版本为底、锁行合并 audioPath:合成期间文本被改过的段不回填(其对象由孤儿清理移除)。
 */
@Service
public class TtsApplicationService {

    private final CoursewareApplicationService coursewares;
    private final StageJsonCodec codec;
    private final DashScopeTtsClient ttsClient;
    private final CoursewareAssetStorage assetStorage;
    private final CoursewareProperties properties;
    private final CourseAccess courseAccess;
    private final CourseAiKeys aiKeys;
    private final CurrentActor currentActor;
    private final ObjectMapper objectMapper;
    private final TaskExecutor executor;
    private final TransactionOperations transactions;

    public TtsApplicationService(CoursewareApplicationService coursewares, StageJsonCodec codec,
                                 DashScopeTtsClient ttsClient, CoursewareAssetStorage assetStorage,
                                 CoursewareProperties properties, CourseAccess courseAccess,
                                 CurrentActor currentActor, ObjectMapper objectMapper,
                                 @Qualifier("sseTaskExecutor") TaskExecutor executor,
                                 CourseAiKeys aiKeys, TransactionOperations transactions) {
        this.coursewares = coursewares;
        this.codec = codec;
        this.ttsClient = ttsClient;
        this.assetStorage = assetStorage;
        this.properties = properties;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.aiKeys = aiKeys;
        this.transactions = transactions;
    }

    public SseEmitter synthesizeSse(long courseId, long coursewareId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        String apiKey = aiKeys.llmKeyForCourse(courseId);
        return SseSupport.run(executor, objectMapper, sink -> {
            Summary summary = synthesize(apiKey, courseId, coursewareId,
                    (done, total) -> sink.emit(Map.of("type", "progress", "done", done, "total", total)),
                    sink::cancelled);
            sink.emit(Map.of("type", "done", "coursewareId", coursewareId, "version", summary.version(),
                    "generated", summary.generated(), "skipped", summary.skipped()));
        });
    }

    public record Summary(int generated, int skipped, long version) {
    }

    private record SynthesizedSegment(String sceneId, int segIndex, String text, String audioPath) {
    }

    /**
     * 合成缺音频的段落;onProgress(done, total) 逐段回调;cancelled 为真时提前收尾(已合成的照常保存)。
     */
    public Summary synthesize(String apiKey, long courseId, long coursewareId,
                              BiConsumer<Integer, Integer> onProgress, BooleanSupplier cancelled) {
        Stage stage = coursewares.getInternal(courseId, coursewareId);

        record Job(String sceneId, int segIndex, String text) {
        }
        List<Job> jobs = new ArrayList<>();
        int skipped = 0;
        for (Stage.Scene scene : stage.scenes()) {
            for (int si = 0; si < scene.speech().size(); si++) {
                Stage.SpeechSegment segment = scene.speech().get(si);
                boolean hasAudio = segment.audioPath() != null && !segment.audioPath().isBlank();
                if (hasAudio) {
                    skipped++;
                    continue;
                }
                jobs.add(new Job(scene.id(), si, segment.text()));
            }
        }

        List<SynthesizedSegment> results = new ArrayList<>();
        int done = 0;
        for (Job job : jobs) {
            if (cancelled.getAsBoolean()) {
                break;
            }
            TtsResult result = ttsClient.synthesize(apiKey, properties.ttsModel(), properties.ttsVoice(), job.text());
            String filename = job.sceneId() + "-" + job.segIndex() + "-" + Ids.random(6) + "." + result.format();
            String contentType = "wav".equals(result.format()) ? "audio/wav" : "audio/mpeg";
            String audioPath = assetStorage.put(coursewareId, filename, result.audio(), contentType);
            results.add(new SynthesizedSegment(job.sceneId(), job.segIndex(), job.text(), audioPath));
            done++;
            onProgress.accept(done, jobs.size());
        }
        long version = mergeAndSave(courseId, coursewareId, results);
        return new Summary(results.size(), skipped, version);
    }

    /**
     * 锁行,以库中最新课件为底合并 audioPath 后写回。
     * 事务用 TransactionOperations 显式开启——从 synthesize 自调用时注解代理不生效,
     * 行锁会在语句返回的瞬间释放,并发编辑就会被静默覆盖。
     */
    long mergeAndSave(long courseId, long coursewareId, List<SynthesizedSegment> results) {
        return transactions.execute(status -> {
            CoursewareEntity locked = coursewares.requireForUpdate(courseId, coursewareId);
            Stage fresh = codec.fromJson(locked.getBody());
            if (results.isEmpty()) {
                return locked.getVersion() == null ? 0 : locked.getVersion();
            }
            return coursewares.saveLocked(locked, applyAudioPaths(fresh, results)).version();
        });
    }

    private Stage applyAudioPaths(Stage stage, List<SynthesizedSegment> results) {
        List<Stage.Scene> scenes = new ArrayList<>(stage.scenes());
        for (SynthesizedSegment seg : results) {
            for (int pi = 0; pi < scenes.size(); pi++) {
                Stage.Scene scene = scenes.get(pi);
                if (!scene.id().equals(seg.sceneId()) || seg.segIndex() >= scene.speech().size()) {
                    continue;
                }
                Stage.SpeechSegment current = scene.speech().get(seg.segIndex());
                // 已有真实音频(非强制重合成),或合成期间文本被编辑过:音频已过时,不回填
                boolean hasAudio = current.audioPath() != null && !current.audioPath().isBlank();
                if (hasAudio || !current.text().equals(seg.text())) {
                    break;
                }
                List<Stage.SpeechSegment> speech = new ArrayList<>(scene.speech());
                speech.set(seg.segIndex(), new Stage.SpeechSegment(current.text(), current.actions(), seg.audioPath()));
                scenes.set(pi, new Stage.Scene(scene.id(), scene.type(), scene.title(), scene.preset(),
                        scene.summary(), scene.blocks(), List.copyOf(speech), scene.layouts(), scene.interactive(), scene.video()));
                break;
            }
        }
        return new Stage(stage.title(), stage.theme(), List.copyOf(scenes));
    }
}
