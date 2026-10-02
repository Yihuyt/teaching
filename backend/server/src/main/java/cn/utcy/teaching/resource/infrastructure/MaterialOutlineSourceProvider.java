package cn.utcy.teaching.resource.infrastructure;

import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.course.CourseOutlineSourceProvider;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.resource.domain.CourseMaterial;
import cn.utcy.teaching.resource.domain.MaterialKind;
import cn.utcy.teaching.resource.domain.MaterialState;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
class MaterialOutlineSourceProvider implements CourseOutlineSourceProvider {

    private final CourseMaterialMapper materials;

    MaterialOutlineSourceProvider(CourseMaterialMapper materials) {
        this.materials = materials;
    }

    @Override
    public CourseOutlineItemType type() {
        return CourseOutlineItemType.MATERIAL;
    }

    @Override
    public Map<Long, String> requireTitles(long courseId, Set<Long> contentIds) {
        if (contentIds.isEmpty()) {
            return Map.of();
        }
        // 已编排的文件不可能不就绪(删除被 409 挡住、上传完成不会回退),缺失是不变量被破坏,由注册表报错
        return materials.selectList(new LambdaQueryWrapper<CourseMaterial>()
                        .eq(CourseMaterial::getCourseId, courseId)
                        .in(CourseMaterial::getId, contentIds))
                .stream()
                .collect(Collectors.toUnmodifiableMap(CourseMaterial::getId, CourseMaterial::getName));
    }

    @Override
    public String requireLinkable(long courseId, long contentId) {
        CourseMaterial material = materials.selectForUpdate(courseId, contentId);
        if (material == null || material.getKind() != MaterialKind.FILE) {
            throw new NotFoundException("课程中不存在该文件");
        }
        if (material.getState() != MaterialState.ACTIVE) {
            throw new ConflictException("文件尚未上传完成");
        }
        return material.getName();
    }
}
