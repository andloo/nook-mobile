package com.nook.mobile.data;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 把多路复用（含视频流）的 Ogg 文件重写为仅含 Vorbis 音频流的 Ogg 文件。
 * <p>
 * 背景：原项目 K.K. 曲目是 Theora(视频) + Vorbis(音频) 混合的 Ogg 文件，首个流为 theora。
 * Android ExoPlayer 的 Ogg 提取器仅识别"首个流为 vorbis/opus/flac"的纯音频文件，
 * 遇到 theora 首流即判定无法识别格式 → 播放报错（桌面版靠 Chromium 解码器提取音频轨才能播）。
 * <p>
 * 原理：Ogg 容器中每个逻辑流（按 bitstream serial 区分）的页面彼此独立、各自合法
 * （BOS/EOS 标志、页序号、granule、CRC 均按流内计数）。因此只需按 serial 过滤出 Vorbis
 * 流的全部页面逐字节复制到新文件，即可得到合法纯 Vorbis 音频流，无需重算 CRC。
 */
public final class OggRemuxer {

    private static final byte[] VORBIS = {'v', 'o', 'r', 'b', 'i', 's'};

    /** 首个流的识别结果。 */
    private static final class FirstStream {
        int packetType; // 识别头首字节：0x01=vorbis, 0x80=theora, 'OpusHead' 等
        byte[] magic;   // magic 6 字节
    }

    private OggRemuxer() {
    }

    /**
     * 确保本地 Ogg 文件可被 ExoPlayer 播放：
     * 若首个流的识别头不是音频（vorbis/opus/flac），则抽取其中 Vorbis 流重写该文件。
     *
     * @return true=已可播放或抽取成功；false=无法修复（文件非法或找不到 Vorbis 流）
     */
    public static boolean ensurePlayable(File file) {
        if (file == null || !file.exists() || file.length() < 28) {
            return false;
        }
        try {
            FirstStream first = readFirstStream(file);
            if (first == null) {
                return false;
            }
            if (isAudioStream(first.packetType, first.magic)) {
                return true; // 首个流即音频 → 直接可播放
            }
            long vorbisSerial = findVorbisSerial(file);
            if (vorbisSerial < 0) {
                return false; // 混合文件里没有 Vorbis 流，无法修复
            }
            return extractStream(file, vorbisSerial);
        } catch (IOException e) {
            return false;
        }
    }

    /** 判断识别头是否为音频流（vorbis / opus / flac）。 */
    private static boolean isAudioStream(int packetType, byte[] magic) {
        if (magic.length < 6) {
            return false;
        }
        if (packetType == 0x01 && startsWith(magic, VORBIS)) {
            return true; // Vorbis Identification Header
        }
        if (magic[0] == 'O' && magic[1] == 'p' && magic[2] == 'u'
                && magic[3] == 's' && magic[4] == 'H' && magic[5] == 'e') {
            return true; // OpusHead
        }
        if (magic[0] == 'f' && magic[1] == 'L' && magic[2] == 'a'
                && magic[3] == 'C') {
            return true; // fLaC
        }
        return false;
    }

