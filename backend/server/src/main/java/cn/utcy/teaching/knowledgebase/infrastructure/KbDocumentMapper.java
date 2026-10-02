package cn.utcy.teaching.knowledgebase.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocumentEntity> {

    @Select("SELECT * FROM knowledge_base_document WHERE id = #{id} FOR UPDATE")
    KbDocumentEntity selectForUpdate(@Param("id") long id);

    /** 进展心跳:只有仍持有 run 标记的任务能跳;返回 0 即标记已失(被判中断或已收尾),本次运行的结果作废 */
    @Update("""
            UPDATE knowledge_base_document SET progress_heartbeat_at = #{now}
            WHERE id = #{id} AND run_token = #{token}
            """)
    int beat(@Param("id") long id, @Param("token") String token, @Param("now") LocalDateTime now);

    @Update("UPDATE knowledge_base_document SET material_id = NULL WHERE material_id = #{materialId}")
    int detachMaterial(@Param("materialId") long materialId);
}
