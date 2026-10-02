package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.ForbiddenOperationException;
import cn.utcy.teaching.shared.actor.OwnershipPolicy;
import cn.utcy.teaching.shared.actor.SystemRole;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JudgeWorkerRegistryTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
    private final CurrentActor currentActor = mock(CurrentActor.class);
    private final JudgeWorkerRegistry registry = new JudgeWorkerRegistry(
            redis, properties(), currentActor, new OwnershipPolicy());

    @Test
    void teacherCannotListWorkers() {
        when(currentActor.require()).thenReturn(new Actor(7L, "teacher", SystemRole.TEACHER));

        assertThatThrownBy(registry::workers).isInstanceOf(ForbiddenOperationException.class);
        verify(redis, never()).opsForStream();
    }

    @Test
    void missingConsumerGroupMeansNoWorkers() {
        when(currentActor.require()).thenReturn(new Actor(1L, "root", SystemRole.ROOT));
        when(redis.opsForStream()).thenReturn(streams);
        when(streams.consumers(anyString(), anyString()))
                .thenThrow(new RedisSystemException("NOGROUP No such key 'judge.jobs' or consumer group", null));

        assertThat(registry.workers()).isEmpty();
    }

    private static JudgeProperties properties() {
        return new JudgeProperties("judge.jobs", "judge.results", "backend-1", "judge-workers",
                Duration.ofMinutes(10), Duration.ofMinutes(15), 3);
    }
}
