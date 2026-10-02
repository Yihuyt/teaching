package cn.utcy.teaching.resource.infrastructure;

import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.resource.domain.ObjectDeletionJob;
import com.aliyun.oss.OSS;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObjectDeletionPersistenceTest {

    @Test
    void zeroAffectedQueueInsertIsExplicitConflict() {
        ObjectDeletionJobMapper jobs = mock(ObjectDeletionJobMapper.class);
        when(jobs.insert(any(ObjectDeletionJob.class))).thenReturn(0);

        assertThatThrownBy(() ->
                new ObjectDeletionQueueService(jobs).enqueue("bucket", "object"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("文件删除任务创建失败");
    }

    @Test
    void zeroAffectedProcessorUpdateIsExplicitConflict() {
        ObjectDeletionJobMapper jobs = mock(ObjectDeletionJobMapper.class);
        ObjectDeletionJob job = ObjectDeletionJob.pending("bucket", "object");
        when(jobs.selectForUpdate(job.getId())).thenReturn(job);
        when(jobs.updateById(any(ObjectDeletionJob.class))).thenReturn(0);

        assertThatThrownBy(() ->
                new ObjectDeletionProcessor(jobs, mock(OSS.class)).process(job.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("对象删除任务状态已变化，更新未生效");
    }
}
