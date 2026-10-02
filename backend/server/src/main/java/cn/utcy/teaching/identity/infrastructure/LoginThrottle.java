package cn.utcy.teaching.identity.infrastructure;

import cn.utcy.teaching.identity.application.TooManyLoginAttemptsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 登录防暴力破解,计数放 Redis(带过期,多实例共用):
 * 每次失败给账号和来源 IP 各记一次;窗口内达到阈值就写一个锁键,锁到期自动解开;登录成功清掉账号的失败计数。
 * 锁着的时候直接拒绝,不再验密码,也不再计数。
 */
@Component
public class LoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    static final String ACCOUNT_FAIL_PREFIX = "teaching:login:fail:account:";
    static final String IP_FAIL_PREFIX = "teaching:login:fail:ip:";
    static final String ACCOUNT_LOCK_PREFIX = "teaching:login:lock:account:";
    static final String IP_LOCK_PREFIX = "teaching:login:lock:ip:";

    /**
     * KEYS[1] 失败计数键,KEYS[2] 锁键;ARGV[1] 阈值,ARGV[2] 窗口秒数,ARGV[3] 锁定秒数。
     * 计数 +1(第一次时设窗口过期);达到阈值就设锁并清计数。返回 1 表示这次锁上了。
     */
    static final RedisScript<Long> RECORD_FAILURE = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            if count >= tonumber(ARGV[1]) then
              redis.call('SET', KEYS[2], '1', 'EX', ARGV[3])
              redis.call('DEL', KEYS[1])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final LoginThrottleProperties properties;

    public LoginThrottle(StringRedisTemplate redis, LoginThrottleProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public void check(String username, String remoteAddress) {
        for (String lockKey : List.of(ACCOUNT_LOCK_PREFIX + accountKey(username), IP_LOCK_PREFIX + ipKey(remoteAddress))) {
            Long ttl = redis.getExpire(lockKey, TimeUnit.SECONDS);
            if (ttl != null && ttl > 0) {
                throw new TooManyLoginAttemptsException(Duration.ofSeconds(ttl));
            }
        }
    }

    public void recordFailure(String username, String remoteAddress) {
        String account = accountKey(username);
        String ip = ipKey(remoteAddress);
        boolean accountLocked = runFailure(ACCOUNT_FAIL_PREFIX + account, ACCOUNT_LOCK_PREFIX + account, properties.accountMaxFailures());
        boolean ipLocked = runFailure(IP_FAIL_PREFIX + ip, IP_LOCK_PREFIX + ip, properties.ipMaxFailures());
        if (accountLocked) {
            log.warn("登录失败达到 {} 次,账号已锁定 {}:username={} ip={}", properties.accountMaxFailures(), properties.lockDuration(), username, remoteAddress);
        }
        if (ipLocked) {
            log.warn("登录失败达到 {} 次,来源 IP 已锁定 {}:ip={}", properties.ipMaxFailures(), properties.lockDuration(), remoteAddress);
        }
    }

    /** 登录成功后调用:清掉这个账号的失败计数(IP 的计数保留,撞库时换账号不清零) */
    public void recordSuccess(String username) {
        redis.delete(ACCOUNT_FAIL_PREFIX + accountKey(username));
    }

    private boolean runFailure(String failKey, String lockKey, int threshold) {
        Long locked = redis.execute(RECORD_FAILURE, List.of(failKey, lockKey),
                String.valueOf(threshold), String.valueOf(properties.window().toSeconds()), String.valueOf(properties.lockDuration().toSeconds()));
        return Objects.equals(locked, 1L);
    }

    private static String accountKey(String username) {
        return (username == null ? "" : username.strip()).toLowerCase(Locale.ROOT);
    }

    private static String ipKey(String remoteAddress) {
        return remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress.strip();
    }
}
