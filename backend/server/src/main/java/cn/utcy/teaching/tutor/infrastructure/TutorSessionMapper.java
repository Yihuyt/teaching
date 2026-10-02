package cn.utcy.teaching.tutor.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TutorSessionMapper extends BaseMapper<TutorSessionEntity> {

    @Select("SELECT * FROM tutor_session WHERE id = #{id} FOR UPDATE")
    TutorSessionEntity selectForUpdate(@Param("id") long id);
}
