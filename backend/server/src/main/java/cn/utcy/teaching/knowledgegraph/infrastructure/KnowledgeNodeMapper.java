package cn.utcy.teaching.knowledgegraph.infrastructure;

import cn.utcy.teaching.knowledgegraph.domain.KnowledgeNode;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 删除节点后原兄弟补位:带 ORDER BY 的单表 UPDATE 按 position 升序执行,
 * 唯一键 (graph_id, parent_scope, position) 逐行检查也不会出现瞬时重复。
 */
@Mapper
public interface KnowledgeNodeMapper extends BaseMapper<KnowledgeNode> {

    @Update("""
            UPDATE knowledge_node
            SET position = position - 1
            WHERE graph_id = #{graphId}
              AND parent_scope = #{parentScope}
              AND position > #{afterPosition}
              AND id <> #{excludeId}
            ORDER BY position ASC
            """)
    int closeGap(@Param("graphId") long graphId, @Param("parentScope") long parentScope,
                 @Param("afterPosition") int afterPosition, @Param("excludeId") long excludeId);
}
