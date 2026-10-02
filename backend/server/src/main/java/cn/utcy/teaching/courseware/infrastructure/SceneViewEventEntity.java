package cn.utcy.teaching.courseware.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("courseware_scene_view")
public class SceneViewEventEntity {

    @TableId
    private Long id;
    private Long coursewareId;
    private Long accountId;
    private String sceneId;
    private LocalDateTime viewedAt;

    protected SceneViewEventEntity() {
    }

    public SceneViewEventEntity(long coursewareId, long accountId, String sceneId,
                               LocalDateTime viewedAt) {
        this.coursewareId = coursewareId;
        this.accountId = accountId;
        this.sceneId = sceneId;
        this.viewedAt = viewedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getCoursewareId() {
        return coursewareId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getSceneId() {
        return sceneId;
    }

    public LocalDateTime getViewedAt() {
        return viewedAt;
    }
}
