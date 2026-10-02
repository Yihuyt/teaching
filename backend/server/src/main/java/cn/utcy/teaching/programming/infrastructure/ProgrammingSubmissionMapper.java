package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.programming.domain.ProgrammingSubmission;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

@Mapper
public interface ProgrammingSubmissionMapper extends BaseMapper<ProgrammingSubmission> {
    /** 重判复位:结果字段回 NULL(updateById 不回写 NULL 字段,必须显式语句) */
    @Update("""
            UPDATE programming_submission
            SET status = 'QUEUED',
                time_used_ms = NULL,
                memory_used_kb = NULL,
                score = NULL,
                result_detail = NULL,
                completed_at = NULL,
                updated_at = #{now}
            WHERE id = #{submissionId}
              AND status <> 'QUEUED'
            """)
    int resetForRejudge(@Param("submissionId") long submissionId, @Param("now") Instant now);

    @org.apache.ibatis.annotations.Select("""
            SELECT account_id AS accountId,
                   COUNT(*) AS submissionCount,
                   MAX(status = 'ACCEPTED') AS accepted,
                   MAX(score) AS bestScore,
                   MAX(submitted_at) AS lastSubmittedAt
            FROM programming_submission
            WHERE problem_id = #{problemId}
            GROUP BY account_id
            ORDER BY MAX(submitted_at) DESC
            """)
    java.util.List<AccountSubmissionSummary> summarizeByAccount(@Param("problemId") long problemId);
}
