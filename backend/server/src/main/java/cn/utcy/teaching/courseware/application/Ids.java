package cn.utcy.teaching.courseware.application;

import java.security.SecureRandom;

/** 短随机 id(nanoid 同款字母表),与 TS 端 id 形态一致 */
final class Ids {

    private static final String ALPHABET =
            "useandom-26T198340PX75pxJACKVERYMINDBUSHWOLF_GQZbfghjklqvwyzrict";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    static String random(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(64)));
        }
        return sb.toString();
    }
}
