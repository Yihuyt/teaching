package cn.utcy.teaching.knowledgegraph.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface KnowledgeGraphBuildMapper extends BaseMapper<KnowledgeGraphBuildEntity> {

    /** 悲观锁读:状态迁移与收尾时防并发操作 */
    @Select("SELECT * FROM knowledge_graph_build WHERE id = #{id} FOR UPDATE")
    KnowledgeGraphBuildEntity selectForUpdate(@Param("id") long id);

    /** 进展心跳:只有仍持有 run 标记的管线能跳;返回 0 即标记已失(被判中断或已收尾),本次运行的结果作废 */
    @Update("""
            UPDATE knowledge_graph_build SET progress_heartbeat_at = #{now}
            WHERE id = #{id} AND run_token = #{token}
            """)
    int beat(@Param("id") long id, @Param("token") String token, @Param("now") LocalDateTime now);

    /** 取消标记(行已删除为 null) */
    @Select("SELECT cancel_requested FROM knowledge_graph_build WHERE id = #{id}")
    Boolean cancelRequested(@Param("id") long id);

    @Update("UPDATE knowledge_graph_build SET material_id = NULL WHERE material_id = #{materialId}")
    int detachMaterial(@Param("materialId") long materialId);
}
