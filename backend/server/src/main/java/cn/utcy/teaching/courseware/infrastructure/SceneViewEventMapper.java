package cn.utcy.teaching.courseware.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SceneViewEventMapper extends BaseMapper<SceneViewEventEntity> {

    @Select("""
            SELECT account_id AS accountId,
                   COUNT(DISTINCT scene_id) AS scenesViewed,
                   MAX(viewed_at) AS lastViewedAt
            FROM courseware_scene_view
            WHERE courseware_id = #{coursewareId}
            GROUP BY account_id
            ORDER BY account_id
            """)
    List<SceneViewAccountStat> summarizeByAccount(@Param("coursewareId") long coursewareId);

    record SceneViewAccountStat(long accountId, long scenesViewed, LocalDateTime lastViewedAt) {
    }
}