    /** 解析文件首个 Ogg 页面，返回首流识别头。 */
    private static FirstStream readFirstStream(File file) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            Page h = readPage(in);
            if (h == null || h.segmentCount == 0) {
                return null;
            }
            byte[] payload = readExactly(in, h.payloadLength);
            FirstStream fs = new FirstStream();
            fs.packetType = payload[0] & 0xFF;
            fs.magic = new byte[6];
            int n = Math.min(6, payload.length - 1);
            if (n < 6) {
                return null;
            }
            System.arraycopy(payload, 1, fs.magic, 0, 6);
            return fs;
        }
    }

    /** 扫描全部页面，返回首个 Vorbis 流（识别头 0x01 'vorbis'）的 serial；无则 -1。 */
    private static long findVorbisSerial(File file) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            Page h;
            while ((h = readPage(in)) != null) {
                byte[] payload = readExactly(in, h.payloadLength);
                if ((h.headerType & 0x02) != 0 // BOS 页面携带识别头
                        && h.segmentCount > 0 && payload.length >= 7
                        && (payload[0] & 0xFF) == 0x01 && startsWith(payload, 1, VORBIS)) {
                    return h.serial;
                }
            }
        }
        return -1;
    }

    /** 把指定 serial 的所有页面逐字节复制到临时文件后替换原文件。 */
    private static boolean extractStream(File file, long serial) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".vorbis.tmp");
        boolean ok = false;
        try (InputStream in = new BufferedInputStream(new FileInputStream(file));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
            Page h;
            while ((h = readPage(in)) != null) {
                byte[] payload = readExactly(in, h.payloadLength);
                if (h.serial == serial) {
                    out.write(h.rawHeader);
                    out.write(payload);
                }
            }
            out.flush();
            ok = true;
        } finally {
            if (!ok) {
                tmp.delete();
            }
        }
        if (ok) {
            if (!tmp.renameTo(file)) {
                // 改名失败（罕见）：尝试先删后改
                if (file.delete() && tmp.renameTo(file)) {
                    return true;
                }
                tmp.delete();
                return false;
            }
            return true;
        }
        return false;
    }

    /** 一个 Ogg 页面：原始 27+segmentCount 字节头 + 负载长度。 */
    private static final class Page {
        byte[] rawHeader;
        int headerType;
        long serial; // 无符号 32 位 bitstream serial
        int segmentCount;
        int payloadLength;
    }

    /** 读取一个 Ogg 页面；流已结束返回 null。 */
    private static Page readPage(InputStream in) throws IOException {
        byte[] header = new byte[27];
        int read = readFully(in, header, 0, 27);
        if (read == -1) {
            return null; // 干净的 EOF
        }
        if (read < 27) {
            throw new IOException("truncated ogg page header");
        }
        if (header[0] != 'O' || header[1] != 'g' || header[2] != 'g' || header[3] != 'S') {
            throw new IOException("invalid ogg magic");
        }
        int segmentCount = header[26] & 0xFF;
        byte[] segTable = new byte[segmentCount];
        if (segmentCount > 0) {
            readFully(in, segTable, 0, segmentCount);
        }
        int payloadLength = 0;
        for (int b : segTable) {
            payloadLength += b & 0xFF;
        }
        Page page = new Page();
        page.segmentCount = segmentCount;
        page.headerType = header[5] & 0xFF;
        page.serial = leInt(header, 14) & 0xFFFFFFFFL; // 无符号化
        page.payloadLength = payloadLength;
        page.rawHeader = new byte[27 + segmentCount];
        System.arraycopy(header, 0, page.rawHeader, 0, 27);
        System.arraycopy(segTable, 0, page.rawHeader, 27, segmentCount);
        return page;
    }

    /** 读取 len 字节；EOF 时抛异常。 */
    private static byte[] readExactly(InputStream in, int len) throws IOException {
        byte[] data = new byte[len];
        if (len > 0) {
            int n = readFully(in, data, 0, len);
            if (n < len) {
                throw new IOException("truncated ogg payload");
            }
        }
        return data;
    }

    /** 尽可能读满 dst[off..off+len)；返回实际读取数；首字节即 EOF 返回 -1。 */
    private static int readFully(InputStream in, byte[] dst, int off, int len) throws IOException {
        int total = 0;
        int first = in.read();
        if (first == -1) {
            return -1;
        }
        dst[off] = (byte) first;
        total = 1;
        while (total < len) {
            int r = in.read(dst, off + total, len - total);
            if (r == -1) {
                break;
            }
            total += r;
        }
        return total;
    }

    private static boolean startsWith(byte[] data, int offset, byte[] magic) {
        for (int i = 0; i < magic.length; i++) {
            if (offset + i >= data.length || data[offset + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(byte[] data, byte[] magic) {
        return startsWith(data, 0, magic);
    }

    /** 小端 32 位整数。 */
    private static int leInt(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }
}
