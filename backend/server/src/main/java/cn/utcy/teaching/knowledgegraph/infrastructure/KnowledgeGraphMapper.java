package cn.utcy.teaching.knowledgegraph.infrastructure;

import cn.utcy.teaching.knowledgegraph.domain.KnowledgeGraph;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KnowledgeGraphMapper extends BaseMapper<KnowledgeGraph> {

    @Select("""
            SELECT *
            FROM knowledge_graph
            WHERE course_id = #{courseId}
              AND id = #{id}
            FOR UPDATE
            """)
    KnowledgeGraph selectForUpdate(
            @Param("courseId") long courseId,
            @Param("id") long id
    );
}
