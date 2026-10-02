package cn.utcy.teaching.courseware.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QaEventMapper extends BaseMapper<QaEventEntity> {

    @Select("""
            SELECT id, courseware_id, account_id, scene_id, question, answer, asked_at
            FROM courseware_qa_event
            WHERE courseware_id = #{coursewareId}
            ORDER BY asked_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<QaEventEntity> listRecent(@Param("coursewareId") long coursewareId,
                                   @Param("limit") int limit);
}
