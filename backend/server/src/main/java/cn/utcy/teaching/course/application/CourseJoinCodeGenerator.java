package cn.utcy.teaching.course.application;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;

@Component
public class CourseJoinCodeGenerator {

    public static final int CODE_LENGTH = 10;

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private final SecureRandom random = new SecureRandom();

    public String next() {
        byte[] bytes = new byte[CODE_LENGTH / 2];
        random.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }
}
