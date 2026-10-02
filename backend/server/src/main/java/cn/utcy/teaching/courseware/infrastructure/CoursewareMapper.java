package cn.utcy.teaching.courseware.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CoursewareMapper extends BaseMapper<CoursewareEntity> {

    @Select("SELECT * FROM courseware WHERE id = #{id} FOR UPDATE")
    CoursewareEntity selectForUpdate(@Param("id") long id);
}
