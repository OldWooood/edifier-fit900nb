package com.deatrg.fit900nb;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Edifier SPP v2 framing for Fit900NB.
 *
 * TX: AA EC CMD LEN_H LEN_L PAYLOAD... CRC
 * RX: BB EC CMD LEN_H LEN_L PAYLOAD... CRC   (some firmware sends CC instead of BB)
 *
 * CRC = sum(all bytes before CRC) & 0xFF.
 * Fit900NB product config uses encryption-method 0, so payloads are plaintext.
 */
final class Protocol {
    static final int HEADER_SEND = 0xAA;
    static final int HEADER_RECV = 0xBB;
    static final int HEADER_RECV_OLD = 0xCC;
    static final int APP_CODE = 0xEC;

    static final int CMD_SCENE_QUERY = 0x08;
    static final int CMD_SCENE_SET = 0x09;
    static final int CMD_EQ_QUERY = 0xD5;
    static final int CMD_EQ_SET = 0xC4;
    static final int CMD_VERSION = 0xC6;
    static final int CMD_NAME = 0xC9;
    static final int CMD_ANC_SET = 0xC1;
    static final int CMD_ANC_QUERY = 0xCC;
    static final int CMD_SHUTDOWN = 0xCE;
    static final int CMD_BATTERY = 0xD0;
    static final int CMD_FUNCTION = 0xD8;
    static final int CMD_DEVICE_STATE = 0xF2;

    private Protocol() {
    }

    static byte[] frame(int cmd, byte... payload) {
        if (payload == null) payload = new byte[0];
        int len = payload.length;
        byte[] out = new byte[5 + len + 1];
        out[0] = (byte) HEADER_SEND;
        out[1] = (byte) APP_CODE;
        out[2] = (byte) cmd;
        out[3] = (byte) ((len >>> 8) & 0xFF);
        out[4] = (byte) (len & 0xFF);
        System.arraycopy(payload, 0, out, 5, len);
        out[out.length - 1] = (byte) crc(out, out.length - 1);
        return out;
    }

    static int crc(byte[] data, int endExclusive) {
        return crcRange(data, 0, endExclusive);
    }

    static int crcRange(byte[] data, int start, int endExclusive) {
        int sum = 0;
        for (int i = start; i < endExclusive; i++) sum += data[i] & 0xFF;
        return sum & 0xFF;
    }

    /**
     * Pull complete, CRC-valid frames out of a stream buffer. Incomplete trailing
     * bytes are kept for the next read; garbage bytes are skipped.
     */
    static List<Frame> drain(ByteArrayOutputStream buffer) {
        byte[] data = buffer.toByteArray();
        int off = 0;
        List<Frame> frames = new ArrayList<>();
        while (data.length - off >= 3) {
            int start = off;
            while (start < data.length && (data[start] & 0xFF) != HEADER_RECV
                    && (data[start] & 0xFF) != HEADER_RECV_OLD) {
                start++;
            }
            off = start;
            if (data.length - off < 5) break;

            int len = ((data[off + 3] & 0xFF) << 8) | (data[off + 4] & 0xFF);
            if (len < 0 || len > 4096) {
                off++;
                continue;
            }
            int total = 5 + len + 1;
            if (data.length - off < total) break;

            int expected = data[off + total - 1] & 0xFF;
            int actual = crcRange(data, off, off + total - 1);
            if (expected != actual) {
                // Not a frame boundary. Move one byte and keep looking.
                off++;
                continue;
            }

            byte[] payload = Arrays.copyOfRange(data, off + 5, off + 5 + len);
            frames.add(new Frame(
                    data[off] & 0xFF,
                    data[off + 1] & 0xFF,
                    data[off + 2] & 0xFF,
                    len,
                    payload,
                    expected));
            off += total;
        }

        buffer.reset();
        if (off < data.length) buffer.write(data, off, data.length - off);
        return frames;
    }

    static String hex(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte b : data) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString().toUpperCase();
    }

    static String ascii(byte[] data) {
        if (data == null) return "";
        int end = data.length;
        while (end > 0 && (data[end - 1] == 0 || data[end - 1] == 0x20)) end--;
        return new String(data, 0, end, java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    static final class Frame {
        final int header;
        final int appCode;
        final int cmd;
        final int length;
        final byte[] payload;
        final int crc;

        Frame(int header, int appCode, int cmd, int length, byte[] payload, int crc) {
            this.header = header;
            this.appCode = appCode;
            this.cmd = cmd;
            this.length = length;
            this.payload = payload;
            this.crc = crc;
        }

        byte[] raw() {
            byte[] out = new byte[5 + payload.length + 1];
            out[0] = (byte) header;
            out[1] = (byte) appCode;
            out[2] = (byte) cmd;
            out[3] = (byte) ((payload.length >>> 8) & 0xFF);
            out[4] = (byte) (payload.length & 0xFF);
            System.arraycopy(payload, 0, out, 5, payload.length);
            out[out.length - 1] = (byte) crc;
            return out;
        }
    }
}
