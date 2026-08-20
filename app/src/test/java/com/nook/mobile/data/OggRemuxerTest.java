package com.nook.mobile.data;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * OggRemuxer 单测：使用真实样本验证 theora+vorbis 混合文件可被抽取为纯 vorbis。
 */
public class OggRemuxerTest {

    @Test
    public void mixedKk_becomesVorbisOnly() throws IOException {
        File src = new File("src/test/resources/kk_agent_mux.ogg");
        assertTrue("fixture missing", src.exists());
        File copy = File.createTempFile("kk_mux_", ".ogg");
        Files.copy(src.toPath(), copy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try {
            long before = copy.length();
            // 原始文件首流应为 theora（视频），ensurePlayable 应重写
            assertTrue(OggRemuxer.ensurePlayable(copy));
            // 重写后首流应为 vorbis 音频
            assertTrue("first stream not audio", firstStreamIsAudio(copy));
            // 抽取后应更小（去掉 theora 页面）
            assertTrue("expected smaller after extraction, before=" + before + " after=" + copy.length(),
                    copy.length() < before);
        } finally {
            copy.delete();
        }
    }

    @Test
    public void pureVorbis_isNoOp() throws IOException {
        File src = new File("src/test/resources/hourly_3am_pure.ogg");
        assertTrue("fixture missing", src.exists());
        File copy = File.createTempFile("hourly_pure_", ".ogg");
        Files.copy(src.toPath(), copy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try {
            long before = copy.length();
            assertTrue(OggRemuxer.ensurePlayable(copy));
            // 纯 vorbis 应为 no-op，字节数不变
            assertEquals(before, copy.length());
            assertTrue(firstStreamIsAudio(copy));
        } finally {
            copy.delete();
        }
    }

    /** 读取首页面，判断首流识别头是否为音频（vorbis/opus/flac）。 */
    private static boolean firstStreamIsAudio(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] header = new byte[27];
            int n = readFully(in, header);
            if (n < 27) {
                return false;
            }
            int segCount = header[26] & 0xFF;
            byte[] seg = new byte[segCount];
            readFully(in, seg);
            int payload = 0;
            for (byte b : seg) {
                payload += b & 0xFF;
            }
            byte[] data = new byte[payload];
            readFully(in, data);
            if (data.length < 7) {
                return false;
            }
            int type = data[0] & 0xFF;
            return (type == 0x01 && "vorbis".equals(new String(data, 1, 6, "US-ASCII")));
        }
    }

    private static int readFully(InputStream in, byte[] dst) throws IOException {
        int total = 0;
        while (total < dst.length) {
            int r = in.read(dst, total, dst.length - total);
            if (r == -1) {
                break;
            }
            total += r;
        }
        return total;
    }
}
