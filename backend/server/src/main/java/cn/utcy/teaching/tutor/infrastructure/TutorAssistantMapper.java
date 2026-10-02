package cn.utcy.teaching.tutor.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TutorAssistantMapper extends BaseMapper<TutorAssistantEntity> {

    @Select("SELECT * FROM tutor_assistant WHERE id = #{id} FOR UPDATE")
    TutorAssistantEntity selectForUpdate(@Param("id") long id);

    @Select("SELECT knowledge_base_id FROM tutor_assistant_knowledge_base WHERE assistant_id = #{assistantId} ORDER BY position")
    List<Long> knowledgeBaseIds(@Param("assistantId") long assistantId);

    @Delete("DELETE FROM tutor_assistant_knowledge_base WHERE assistant_id = #{assistantId}")
    void clearKnowledgeBases(@Param("assistantId") long assistantId);

    @Delete("DELETE FROM tutor_assistant_knowledge_base WHERE knowledge_base_id = #{knowledgeBaseId}")
    int unmountKnowledgeBase(@Param("knowledgeBaseId") long knowledgeBaseId);

    @Insert("INSERT INTO tutor_assistant_knowledge_base (assistant_id, knowledge_base_id, position) VALUES (#{assistantId}, #{kbId}, #{position})")
    void addKnowledgeBase(@Param("assistantId") long assistantId, @Param("kbId") long kbId, @Param("position") int position);
}
