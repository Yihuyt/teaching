package cn.utcy.teaching.knowledgegraph.api;

import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphEditor;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphEditor.NodeCreated;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphService;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphService.GraphSnapshot;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphService.GraphView;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphService.ResourceOpenView;
import cn.utcy.teaching.knowledgegraph.domain.EdgeKind;
import cn.utcy.teaching.knowledgegraph.domain.GraphRules;
import cn.utcy.teaching.knowledgegraph.domain.KpType;
import cn.utcy.teaching.knowledgegraph.domain.NodeContent;
import cn.utcy.teaching.knowledgegraph.domain.NodeKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 图谱 + 节点 + 关系 + 挂载 + 打开资源。内容写操作即时落库、每个操作返回新快照;
 * 新节点一律追加到父节点末尾,兄弟顺序即创建顺序。
 */
@Validated
@RestController
@RequestMapping("/api/v1/courses/{courseId}/knowledge-graphs")
public class CourseKnowledgeGraphController {

    private final KnowledgeGraphService graphs;
    private final KnowledgeGraphEditor editor;

    public CourseKnowledgeGraphController(KnowledgeGraphService graphs, KnowledgeGraphEditor editor) {
        this.graphs = graphs;
        this.editor = editor;
    }

    @GetMapping
    public List<GraphView> list(@PathVariable @Min(1) long courseId,
                                @RequestParam(defaultValue = "false") boolean management) {
        return graphs.list(courseId, management);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GraphView create(@PathVariable @Min(1) long courseId, @Valid @RequestBody CreateGraphRequest request) {
        return graphs.create(courseId, request.name());
    }

    @PutMapping("/{graphId}")
    public GraphView update(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long graphId,
                            @Valid @RequestBody UpdateGraphRequest request) {
        return graphs.rename(courseId, graphId, request.name());
    }

    @DeleteMapping("/{graphId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long graphId) {
        graphs.delete(courseId, graphId);
    }

    /** 发布:学生可见、可用于问答与出题 */
    @PostMapping("/{graphId}/publication")
    public GraphView publish(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long graphId) {
        return graphs.publish(courseId, graphId);
    }

    @DeleteMapping("/{graphId}/publication")
    public GraphView unpublish(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long graphId) {
        return graphs.unpublish(courseId, graphId);
    }

    @GetMapping("/{graphId}")
    public GraphSnapshot snapshot(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long graphId,
                                  @RequestParam(defaultValue = "false") boolean management) {
        return graphs.snapshot(courseId, graphId, management);
    }

    @PostMapping("/{graphId}/nodes")
    @ResponseStatus(HttpStatus.CREATED)
    public NodeCreated createNode(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long graphId,
                                  @Valid @RequestBody CreateNodeRequest request) {
        return editor.createNode(courseId, graphId, request.parentId(), new NodeContent(request.kind(),
                request.label(), request.kpType(), request.summary(), request.definition(), request.explanation(),
                request.aliases(), request.code(), request.language(),
                request.sourceSectionTitle(), request.quote()));
    }

    @PutMapping("/{graphId}/nodes/{nodeId}")
    public GraphSnapshot updateNode(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long graphId,
                                    @PathVariable @Min(1) long nodeId, @Valid @RequestBody UpdateNodeRequest request) {
        return editor.updateNode(courseId, graphId, nodeId, new NodeContent(null, request.label(), request.kpType(),
                request.summary(), request.definition(), request.explanation(), request.aliases(), request.code(),
                request.language(), request.sourceSectionTitle(), request.quote()));
    }

    @DeleteMapping("/{graphId}/nodes/{nodeId}")
    public GraphSnapshot deleteNode(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long graphId,
                                    @PathVariable @Min(1) long nodeId) {
        return editor.deleteNode(courseId, graphId, nodeId);
    }

    @PostMapping("/{graphId}/edges")
    @ResponseStatus(HttpStatus.CREATED)
    public GraphSnapshot createEdge(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long graphId,
                                    @Valid @RequestBody CreateEdgeRequest request) {
        return editor.createEdge(courseId, graphId, request.sourceNodeId(), request.targetNodeId(), request.kind());
    }

    @DeleteMapping("/{graphId}/edges/{edgeId}")
    public GraphSnapshot deleteEdge(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long graphId,
                                    @PathVariable @Min(1) long edgeId) {
        return editor.deleteEdge(courseId, graphId, edgeId);
    }

    @PostMapping("/{graphId}/nodes/{nodeId}/resources")
    @ResponseStatus(HttpStatus.CREATED)
    public GraphSnapshot attachResource(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long graphId,
                                        @PathVariable @Min(1) long nodeId,
                                        @Valid @RequestBody AttachResourceRequest request) {
        return editor.attachResource(courseId, graphId, nodeId, request.itemType(), request.contentId());
    }

    @DeleteMapping("/{graphId}/nodes/{nodeId}/resources/{itemType}/{contentId}")
    public GraphSnapshot detachResource(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long graphId,
                                        @PathVariable @Min(1) long nodeId,
                                        @PathVariable CourseOutlineItemType itemType,
                                        @PathVariable @Min(1) long contentId) {
        return editor.detachResource(courseId, graphId, nodeId, itemType, contentId);
    }

    @PostMapping("/{graphId}/nodes/{nodeId}/resources/{itemType}/{contentId}/open")
    public ResourceOpenView openResource(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long graphId,
                                         @PathVariable @Min(1) long nodeId,
                                         @PathVariable CourseOutlineItemType itemType,
                                         @PathVariable @Min(1) long contentId,
                                         @RequestParam(defaultValue = "false") boolean management) {
        return graphs.openResource(courseId, graphId, nodeId, itemType, contentId, management);
    }

    public record CreateGraphRequest(
            @NotBlank(message = "图谱名称不能为空")
            @Size(max = 128, message = "图谱名称不能超过 128 个字符")
            String name
    ) {
    }

    public record UpdateGraphRequest(
            @NotBlank(message = "图谱名称不能为空")
            @Size(max = 128, message = "图谱名称不能超过 128 个字符")
            String name
    ) {
    }

    /** parentId 为空 = 图谱根;文字栏按类型各归其名:章节 summary / 知识点 definition / 代码示例 explanation */
    public record CreateNodeRequest(
            @Min(1) Long parentId,
            @NotNull(message = "节点类型不能为空") NodeKind kind,
            @NotBlank(message = "名称不能为空")
            @Size(max = GraphRules.MAX_LABEL, message = "名称不能超过 255 个字符")
            String label,
            KpType kpType,
            @Size(max = GraphRules.MAX_TEXT, message = "摘要不能超过 2000 个字符")
            String summary,
            @Size(max = GraphRules.MAX_TEXT, message = "释义不能超过 2000 个字符")
            String definition,
            @Size(max = GraphRules.MAX_TEXT, message = "说明不能超过 2000 个字符")
            String explanation,
            @Size(max = GraphRules.MAX_ALIASES, message = "别名最多 10 个")
            List<@NotBlank @Size(max = GraphRules.MAX_ALIAS) String> aliases,
            @Size(max = GraphRules.MAX_CODE, message = "代码不能超过 20000 个字符")
            String code,
            @Size(max = GraphRules.MAX_LANGUAGE, message = "语言不能超过 32 个字符")
            String language,
            @Size(max = GraphRules.MAX_SOURCE_TITLE, message = "出处小节不能超过 255 个字符")
            String sourceSectionTitle,
            @Size(max = GraphRules.MAX_QUOTE, message = "原文引文不能超过 500 个字符")
            String quote
    ) {
    }

    public record UpdateNodeRequest(
            @NotBlank(message = "名称不能为空")
            @Size(max = GraphRules.MAX_LABEL, message = "名称不能超过 255 个字符")
            String label,
            KpType kpType,
            @Size(max = GraphRules.MAX_TEXT, message = "摘要不能超过 2000 个字符")
            String summary,
            @Size(max = GraphRules.MAX_TEXT, message = "释义不能超过 2000 个字符")
            String definition,
            @Size(max = GraphRules.MAX_TEXT, message = "说明不能超过 2000 个字符")
            String explanation,
            @Size(max = GraphRules.MAX_ALIASES, message = "别名最多 10 个")
            List<@NotBlank @Size(max = GraphRules.MAX_ALIAS) String> aliases,
            @Size(max = GraphRules.MAX_CODE, message = "代码不能超过 20000 个字符")
            String code,
            @Size(max = GraphRules.MAX_LANGUAGE, message = "语言不能超过 32 个字符")
            String language,
            @Size(max = GraphRules.MAX_SOURCE_TITLE, message = "出处小节不能超过 255 个字符")
            String sourceSectionTitle,
            @Size(max = GraphRules.MAX_QUOTE, message = "原文引文不能超过 500 个字符")
            String quote
    ) {
    }

    public record CreateEdgeRequest(
            @Min(1) long sourceNodeId,
            @Min(1) long targetNodeId,
            @NotNull(message = "关系类型不能为空") EdgeKind kind
    ) {
    }

    public record AttachResourceRequest(
            @NotNull(message = "资源类型不能为空") CourseOutlineItemType itemType,
            @Min(1) long contentId
    ) {
    }
}
