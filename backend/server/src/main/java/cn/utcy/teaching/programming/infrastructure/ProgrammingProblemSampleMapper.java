package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.programming.domain.ProgrammingProblemSample;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProgrammingProblemSampleMapper
        extends BaseMapper<ProgrammingProblemSample> {

    @Delete("DELETE FROM programming_problem_sample WHERE problem_id = #{problemId}")
    int deleteByProblemId(@Param("problemId") long problemId);
}
