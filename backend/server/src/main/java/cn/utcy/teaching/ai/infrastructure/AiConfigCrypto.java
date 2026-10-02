package cn.utcy.teaching.ai.infrastructure;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 用户级 AI 密钥的落库加密(AES-256-GCM):密文 = IV(12B) || GCM 输出。
 * 加密密钥从平台 secret 文件读入(base64 的 32 字节),启动即校验——
 * 缺失/格式错说明部署不完整,立即炸而不是等到用户配置时才失败。
 */
@Component
public class AiConfigCrypto {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AiConfigCrypto(AiConfigProperties properties) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(
                    Files.readString(properties.keyPath(), StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "AI 配置加密密钥读取失败: " + properties.keyPath(), e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "AI 配置加密密钥不是合法 base64: " + properties.keyPath(), e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "AI 配置加密密钥必须是 32 字节(base64 前),当前 " + raw.length + " 字节");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public byte[] encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_BYTES + sealed.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(sealed, 0, out, IV_BYTES, sealed.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("配置加密失败", e);
        }
    }

    public String decrypt(byte[] cipherBytes) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, cipherBytes, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(
                    Arrays.copyOfRange(cipherBytes, IV_BYTES, cipherBytes.length));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // 平台加密密钥换过而旧密文还在:按未配置处理由调用方决定,这里如实抛错
            throw new IllegalStateException("配置解密失败(平台加密密钥可能已更换)", e);
        }
    }
}
