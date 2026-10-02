package cn.utcy.teaching.course.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("course_member")
public class CourseMember {

    @TableId
    private Long id;
    private Long courseId;
    private Long accountId;
    private Instant joinedAt;

    protected CourseMember() {
    }

    public CourseMember(Long id, Long courseId, Long accountId, Instant joinedAt) {
        this.id = id;
        this.courseId = courseId;
        this.accountId = accountId;
        this.joinedAt = joinedAt;
    }

    public static CourseMember create(long courseId, long accountId) {
        return new CourseMember(null, courseId, accountId, Instant.now());
    }

    public Long getId() {
        return id;
    }

    public Long getCourseId() {
        return courseId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }
}
