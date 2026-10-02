package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.learning.LearningEvent;
import cn.utcy.teaching.shared.learning.LearningEventRecorder;
import cn.utcy.teaching.shared.learning.LearningEventType;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.ai.media.DashScopeTtsClient;
import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.ai.llm.PromptLoader;
import cn.utcy.teaching.ai.structured.SchemaRegistry;
import cn.utcy.teaching.shared.sse.SseSupport;
import cn.utcy.teaching.ai.structured.StructuredGenerator;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.courseware.domain.Stage;
import cn.utcy.teaching.courseware.domain.StageValidator;
import cn.utcy.teaching.courseware.domain.Action;
import cn.utcy.teaching.courseware.infrastructure.CoursewareProperties;
import cn.utcy.teaching.courseware.infrastructure.QaEventEntity;
import cn.utcy.teaching.courseware.infrastructure.QaEventMapper;
import cn.utcy.teaching.courseware.infrastructure.TextFieldStreamer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class CoursewareQaService {

    private static final Logger log = LoggerFactory.getLogger(CoursewareQaService.class);

    private static final int RECENT_SPEECH_SEGMENTS = 6;

    private final CoursewareApplicationService coursewares;
    private final SchemaRegistry schemas;
    private final PromptLoader prompts;
    private final StructuredGenerator structured;
    private final ModelConfig llmModel;
    private final ObjectMapper objectMapper;
    /** 模型输出专用宽松解析器(见 LlmOutputMappers) */
    private final ObjectMapper llmMapper;
    private final DashScopeTtsClient ttsClient;
    private final CoursewareProperties properties;
    private final QaEventMapper qaEvents;
    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;
    private final TaskExecutor sseExecutor;
    private final CourseAiKeys aiKeys;
    private final LearningEventRecorder learningEvents;

    public CoursewareQaService(CoursewareApplicationService coursewares,
                               @Qualifier("coursewareSchemas") SchemaRegistry schemas,
                               PromptLoader prompts, StructuredGenerator structured,
                               @Qualifier("coursewareLlmModel") ModelConfig llmModel,
                               ObjectMapper objectMapper, DashScopeTtsClient ttsClient,
                               CoursewareProperties properties, QaEventMapper qaEvents,
                               CourseAccess courseAccess, CurrentActor currentActor,
                               @Qualifier("sseTaskExecutor") TaskExecutor sseExecutor,
                               CourseAiKeys aiKeys,
            LearningEventRecorder learningEvents) {
        this.coursewares = coursewares;
        this.schemas = schemas;
        this.prompts = prompts;
        this.structured = structured;
        this.llmModel = llmModel;
        this.objectMapper = objectMapper;
        this.llmMapper = cn.utcy.teaching.ai.structured.LlmOutputMappers.lenient(objectMapper);
        this.ttsClient = ttsClient;
        this.properties = properties;
        this.qaEvents = qaEvents;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
        this.sseExecutor = sseExecutor;
        this.aiKeys = aiKeys;
        this.learningEvents = learningEvents;
    }

    /** audio 为 null 表示语音合成失败(回答本身不受影响,纯文本呈现) */
    public record Answer(String text, List<AnswerAction> actions, AnswerAudio audio) {
    }

    /**
     * 回答动作(扁平结构,type 只会是 highlight——服务端已过滤):
     * 多态的 Action 在 OpenAPI/orval 链路里不可控,问答面只需要这一种带目标的动作。
     */
    public record AnswerAction(String type, String target) {
    }

    public record AnswerAudio(String base64, String format) {
    }

    /** 课堂提问入口:鉴权后进入 SSE 任务,事件 answer_delta/retry/done/error */
    public SseEmitter askSse(long courseId, long coursewareId, String sceneId, String question) {
        Actor actor = currentActor.require();
        courseAccess.requireLearningAccess(courseId, actor);
        coursewares.requireLearnable(courseId, coursewareId);
        long accountId = actor.userId();
        // 学生触发的问答计费到课程负责人:密钥在请求线程解析,未配置立刻 400
        String apiKey = aiKeys.llmKeyForCourse(courseId);
        return SseSupport.run(sseExecutor, objectMapper, sink -> {
            Answer answer = answer(apiKey, accountId, courseId, coursewareId, sceneId, question,
                    sink::emit);
            sink.emit(Map.of("type", "done", "answer", answer));
        });
    }

    Answer answer(String apiKey, long accountId, long courseId, long coursewareId, String sceneId,
                  String question, Consumer<Map<String, Object>> emit) {
        // 播放语境:测验答案/讲解已剥除,模型无从泄题(判分在服务端的同一原则)
        Stage stage = coursewares.getPlayStageInternal(courseId, coursewareId);
        int sceneIndex = -1;
        for (int i = 0; i < stage.scenes().size(); i++) {
            if (stage.scenes().get(i).id().equals(sceneId)) {
                sceneIndex = i;
                break;
            }
        }
        if (sceneIndex == -1) {
            throw new NotFoundException("页面不存在");
        }
        Stage.Scene scene = stage.scenes().get(sceneIndex);

        Map<String, Object> vars = new HashMap<>();
        vars.put("courseTitle", stage.title());
        vars.put("sceneTitle", scene.title());
        vars.put("sceneNumber", sceneIndex + 1);
        vars.put("totalScenes", stage.scenes().size());
        vars.put("blocksJson", Stage.isBlockless(scene.type())
                ? "(" + ("video".equals(scene.type()) ? "视频页" : "交互仿真页") + ",无内容块——actions 必须为空数组"
                + (scene.summary() == null ? "" : ";本页概要:" + scene.summary()) + ")" : toPrettyJson(scene.blocks()));
        vars.put("recentSpeech", recentSpeech(scene));
        vars.put("question", question);
        vars.put("schemaJson", schemas.rawSchema("answer"));
        PromptLoader.Prompt prompt = prompts.build("courseware/prompts", "qa", vars);

        // 每轮一个提取器:打回换轮后前端已按 retry 事件清空,增量从头重出
        final TextFieldStreamer[] streamer = {new TextFieldStreamer()};
        final int[] streamerRound = {1};
        StructuredGenerator.Result<Answer> result = structured.generate(
                new StructuredGenerator.Request<>(
                        apiKey,
                        llmModel,
                        "answer",
                        schemas.rawSchema("answer"),
                        schemas.validator("answer"),
                        prompt.system(), prompt.user(), 3,
                        parsed -> refineAnswer(parsed, scene),
                        (round, reason) -> emit.accept(Map.of(
                                "type", "retry", "round", round, "reason", reason))),
                (round, rawDelta) -> {
                    if (round != streamerRound[0]) {
                        streamer[0] = new TextFieldStreamer();
                        streamerRound[0] = round;
                    }
                    String text = streamer[0].feed(rawDelta);
                    if (!text.isEmpty()) {
                        emit.accept(Map.of("type", "answer_delta", "text", text));
                    }
                });

        Answer answer = result.value();
        qaEvents.insert(new QaEventEntity(coursewareId, accountId, sceneId, question,
                answer.text(), LocalDateTime.now(ZoneOffset.UTC)));
        learningEvents.record(new LearningEvent(courseId, accountId,
                LearningEventType.COURSEWARE_QUESTION_ASKED, coursewareId, java.util.Map.of("sceneId", sceneId)));
        return new Answer(answer.text(), answer.actions(), synthesizeAudio(apiKey, answer.text()));
    }

    private StructuredGenerator.Refined<Answer> refineAnswer(JsonNode parsed, Stage.Scene scene) {
        String text = parsed.path("text").asText("");
        if (text.isBlank()) {
            return StructuredGenerator.Refined.errors(List.of("text 回答文本为空"));
        }

        List<Action> actions;
        try {
            actions = llmMapper.convertValue(parsed.path("actions"),
                    llmMapper.getTypeFactory()
                            .constructCollectionType(List.class, Action.class));
        } catch (Exception e) {
            return StructuredGenerator.Refined.errors(List.of("actions 结构无法解析:" + e.getMessage()));
        }

        Map<String, Integer> blockIds = Stage.isBlockless(scene.type())
                ? Map.of() : StageValidator.collectBlockIds(scene.blocks());

        List<AnswerAction> kept = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Action action : actions) {
            if (!(action instanceof Action.Highlight highlight)) {
                warnings.add("动作已丢弃:问答不支持 " + action.type());
                continue;
            }
            String target = highlight.target();
            if (Stage.isBlockless(scene.type())) {
                warnings.add("动作已丢弃:本页没有内容块,不支持 " + action.type());
                continue;
            }
            String err = StageValidator.validateActionTarget(target, blockIds);
            if (err != null) {
                warnings.add("动作已丢弃:" + err);
                continue;
            }
            kept.add(new AnswerAction(action.type(), target));
        }

        // 与讲稿动作同一阈值:过半无效说明模型在编造目标,回喂真实块 id 清单打回
        int dropped = actions.size() - kept.size();
        if (!actions.isEmpty() && dropped * 2 > actions.size()) {
            String roster = Stage.isBlockless(scene.type()) ? "(本页无内容块,actions 必须为空数组)"
                    : "可用的块 id 只有:" + String.join(", ", blockIds.keySet());
            return StructuredGenerator.Refined.errors(List.of(
                    "超过一半的动作无效被丢弃(" + dropped + "/" + actions.size() + ")。" + roster));
        }
        return StructuredGenerator.Refined.value(new Answer(text, List.copyOf(kept), null), warnings);
    }

    private String recentSpeech(Stage.Scene scene) {
        List<Stage.SpeechSegment> speech = scene.speech() == null ? List.of() : scene.speech();
        if (speech.isEmpty()) {
            return "(本页尚无讲稿)";
        }
        int from = Math.max(0, speech.size() - RECENT_SPEECH_SEGMENTS);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < speech.size(); i++) {
            sb.append("- ").append(speech.get(i).text()).append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 回答语音:内联返回(base64),不落 OSS——问答音频是一次性的,落对象存储要走
     * 预签名与异步删除队列,得不偿失。合成失败只降级为纯文本(回答已生成,
     * 不因语音通道故障丢弃)。
     */
    private AnswerAudio synthesizeAudio(String apiKey, String text) {
        try {
            DashScopeTtsClient.TtsResult result =
                    ttsClient.synthesize(apiKey, properties.ttsModel(), properties.ttsVoice(), text);
            return new AnswerAudio(Base64.getEncoder().encodeToString(result.audio()), result.format());
        } catch (RuntimeException e) {
            log.warn("问答语音合成失败,回答以纯文本返回", e);
            return null;
        }
    }

    private String toPrettyJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("内容块序列化失败", e);
        }
    }
}
