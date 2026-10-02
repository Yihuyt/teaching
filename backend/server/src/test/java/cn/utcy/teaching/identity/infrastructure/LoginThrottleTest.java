package cn.utcy.teaching.identity.infrastructure;

import cn.utcy.teaching.identity.application.TooManyLoginAttemptsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginThrottleTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final LoginThrottle throttle = new LoginThrottle(redis,
            new LoginThrottleProperties(5, 30, Duration.ofMinutes(15), Duration.ofMinutes(15)));

    @Test
    @DisplayName("账号锁着:拒绝,提示按剩余时间取整到分钟;没锁就放行")
    void rejectsWhileLocked() {
        when(redis.getExpire(LoginThrottle.ACCOUNT_LOCK_PREFIX + "alice", TimeUnit.SECONDS)).thenReturn(601L);
        assertThatThrownBy(() -> throttle.check(" Alice ", "10.0.0.8"))
                .isInstanceOf(TooManyLoginAttemptsException.class)
                .hasMessage("登录失败次数过多,请 11 分钟后再试")
                .extracting(e -> ((TooManyLoginAttemptsException) e).retryAfter()).isEqualTo(Duration.ofSeconds(601));

        when(redis.getExpire(LoginThrottle.ACCOUNT_LOCK_PREFIX + "bob", TimeUnit.SECONDS)).thenReturn(-2L);
        when(redis.getExpire(LoginThrottle.IP_LOCK_PREFIX + "10.0.0.8", TimeUnit.SECONDS)).thenReturn(-2L);
        assertThatCode(() -> throttle.check("bob", "10.0.0.8")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("来源 IP 锁着:换账号也拒绝,秒数不足一分钟按秒说")
    void rejectsLockedAddressForAnyAccount() {
        when(redis.getExpire(LoginThrottle.ACCOUNT_LOCK_PREFIX + "carol", TimeUnit.SECONDS)).thenReturn(-2L);
        when(redis.getExpire(LoginThrottle.IP_LOCK_PREFIX + "203.0.113.5", TimeUnit.SECONDS)).thenReturn(40L);
        assertThatThrownBy(() -> throttle.check("carol", "203.0.113.5")).hasMessage("登录失败次数过多,请 40 秒后再试");
    }

    @Test
    @DisplayName("记一次失败:账号和 IP 各跑一次计数脚本,阈值、窗口秒数、锁定秒数按配置传;脚本返回 1 表示锁上了")
    void recordsFailureForAccountAndAddress() {
        when(redis.execute(eq(LoginThrottle.RECORD_FAILURE), anyList(), any(), any(), any())).thenReturn(1L, 0L);
        throttle.recordFailure("Alice", "10.0.0.8");
        verify(redis).execute(LoginThrottle.RECORD_FAILURE,
                List.of(LoginThrottle.ACCOUNT_FAIL_PREFIX + "alice", LoginThrottle.ACCOUNT_LOCK_PREFIX + "alice"), "5", "900", "900");
        verify(redis).execute(LoginThrottle.RECORD_FAILURE,
                List.of(LoginThrottle.IP_FAIL_PREFIX + "10.0.0.8", LoginThrottle.IP_LOCK_PREFIX + "10.0.0.8"), "30", "900", "900");
    }

    @Test
    @DisplayName("登录成功:清账号的失败计数,IP 的计数留着")
    void successClearsAccountFailures() {
        throttle.recordSuccess("Alice");
        verify(redis).delete(LoginThrottle.ACCOUNT_FAIL_PREFIX + "alice");
        verify(redis, never()).delete(LoginThrottle.IP_FAIL_PREFIX + "10.0.0.8");
    }

    @Test
    @DisplayName("计数脚本:第一次失败设窗口过期;到阈值设锁、清计数")
    void failureScriptShape() {
        String script = LoginThrottle.RECORD_FAILURE.getScriptAsString();
        assertThat(script).contains("INCR").contains("EXPIRE").contains("'SET', KEYS[2], '1', 'EX', ARGV[3]").contains("'DEL', KEYS[1]");
    }
}
