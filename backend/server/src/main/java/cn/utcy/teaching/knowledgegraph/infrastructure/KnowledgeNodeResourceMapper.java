package cn.utcy.teaching.knowledgegraph.infrastructure;

import cn.utcy.teaching.knowledgegraph.domain.KnowledgeNodeResource;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeNodeResourceMapper extends BaseMapper<KnowledgeNodeResource> {
}
