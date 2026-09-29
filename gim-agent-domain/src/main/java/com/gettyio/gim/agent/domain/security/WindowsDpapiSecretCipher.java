package com.gettyio.gim.agent.domain.security;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.Structure.FieldOrder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Windows DPAPI（CryptProtectData / CryptUnprotectData，当前用户范围）实现，
 * 通过 JNA 绑定 crypt32.dll。密文前缀 {@code dpapi:}。
 * <p>仅在 Windows 运行时可用；由 {@link SecretCipherFactory} 按 OS 选择。</p>
 */
public class WindowsDpapiSecretCipher implements SecretCipher {

    private static final String PREFIX = "dpapi:";
    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x01;

    private final Crypt32 crypt32;

    public WindowsDpapiSecretCipher() {
        this.crypt32 = Crypt32.INSTANCE;
    }

    @Override
    public String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }
        Crypt32.DATA_BLOB in = new Crypt32.DATA_BLOB(plainText.getBytes(StandardCharsets.UTF_8));
        Crypt32.DATA_BLOB out = new Crypt32.DATA_BLOB();
        try {
            if (!crypt32.CryptProtectData(in, "gim-agent", null, null, null, CRYPTPROTECT_UI_FORBIDDEN, out)) {
                throw new SecurityOperationException("CryptProtectData 调用失败");
            }
            out.read();
            byte[] encrypted = out.getDataBytes();
            return PREFIX + Base64.getEncoder().encodeToString(encrypted);
        } finally {
            if (out.pbData != null) {
                Kernel32Util.localFree(out.pbData);
            }
        }
    }

    @Override
    public String decrypt(String cipherText) {
        if (cipherText == null) {
            return null;
        }
        if (!cipherText.startsWith(PREFIX)) {
            throw new SecurityOperationException("密文前缀不匹配 DPAPI");
        }
        byte[] raw = Base64.getDecoder().decode(cipherText.substring(PREFIX.length()));
        Crypt32.DATA_BLOB in = new Crypt32.DATA_BLOB(raw);
        Crypt32.DATA_BLOB out = new Crypt32.DATA_BLOB();
        try {
            if (!crypt32.CryptUnprotectData(in, null, null, null, null, CRYPTPROTECT_UI_FORBIDDEN, out)) {
                throw new SecurityOperationException("CryptUnprotectData 调用失败");
            }
            out.read();
            return new String(out.getDataBytes(), StandardCharsets.UTF_8);
        } finally {
            if (out.pbData != null) {
                Kernel32Util.localFree(out.pbData);
            }
        }
    }

    @Override
    public String prefix() {
        return PREFIX;
    }

    /**
     * crypt32.dll 绑定
     */
    public interface Crypt32 extends StdCallLibrary {

        Crypt32 INSTANCE = com.sun.jna.Native.load("crypt32", Crypt32.class,
                com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);

        boolean CryptProtectData(DATA_BLOB pDataIn, String szDataDescr, DATA_BLOB pOptionalEntropy,
                                 Pointer pvReserved, DATA_BLOB pPromptStruct, int dwFlags, DATA_BLOB pDataOut);

        boolean CryptUnprotectData(DATA_BLOB pDataIn, Pointer ppszDataDescr, DATA_BLOB pOptionalEntropy,
                                   Pointer pvReserved, DATA_BLOB pPromptStruct, int dwFlags, DATA_BLOB pDataOut);

        @FieldOrder({"cbData", "pbData"})
        class DATA_BLOB extends Structure {
            public int cbData;
            public Pointer pbData;

            public DATA_BLOB() {
                super();
            }

            public DATA_BLOB(byte[] data) {
                super();
                this.cbData = data.length;
                this.pbData = new com.sun.jna.Memory(data.length == 0 ? 1 : data.length);
                this.pbData.write(0, data, 0, data.length);
            }

            public byte[] getDataBytes() {
                if (pbData == null || cbData == 0) {
                    return new byte[0];
                }
                return pbData.getByteArray(0, cbData);
            }
        }
    }

    /**
     * LocalFree 封装（kernel32）
     */
    private static final class Kernel32Util {
        interface Kernel32 extends StdCallLibrary {
            Kernel32 INSTANCE = com.sun.jna.Native.load("kernel32", Kernel32.class,
                    com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);

            Pointer LocalFree(Pointer hLocal);
        }

        static void localFree(Pointer p) {
            try {
                Kernel32.INSTANCE.LocalFree(p);
            } catch (Exception ignored) {
                // 释放失败不影响结果
            }
        }
    }
}
