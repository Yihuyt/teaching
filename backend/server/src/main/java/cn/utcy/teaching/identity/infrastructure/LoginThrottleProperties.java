package cn.utcy.teaching.identity.infrastructure;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 登录防暴力破解:一个观察窗口内失败到阈值就锁一段时间。账号维度防猜密码,来源 IP 维度防撞库。
 *
 * @param accountMaxFailures 同一账号在窗口内允许的失败次数,达到即锁
 * @param ipMaxFailures      同一来源 IP 在窗口内允许的失败次数(不分账号),达到即锁
 * @param window             失败计数的观察窗口
 * @param lockDuration       锁定时长
 */
@Validated
@ConfigurationProperties("teaching.login-throttle")
public record LoginThrottleProperties(
        @Min(1) int accountMaxFailures,
        @Min(1) int ipMaxFailures,
        @NotNull Duration window,
        @NotNull Duration lockDuration
) {

    public LoginThrottleProperties {
        if (window != null && (window.isZero() || window.isNegative())) {
            throw new IllegalArgumentException("登录失败观察窗口必须大于 0");
        }
        if (lockDuration != null && (lockDuration.isZero() || lockDuration.isNegative())) {
            throw new IllegalArgumentException("登录锁定时长必须大于 0");
        }
    }
}
