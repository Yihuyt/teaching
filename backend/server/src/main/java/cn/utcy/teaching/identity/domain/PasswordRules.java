package cn.utcy.teaching.identity.domain;

/** 密码长度规则的唯一出处:接口校验、服务校验、root 初始密码文件都按这里 */
public final class PasswordRules {

    public static final int MIN_LENGTH = 6;
    public static final int MAX_LENGTH = 128;
    public static final String LENGTH_MESSAGE = "密码长度必须为 6 至 128 个字符";

    private PasswordRules() {
    }

    public static boolean lengthOk(String password) {
        return password != null && password.length() >= MIN_LENGTH && password.length() <= MAX_LENGTH;
    }
}
