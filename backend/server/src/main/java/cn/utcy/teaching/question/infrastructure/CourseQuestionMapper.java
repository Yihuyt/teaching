package cn.utcy.teaching.question.infrastructure;

import cn.utcy.teaching.question.domain.CourseQuestion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CourseQuestionMapper extends BaseMapper<CourseQuestion> {

    @Select("""
            SELECT *
            FROM course_question
            WHERE id = #{id} AND course_id = #{courseId}
            FOR UPDATE
            """)
    CourseQuestion selectForUpdate(
            @Param("courseId") long courseId,
            @Param("id") long id);
}
