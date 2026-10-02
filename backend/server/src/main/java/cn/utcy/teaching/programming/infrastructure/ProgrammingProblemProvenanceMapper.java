package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.programming.domain.ProgrammingProblemProvenance;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProgrammingProblemProvenanceMapper
        extends BaseMapper<ProgrammingProblemProvenance> {
}
