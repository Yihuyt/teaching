package cn.utcy.teaching.question.infrastructure;

import cn.utcy.teaching.question.domain.CourseQuestionItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface CourseQuestionItemMapper extends BaseMapper<CourseQuestionItem> {

    @Select("""
            <script>
            SELECT question_id AS questionId, COUNT(*) AS itemCount, SUM(score) AS totalScore
            FROM course_question_item
            WHERE question_id IN
            <foreach collection="questionIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY question_id
            </script>
            """)
    List<Map<String, Object>> summarize(@Param("questionIds") List<Long> questionIds);
}
