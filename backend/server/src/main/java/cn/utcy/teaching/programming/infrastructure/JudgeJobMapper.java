package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.programming.domain.JudgeJob;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

@Mapper
public interface JudgeJobMapper extends BaseMapper<JudgeJob> {

    @Select("SELECT * FROM judge_job WHERE id = #{jobId} FOR UPDATE")
    JudgeJob selectForUpdate(@Param("jobId") String jobId);

    @Select("SELECT * FROM judge_job WHERE submission_id = #{submissionId} FOR UPDATE")
    JudgeJob selectBySubmissionForUpdate(@Param("submissionId") long submissionId);

    @Update("""
            UPDATE judge_job
            SET status = 'published',
                stream_record_id = #{streamRecordId},
                published_at = #{publishedAt}
            WHERE id = #{jobId}
              AND status = 'pending'
            """)
    int markPublished(
            @Param("jobId") String jobId,
            @Param("streamRecordId") String streamRecordId,
            @Param("publishedAt") Instant publishedAt
    );

    /** 复位重投:MyBatis-Plus 的 updateById 不回写 NULL 字段,置空必须走显式语句 */
    @Update("""
            UPDATE judge_job
            SET status = 'pending',
                attempt = #{attempt},
                requeue_count = #{requeueCount},
                stream_record_id = NULL,
                published_at = NULL,
                completed_at = NULL
            WHERE id = #{jobId}
              AND status = #{expectedStatus}
            """)
    int resetForRepublish(
            @Param("jobId") String jobId,
            @Param("attempt") int attempt,
            @Param("requeueCount") int requeueCount,
            @Param("expectedStatus") String expectedStatus
    );
}
