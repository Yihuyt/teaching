package cn.utcy.teaching.tutor.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseRetrievalService.KnowledgeBaseRef;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseService;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseService.KnowledgeBaseMount;
import cn.utcy.teaching.tutor.infrastructure.TutorAssistantEntity;
import cn.utcy.teaching.tutor.infrastructure.TutorAssistantEntity.ModelSettings;
import cn.utcy.teaching.tutor.infrastructure.TutorProperties;
import cn.utcy.teaching.tutor.infrastructure.TutorRowPurger;
import cn.utcy.teaching.tutor.infrastructure.TutorAssistantMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class TutorAssistantService {

    public static final int MAX_MOUNTS = 10;
    public static final int MAX_ROUNDS_LIMIT = 16;

    private final TutorAssistantMapper assistants;
    private final KnowledgeBaseService knowledgeBases;
    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;
    private final TutorProperties properties;
    private final TutorRowPurger purger;

    public TutorAssistantService(TutorAssistantMapper assistants, KnowledgeBaseService knowledgeBases,
                                 CourseAccess courseAccess, CurrentActor currentActor,
                                 TutorProperties properties, TutorRowPurger purger) {
        this.assistants = assistants;
        this.purger = purger;
        this.knowledgeBases = knowledgeBases;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
        this.properties = properties;
    }

    public record TutorAssistantDefaults(String model, double temperature, boolean reasoning, int maxRounds) {
    }

    public TutorAssistantDefaults defaults() {
        return new TutorAssistantDefaults(properties.model(), properties.temperature(), properties.reasoning(),
                properties.maxRounds());
    }

    public record TutorMountView(long id, String name, boolean ready) {
    }

    public record TutorAssistantView(long id, String name, String description, String instructions,
                                     String model, double temperature, boolean reasoning, int maxRounds,
                                     boolean visibleToStudents, List<TutorMountView> knowledgeBases,
                                     Instant createdAt, Instant updatedAt) {
    }

    /** 学生端卡片:不含教师补充要求 */
    public record TutorAssistantCard(long id, String name, String description, List<TutorMountView> knowledgeBases) {
    }

    public record SaveAssistant(String name, String description, String instructions, ModelSettings model,
                                boolean visibleToStudents, List<Long> knowledgeBaseIds) {
    }

    @Transactional(readOnly = true)
    public List<TutorAssistantView> list(long courseId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        return listEntities(courseId, false).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<TutorAssistantCard> listForStudents(long courseId) {
        courseAccess.requireLearningAccess(courseId, currentActor.require());
        return listEntities(courseId, true).stream().map(this::card).toList();
    }

    @Transactional
    public TutorAssistantView create(long courseId, SaveAssistant request) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        List<Long> kbIds = normalizeIds(request.knowledgeBaseIds(), "知识库");
        knowledgeBases.describe(courseId, kbIds);
        TutorAssistantEntity entity = new TutorAssistantEntity(courseId, request.name().strip(),
                normalizeDescription(request.description()), normalizeInstructions(request.instructions()),
                normalizeModel(request.model()), request.visibleToStudents(), now());
        try {
            assistants.insert(entity);
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("同名助手已存在");
        }
        replaceMounts(entity.getId(), kbIds);
        return view(entity);
    }

    @Transactional
    public TutorAssistantView update(long courseId, long assistantId, SaveAssistant request) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        TutorAssistantEntity entity = requireInCourse(courseId, assistantId);
        List<Long> kbIds = normalizeIds(request.knowledgeBaseIds(), "知识库");
        knowledgeBases.describe(courseId, kbIds);
        entity.update(request.name().strip(), normalizeDescription(request.description()),
                normalizeInstructions(request.instructions()), normalizeModel(request.model()),
                request.visibleToStudents(), now());
        try {
            assistants.updateById(entity);
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("同名助手已存在");
        }
        replaceMounts(assistantId, kbIds);
        return view(entity);
    }

    @Transactional
    public void delete(long courseId, long assistantId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        // 锁助手行:并发的会话新建 / 挂载修改在同一事务内读它,删除后不再有子行落地
        TutorAssistantEntity entity = assistants.selectForUpdate(assistantId);
        if (entity == null || entity.getCourseId() != courseId) {
            throw new NotFoundException("课程中不存在该助手");
        }
        purger.purgeAssistant(entity.getId());
    }

    // ---- 模块内共用 ----

    TutorAssistantEntity requireUsable(long courseId, long assistantId, Actor actor) {
        TutorAssistantEntity entity = requireInCourse(courseId, assistantId);
        if (courseAccess.canManage(courseId, actor)) {
            return entity;
        }
        courseAccess.requireLearningAccess(courseId, actor);
        if (!entity.getVisibleToStudents()) {
            throw new NotFoundException("课程中不存在该助手");
        }
        return entity;
    }

    TutorAssistantEntity requireInCourse(long courseId, long assistantId) {
        TutorAssistantEntity entity = assistants.selectById(assistantId);
        if (entity == null || entity.getCourseId() != courseId) {
            throw new NotFoundException("课程中不存在该助手");
        }
        return entity;
    }

    TutorMounts mounts(TutorAssistantEntity assistant) {
        long courseId = assistant.getCourseId();
        List<KnowledgeBaseRef> kbs = knowledgeBases.describe(courseId, assistants.knowledgeBaseIds(assistant.getId()))
                .stream().filter(KnowledgeBaseMount::ready)
                .map(kb -> new KnowledgeBaseRef(kb.id(), kb.name())).toList();
        ModelSettings settings = assistant.modelSettings();
        ModelConfig model = new ModelConfig("tutor-assistant-" + assistant.getId(),
                settings.model(), settings.reasoning(), settings.temperature(), properties.topP(),
                properties.maxOutputTokens());
        return new TutorMounts(assistant.getName(), assistant.getInstructions() == null ? "" : assistant.getInstructions(),
                model, settings.maxRounds(), kbs);
    }

    private List<TutorAssistantEntity> listEntities(long courseId, boolean visibleOnly) {
        return assistants.selectList(new LambdaQueryWrapper<TutorAssistantEntity>()
                .eq(TutorAssistantEntity::getCourseId, courseId)
                .eq(visibleOnly, TutorAssistantEntity::getVisibleToStudents, true)
                .orderByAsc(TutorAssistantEntity::getId));
    }

    private void replaceMounts(long assistantId, List<Long> kbIds) {
        assistants.clearKnowledgeBases(assistantId);
        for (int i = 0; i < kbIds.size(); i++) {
            assistants.addKnowledgeBase(assistantId, kbIds.get(i), i);
        }
    }

    private TutorAssistantView view(TutorAssistantEntity entity) {
        ModelSettings model = entity.modelSettings();
        return new TutorAssistantView(entity.getId(), entity.getName(), entity.getDescription(),
                entity.getInstructions() == null ? "" : entity.getInstructions(),
                model.model(), model.temperature(), model.reasoning(), model.maxRounds(), entity.getVisibleToStudents(),
                kbMounts(entity),
                entity.getCreatedAt().toInstant(ZoneOffset.UTC), entity.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }

    private TutorAssistantCard card(TutorAssistantEntity entity) {
        return new TutorAssistantCard(entity.getId(), entity.getName(), entity.getDescription(),
                kbMounts(entity));
    }

    private List<TutorMountView> kbMounts(TutorAssistantEntity entity) {
        return knowledgeBases.describe(entity.getCourseId(), assistants.knowledgeBaseIds(entity.getId())).stream()
                .map(kb -> new TutorMountView(kb.id(), kb.name(), kb.ready())).toList();
    }

    private static List<Long> normalizeIds(List<Long> ids, String label) {
        List<Long> unique = List.copyOf(new LinkedHashSet<>(ids == null ? List.of() : ids));
        if (unique.size() > MAX_MOUNTS) {
            throw new BadRequestException("一个助手最多挂载 " + MAX_MOUNTS + " 个" + label);
        }
        return unique;
    }

    private static ModelSettings normalizeModel(ModelSettings model) {
        String name = model.model() == null ? "" : model.model().strip();
        if (name.isEmpty()) {
            throw new BadRequestException("模型不能为空");
        }
        if (model.temperature() < 0 || model.temperature() > 2) {
            throw new BadRequestException("温度须在 0 到 2 之间");
        }
        if (model.maxRounds() < 1 || model.maxRounds() > MAX_ROUNDS_LIMIT) {
            throw new BadRequestException("工具轮次上限须在 1 到 " + MAX_ROUNDS_LIMIT + " 之间");
        }
        return new ModelSettings(name, Math.round(model.temperature() * 100) / 100.0, model.reasoning(), model.maxRounds());
    }

    private static String normalizeDescription(String description) {
        return description == null ? "" : description.strip();
    }

    private static String normalizeInstructions(String instructions) {
        return instructions == null || instructions.isBlank() ? null : instructions.strip();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
