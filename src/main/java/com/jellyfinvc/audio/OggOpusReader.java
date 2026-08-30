package com.jellyfinvc.audio;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Minimal Ogg container demuxer for an Ogg/Opus stream (RFC 3533 + RFC 7845).
 * Reads pages off an {@link InputStream} and yields the raw Opus packets
 * inside them, having already consumed and interpreted the two mandatory
 * header packets (OpusHead / OpusTags).
 *
 * <p>Jellyfin can transcode to Ogg/Opus but - on at least some deployments -
 * cannot stream raw PCM/WAV at all (its transcoder 500s on any container that
 * needs an up-front known length). Since Simple Voice Chat's own API exposes
 * an {@code OpusDecoder}, decoding here avoids needing any third-party codec
 * library in the plugin.
 */
public final class OggOpusReader {

    private final InputStream in;
    private final Deque<byte[]> pendingPackets = new ArrayDeque<>();
    private byte[] partialPacket = null; // spans a page boundary
    private boolean headerParsed = false;
    private boolean eof = false;

    private int channelCount = 1;
    private int preSkip = 0;

    public OggOpusReader(InputStream in) {
        this.in = in;
    }

    public int channelCount() {
        return channelCount;
    }

    public int preSkip() {
        return preSkip;
    }

    /**
     * Reads and validates the OpusHead/OpusTags header packets. Must be
     * called once before {@link #nextAudioPacket()}.
     */
    public void readHeader() throws IOException {
        byte[] head = nextRawPacket();
        if (head == null || head.length < 19 || !matches(head, 0, "OpusHead")) {
            throw new IOException("Expected an OpusHead packet at the start of the Ogg stream");
        }
        channelCount = head[9] & 0xFF;
        preSkip = (head[10] & 0xFF) | ((head[11] & 0xFF) << 8);

        byte[] tags = nextRawPacket();
        if (tags == null || tags.length < 8 || !matches(tags, 0, "OpusTags")) {
            throw new IOException("Expected an OpusTags packet after OpusHead");
        }
        headerParsed = true;
    }

    /** Returns the next raw Opus audio packet, or null at end of stream. */
    public byte[] nextAudioPacket() throws IOException {
        if (!headerParsed) {
            readHeader();
        }
        return nextRawPacket();
    }

    private static boolean matches(byte[] data, int offset, String magic) {
        byte[] want = magic.getBytes(StandardCharsets.US_ASCII);
        if (data.length < offset + want.length) {
            return false;
        }
        for (int i = 0; i < want.length; i++) {
            if (data[offset + i] != want[i]) {
                return false;
            }
        }
        return true;
    }

    private byte[] nextRawPacket() throws IOException {
        while (pendingPackets.isEmpty()) {
            if (eof) {
                return null;
            }
            readPage();
        }
        return pendingPackets.poll();
    }

    private void readPage() throws IOException {
        byte[] header = readNBytesStrict(27);
        if (header == null) {
            eof = true;
            return;
        }
        if (header[0] != 'O' || header[1] != 'g' || header[2] != 'g' || header[3] != 'S') {
            throw new IOException("Not a valid Ogg page (bad capture pattern)");
        }
        int headerType = header[5] & 0xFF;
        boolean continued = (headerType & 0x01) != 0;
        int pageSegments = header[26] & 0xFF;

        byte[] segmentTable = readNBytesStrict(pageSegments);
        if (segmentTable == null) {
            throw new EOFException("Truncated Ogg page segment table");
        }

        // Group lacing values into packet lengths; a run of 255s continues a
        // packet, terminated by a value < 255. A trailing unterminated run
        // means the last packet continues onto the next page.
        int i = 0;
        boolean firstPacketOnPage = true;
        while (i < pageSegments) {
            int length = 0;
            boolean terminated = false;
            while (i < pageSegments) {
                int seg = segmentTable[i++] & 0xFF;
                length += seg;
                if (seg < 255) {
                    terminated = true;
                    break;
                }
            }
            byte[] chunk = readNBytesStrict(length);
            if (chunk == null) {
                throw new EOFException("Truncated Ogg page payload");
            }

            if (firstPacketOnPage && continued && partialPacket != null) {
                byte[] merged = new byte[partialPacket.length + chunk.length];
                System.arraycopy(partialPacket, 0, merged, 0, partialPacket.length);
                System.arraycopy(chunk, 0, merged, partialPacket.length, chunk.length);
                chunk = merged;
                partialPacket = null;
            }
            firstPacketOnPage = false;

            if (terminated) {
                pendingPackets.add(chunk);
            } else {
                // Unterminated (ends on a 255): continues on the next page.
                partialPacket = chunk;
            }
        }
    }

    private byte[] readNBytesStrict(int n) throws IOException {
        if (n == 0) {
            return new byte[0];
        }
        byte[] buf = new byte[n];
        int total = 0;
        while (total < n) {
            int r = in.read(buf, total, n - total);
            if (r < 0) {
                return total == 0 ? null : null;
            }
            total += r;
        }
        return buf;
    }
}
