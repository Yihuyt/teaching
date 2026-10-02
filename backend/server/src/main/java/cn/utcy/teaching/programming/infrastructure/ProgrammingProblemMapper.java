package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.programming.domain.ProgrammingProblem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProgrammingProblemMapper extends BaseMapper<ProgrammingProblem> {

    @Select("SELECT * FROM programming_problem WHERE id = #{id} FOR UPDATE")
    ProgrammingProblem selectForUpdate(@Param("id") long id);
}
