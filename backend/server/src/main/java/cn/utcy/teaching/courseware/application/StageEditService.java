package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.courseware.application.CoursewareApplicationService.CoursewareDetail;
import cn.utcy.teaching.courseware.application.CoursewareApplicationService.VersionedStage;
import cn.utcy.teaching.courseware.domain.Stage;
import cn.utcy.teaching.courseware.domain.StageCommands;
import cn.utcy.teaching.courseware.domain.StageValidator;
import cn.utcy.teaching.courseware.domain.StageProblem;
import cn.utcy.teaching.courseware.domain.EditOp;
import cn.utcy.teaching.courseware.infrastructure.CoursewareEntity;
import cn.utcy.teaching.ai.structured.LlmOutputMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class StageEditService {

    private static final Logger log = LoggerFactory.getLogger(StageEditService.class);
    private static final String MALFORMED_EDIT = "修改内容格式不正确,请刷新页面后重试";

    private final CoursewareApplicationService coursewares;
    private final StageJsonCodec codec;
    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;
    private final EditOpSchema opSchema;
    /** 形状已由 schema 保证,解析只做 JSON → 记录的映射:可选字段缺省为 null */
    private final ObjectMapper opMapper;

    public StageEditService(CoursewareApplicationService coursewares, StageJsonCodec codec,
                           CourseAccess courseAccess, CurrentActor currentActor, EditOpSchema opSchema,
                           ObjectMapper objectMapper) {
        this.coursewares = coursewares;
        this.codec = codec;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
        this.opSchema = opSchema;
        this.opMapper = LlmOutputMappers.lenient(objectMapper);
    }

    public EditOp parse(JsonNode op) {
        List<String> violations = opSchema.violations(op);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(String.join(";", violations));
        }
        try {
            return opMapper.convertValue(op, EditOp.class);
        } catch (IllegalArgumentException exception) {
            Throwable root = exception;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            throw new IllegalArgumentException(root.getMessage() == null ? exception.getMessage() : root.getMessage());
        }
    }

    @Transactional
    public CoursewareDetail applyForTeacher(long courseId, long coursewareId, List<JsonNode> rawOps) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        List<EditOp> ops = new ArrayList<>();
        for (JsonNode raw : rawOps) {
            try {
                ops.add(parse(raw));
            } catch (IllegalArgumentException exception) {
                log.warn("课件编辑操作不符合契约:{} → {}", raw, exception.getMessage());
                throw new BadRequestException(MALFORMED_EDIT);
            }
        }
        CoursewareEntity locked = coursewares.requireForUpdate(courseId, coursewareId);
        Stage current = codec.fromJson(locked.getBody());
        VersionedStage saved;
        try {
            saved = coursewares.saveLocked(locked, StageCommands.apply(current, ops));
        } catch (StageCommands.Rejected rejected) {
            throw new BadRequestException(String.join(";", rejected.errors()));
        } catch (StageCommands.Malformed malformed) {
            log.warn("课件编辑结果含编辑界面不会产生的内容:{} → {}", rawOps, malformed.getMessage());
            throw new BadRequestException(MALFORMED_EDIT);
        }
        return coursewares.detail(locked, saved.stage());
    }

    /**
     * 按页序整页落库(按大纲生成的逐页检查点):order 为 1 起的页序;已有该序号的页被替换(保留其 id,学习记录不断线),
     * 超出末尾则追加。页必须通过整页校验。
     */
    @Transactional
    public VersionedStage putSceneInternal(long courseId, long coursewareId, int order, Stage.Scene scene) {
        CoursewareEntity locked = coursewares.requireForUpdate(courseId, coursewareId);
        Stage current = codec.fromJson(locked.getBody());
        List<Stage.Scene> scenes = new ArrayList<>(current.scenes());
        if (order < 1) {
            throw new StageCommands.Rejected(List.of("页序必须从 1 开始"));
        }
        Stage.Scene stored;
        if (order <= scenes.size()) {
            Stage.Scene existing = scenes.get(order - 1);
            stored = withId(scene, existing.id());
            scenes.set(order - 1, stored);
        } else {
            if (scenes.size() + 1 > StageCommands.MAX_SCENES) {
                throw new StageCommands.Rejected(List.of("总页数不能超过 " + StageCommands.MAX_SCENES));
            }
            stored = withId(scene, StageCommands.newSceneId(scenes));
            scenes.add(stored);
        }
        List<String> errors = StageProblem.forModel(StageValidator.validateScene(stored));
        if (!errors.isEmpty()) {
            throw new StageCommands.Rejected(errors);
        }
        Stage next = new Stage(current.title(), current.theme(), List.copyOf(scenes));
        return coursewares.saveLocked(locked, next);
    }

    /** 整页替换(单页重生成的持久化):按 id 定位,页序在生成期间被调整也不会写错页;页已被删则 404 */
    @Transactional
    public VersionedStage replaceSceneInternal(long courseId, long coursewareId, String sceneId, Stage.Scene scene) {
        CoursewareEntity locked = coursewares.requireForUpdate(courseId, coursewareId);
        Stage current = codec.fromJson(locked.getBody());
        List<Stage.Scene> scenes = new ArrayList<>(current.scenes());
        int index = -1;
        for (int i = 0; i < scenes.size(); i++) {
            if (scenes.get(i).id().equals(sceneId)) {
                index = i;
            }
        }
        if (index < 0) {
            throw new NotFoundException("页面不存在");
        }
        Stage.Scene stored = withId(scene, sceneId);
        List<String> errors = StageProblem.forModel(StageValidator.validateScene(stored));
        if (!errors.isEmpty()) {
            throw new StageCommands.Rejected(errors);
        }
        scenes.set(index, stored);
        Stage next = new Stage(current.title(), current.theme(), List.copyOf(scenes));
        return coursewares.saveLocked(locked, next);
    }

    @Transactional
    public VersionedStage resetInternal(long courseId, long coursewareId, String title) {
        CoursewareEntity locked = coursewares.requireForUpdate(courseId, coursewareId);
        Stage current = codec.fromJson(locked.getBody());
        Stage next = new Stage(title.strip(), current.theme(), List.of());
        return coursewares.saveLocked(locked, next);
    }

    private static Stage.Scene withId(Stage.Scene scene, String id) {
        return new Stage.Scene(id, scene.type(), scene.title(), scene.preset(), scene.summary(), scene.blocks(),
                scene.speech(), scene.layouts(), scene.interactive(), scene.video());
    }
}
