package cn.utcy.teaching.tutor.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface TutorMessageMapper extends BaseMapper<TutorMessageEntity> {

    @Select("""
            <script>
            SELECT session_id AS sessionId, COUNT(*) AS total FROM tutor_message
            WHERE session_id IN
            <foreach collection="sessionIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY session_id
            </script>
            """)
    List<Map<String, Object>> countBySession(@Param("sessionIds") List<Long> sessionIds);
}
