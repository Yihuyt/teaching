package cn.utcy.teaching.courseware.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface QuizAttemptMapper extends BaseMapper<QuizAttemptEntity> {

    @Select("""
            SELECT block_id AS blockId,
                   COUNT(*) AS attempts,
                   SUM(correct) AS correctCount
            FROM courseware_quiz_attempt
            WHERE courseware_id = #{coursewareId}
            GROUP BY block_id
            ORDER BY block_id
            """)
    List<QuizBlockStat> summarizeByBlock(@Param("coursewareId") long coursewareId);

    record QuizBlockStat(String blockId, long attempts, long correctCount) {
    }

    @Select("""
            SELECT account_id AS accountId,
                   COUNT(*) AS attempts,
                   SUM(correct) AS correctCount,
                   MAX(attempted_at) AS lastAttemptAt
            FROM courseware_quiz_attempt
            WHERE courseware_id = #{coursewareId}
            GROUP BY account_id
            ORDER BY account_id
            """)
    List<QuizAccountStat> summarizeByAccount(@Param("coursewareId") long coursewareId);

    record QuizAccountStat(long accountId, long attempts, long correctCount,
                           LocalDateTime lastAttemptAt) {
    }
}
