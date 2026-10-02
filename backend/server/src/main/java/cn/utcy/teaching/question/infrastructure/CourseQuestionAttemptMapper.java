package cn.utcy.teaching.question.infrastructure;

import cn.utcy.teaching.question.domain.CourseQuestionAttempt;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CourseQuestionAttemptMapper extends BaseMapper<CourseQuestionAttempt> {

    @Select("SELECT * FROM course_question_attempt WHERE id = #{id} FOR UPDATE")
    CourseQuestionAttempt selectForUpdate(@Param("id") long id);

    @Select("""
            SELECT account_id AS accountId, COUNT(*) AS attemptCount, MAX(score) AS bestScore,
                   MAX(submitted_at) AS lastSubmittedAt
            FROM course_question_attempt
            WHERE question_id = #{questionId} AND submitted_at IS NOT NULL
            GROUP BY account_id
            ORDER BY MAX(submitted_at) DESC
            """)
    List<AccountAttemptSummary> summarizeByAccount(@Param("questionId") long questionId);
}
