package cn.utcy.teaching.course.infrastructure;

import cn.utcy.teaching.course.domain.Course;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CourseMapper extends BaseMapper<Course> {

    @Select("SELECT * FROM course WHERE id = #{id} FOR UPDATE")
    Course selectForUpdate(@Param("id") long id);

    @Select("SELECT * FROM course WHERE join_code = #{joinCode} FOR UPDATE")
    Course selectByJoinCodeForUpdate(@Param("joinCode") String joinCode);
}
