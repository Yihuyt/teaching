package cn.utcy.teaching.knowledgebase.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBaseEntity> {

    /** 悲观锁读:入库/重建期间防并发状态漂移 */
    @Select("SELECT * FROM knowledge_base WHERE id = #{id} FOR UPDATE")
    KnowledgeBaseEntity selectForUpdate(@Param("id") long id);

    /** 首份文档抢占索引指针:只有还没有指针时写入,返回 0 即别人先建好了(用赢家的) */
    @Update("""
            UPDATE knowledge_base SET active_signature = #{signature}, active_index_name = #{indexName}
            WHERE id = #{id} AND active_index_name IS NULL
            """)
    int activateIndexIfEmpty(@Param("id") long id, @Param("signature") String signature,
                             @Param("indexName") String indexName);
}
