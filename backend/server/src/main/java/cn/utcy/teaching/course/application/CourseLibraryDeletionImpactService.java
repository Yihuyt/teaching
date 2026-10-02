package cn.utcy.teaching.course.application;

import cn.utcy.teaching.shared.course.CourseContentReferenceSource;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.course.CourseOutlineLinks;
import cn.utcy.teaching.shared.actor.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 资料库删除确认前的一次询问:所选内容里有没有与别处关联的(在课程内容中、挂在图谱节点上、
 * 有作答 / 提交、文件夹非空)。只回答"有 / 无",确认框据此换一句话,不罗列明细。
 */
@Service
public class CourseLibraryDeletionImpactService {

    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;
    private final CourseOutlineLinks outlineLinks;
    private final List<CourseContentReferenceSource> sources;

    public CourseLibraryDeletionImpactService(CourseAccess courseAccess, CurrentActor currentActor,
                                              CourseOutlineLinks outlineLinks,
                                              List<CourseContentReferenceSource> sources) {
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
        this.outlineLinks = outlineLinks;
        this.sources = List.copyOf(sources);
    }

    @Transactional(readOnly = true)
    public DeletionImpactView impact(long courseId, List<Long> materialIds, List<Long> questionIds,
                                     List<Long> problemIds) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        boolean associated = anyReferenced(courseId, CourseOutlineItemType.MATERIAL, materialIds)
                || anyReferenced(courseId, CourseOutlineItemType.QUESTION, questionIds)
                || anyReferenced(courseId, CourseOutlineItemType.PROGRAMMING_PROBLEM, problemIds);
        return new DeletionImpactView(associated);
    }

    private boolean anyReferenced(long courseId, CourseOutlineItemType itemType, List<Long> contentIds) {
        for (Long contentId : contentIds) {
            if (outlineLinks.isLinked(courseId, itemType, contentId)
                    || sources.stream().anyMatch(source -> source.references(courseId, itemType, contentId))) {
                return true;
            }
        }
        return false;
    }

    public record DeletionImpactView(boolean associated) {
    }
}
