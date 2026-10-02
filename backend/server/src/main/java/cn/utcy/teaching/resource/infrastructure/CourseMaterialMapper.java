package cn.utcy.teaching.resource.infrastructure;

import cn.utcy.teaching.resource.domain.CourseMaterial;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CourseMaterialMapper extends BaseMapper<CourseMaterial> {

    @Select("""
            SELECT *
            FROM course_material
            WHERE course_id = #{courseId}
              AND id = #{id}
            """)
    CourseMaterial selectInCourse(
            @Param("courseId") long courseId,
            @Param("id") long id);

    @Select("""
            SELECT *
            FROM course_material
            WHERE course_id = #{courseId}
              AND id = #{id}
            FOR UPDATE
            """)
    CourseMaterial selectForUpdate(
            @Param("courseId") long courseId,
            @Param("id") long id);

    @Select("""
            SELECT object_key
            FROM course_material
            WHERE course_id = #{courseId}
              AND kind = 'file'
            ORDER BY id
            """)
    List<String> selectObjectKeysByCourse(@Param("courseId") long courseId);
}
