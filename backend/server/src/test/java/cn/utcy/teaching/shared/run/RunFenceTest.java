package cn.utcy.teaching.shared.run;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RunFenceTest {

    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-09-04T00:00:00Z");

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("节流:间隔内的多次 beat 只落一次库")
    void throttlesWrites() {
        TestClock clock = new TestClock();
        AtomicInteger writes = new AtomicInteger();
        RunFence.Heartbeat heartbeat = new RunFence.Heartbeat(clock, () -> {
            writes.incrementAndGet();
            return true;
        });

        assertThat(heartbeat.beat()).isTrue();
        assertThat(heartbeat.beat()).isTrue();
        clock.advanceSeconds(RunFence.BEAT_INTERVAL.toSeconds());
        assertThat(heartbeat.beat()).isTrue();

        assertThat(writes.get()).isEqualTo(2);
        assertThat(heartbeat.lost()).isFalse();
    }

    @Test
    @DisplayName("围栏失守:落库影响 0 行后一直失守,不再落库")
    void lostIsSticky() {
        TestClock clock = new TestClock();
        AtomicInteger writes = new AtomicInteger();
        RunFence.Heartbeat heartbeat = new RunFence.Heartbeat(clock, () -> {
            writes.incrementAndGet();
            return false;
        });

        assertThat(heartbeat.beat()).isFalse();
        clock.advanceSeconds(60);
        assertThat(heartbeat.beat()).isFalse();
        assertThat(heartbeat.lost()).isTrue();
        assertThat(writes.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("落库抛错(库瞬时不可用)不算失守,下一间隔再试")
    void writeFailureIsNotLoss() {
        TestClock clock = new TestClock();
        RunFence.Heartbeat heartbeat = new RunFence.Heartbeat(clock, () -> {
            throw new IllegalStateException("db down");
        });

        assertThat(heartbeat.beat()).isTrue();
        assertThat(heartbeat.lost()).isFalse();
    }

    @Test
    @DisplayName("其他落库发现失守可登记进来")
    void markLost() {
        RunFence.Heartbeat heartbeat = new RunFence.Heartbeat(new TestClock(), () -> true);
        heartbeat.markLost();
        assertThat(heartbeat.beat()).isFalse();
    }
}
