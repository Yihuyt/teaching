package cn.utcy.teaching.judge.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

public final class OssCredentialsReader {

    private static final String ID_PREFIX = "ALIYUN_OSS_ACCESS_KEY_ID=";
    private static final String SECRET_PREFIX = "ALIYUN_OSS_ACCESS_KEY_SECRET=";

    private OssCredentialsReader() {
    }

    public static OssCredentials read(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("OSS 密钥路径必须指向普通文件");
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (lines.size() != 2
                    || !lines.get(0).startsWith(ID_PREFIX)
                    || !lines.get(1).startsWith(SECRET_PREFIX)) {
                throw new IllegalStateException("OSS 密钥文件必须严格包含 AccessKey ID 和 AccessKey Secret 两行");
            }
            String id = lines.get(0).substring(ID_PREFIX.length());
            String secret = lines.get(1).substring(SECRET_PREFIX.length());
            if (id.isBlank() || secret.isBlank() || !id.equals(id.strip()) || !secret.equals(secret.strip())) {
                throw new IllegalStateException("OSS 密钥文件字段不得为空或包含首尾空白");
            }
            return new OssCredentials(id, secret);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 OSS 密钥文件", exception);
        }
    }
}
