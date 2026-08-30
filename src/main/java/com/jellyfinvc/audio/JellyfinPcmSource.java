package com.jellyfinvc.audio;

import com.jellyfinvc.jellyfin.JellyfinClient;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Streams one Jellyfin track over HTTP as Ogg/Opus, decodes it with Simple
 * Voice Chat's own {@link OpusDecoder} (no third-party codec library needed),
 * and hands the result out as fixed 20ms (960 sample) frames.
 *
 * <p>Jellyfin is asked for Opus rather than raw PCM/WAV because - at least on
 * some deployments - its transcoder can produce compressed formats (mp3, aac,
 * ogg/vorbis, opus, flac) fine but 500s on any container asked to carry raw
 * PCM (wav, matroska, webm, ts all fail the same way), apparently because a
 * live transcode can't satisfy those containers' need for an up-front known
 * length. Opus/Ogg has no such requirement and streams cleanly.
 *
 * <p>A background thread does the blocking HTTP read, Ogg demuxing, and Opus
 * decoding, filling a bounded queue; {@link #nextFrame()} is called from the
 * audio tick and never blocks for long - it returns silence on underrun
 * rather than stalling playback.
 */
public final class JellyfinPcmSource {

    private static final int SAMPLES_PER_FRAME = 960; // 20ms @ 48kHz
    private static final short[] EOF_MARKER = new short[0];

    private final VoicechatServerApi api;
    private final JellyfinClient client;
    private final String streamUrl;
    private final Logger logger;
    private final String tag;
    private final BlockingQueue<short[]> queue;

    private volatile Thread readerThread;
    private volatile boolean paused = false;
    private volatile boolean stopped = false;
    private volatile float volume = 1.0f;
    private final AtomicBoolean finishedSignaled = new AtomicBoolean(false);

    private Runnable onFinished;

    public JellyfinPcmSource(VoicechatServerApi api, JellyfinClient client, String sessionKey, String itemId,
                              int bufferFrames, Logger logger) {
        this.api = api;
        this.client = client;
        this.streamUrl = client.buildOpusStreamUrl(itemId);
        this.logger = logger;
        this.tag = "[" + sessionKey + "/" + itemId.substring(0, Math.min(8, itemId.length())) + "] ";
        this.queue = new ArrayBlockingQueue<>(Math.max(10, bufferFrames));
    }

    public void setOnFinished(Runnable onFinished) {
        this.onFinished = onFinished;
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0f, Math.min(2f, volume));
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public void start() {
        Thread t = new Thread(this::readLoop, "jellyfinvc-opus-reader");
        t.setDaemon(true);
        this.readerThread = t;
        t.start();
    }

    public void stop() {
        stopped = true;
        Thread t = readerThread;
        if (t != null) {
            t.interrupt();
        }
        queue.clear();
    }

    /**
     * Called every 20ms by the audio player's supplier. Never blocks for long.
     */
    public short[] nextFrame() {
        if (paused) {
            return new short[SAMPLES_PER_FRAME];
        }
        short[] frame;
        try {
            frame = queue.poll(15, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new short[SAMPLES_PER_FRAME];
        }
        if (frame == null) {
            // Buffering underrun - keep the channel alive with silence.
            return new short[SAMPLES_PER_FRAME];
        }
        if (frame.length == 0) {
            if (finishedSignaled.compareAndSet(false, true) && onFinished != null) {
                onFinished.run();
            }
            return new short[SAMPLES_PER_FRAME];
        }
        if (Math.abs(volume - 1f) < 0.001f) {
            return frame;
        }
        short[] scaled = new short[frame.length];
        for (int i = 0; i < frame.length; i++) {
            scaled[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(frame[i] * volume)));
        }
        return scaled;
    }

    private void readLoop() {
        OpusDecoder decoder = null;
        try {
            decoder = api.createDecoder();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(streamUrl))
                    .timeout(Duration.ofMinutes(15))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.httpClient()
                    .send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                logger.warning(tag + "Jellyfin audio stream returned HTTP " + response.statusCode() + " for " + streamUrl);
                return;
            }
            try (InputStream raw = new BufferedInputStream(response.body(), 64 * 1024)) {
                pumpOpus(raw, decoder);
            }
        } catch (IOException e) {
            if (!stopped) {
                logger.log(Level.WARNING, tag + "Jellyfin audio stream failed", e);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            // Anything unexpected (decoder errors, etc) must be visible instead
            // of silently killing this thread and leaving playback mute.
            logger.log(Level.SEVERE, tag + "Unexpected error while streaming/decoding Jellyfin audio", t);
        } finally {
            if (decoder != null) {
                decoder.close();
            }
            offerEof();
        }
    }

    private void pumpOpus(InputStream raw, OpusDecoder decoder) throws IOException, InterruptedException {
        OggOpusReader reader = new OggOpusReader(raw);
        reader.readHeader();
        int channels = Math.max(1, reader.channelCount());
        int skipRemaining = reader.preSkip() * channels;

        short[] pending = new short[0];
        long packetCount = 0;
        while (!stopped) {
            byte[] packet = reader.nextAudioPacket();
            if (packet == null) {
                return;
            }
            packetCount++;
            short[] decoded = decoder.decode(packet);
            if (decoded == null || decoded.length == 0) {
                continue;
            }
            if (channels == 2) {
                decoded = downmixStereoToMono(decoded);
            } else if (channels > 2) {
                decoded = downmixToMono(decoded, channels);
            }

            if (skipRemaining > 0) {
                int skipSamples = channels == 1 ? skipRemaining : skipRemaining / channels;
                if (decoded.length <= skipSamples) {
                    skipRemaining -= decoded.length * (channels == 1 ? 1 : channels);
                    continue;
                }
                decoded = Arrays.copyOfRange(decoded, skipSamples, decoded.length);
                skipRemaining = 0;
            }

            pending = concat(pending, decoded);
            int offset = 0;
            while (pending.length - offset >= SAMPLES_PER_FRAME) {
                short[] frame = Arrays.copyOfRange(pending, offset, offset + SAMPLES_PER_FRAME);
                queue.put(frame);
                offset += SAMPLES_PER_FRAME;
            }
            if (offset > 0) {
                pending = Arrays.copyOfRange(pending, offset, pending.length);
            }
        }
    }

    private static short[] downmixStereoToMono(short[] interleaved) {
        short[] mono = new short[interleaved.length / 2];
        for (int i = 0; i < mono.length; i++) {
            mono[i] = (short) ((interleaved[i * 2] + interleaved[i * 2 + 1]) / 2);
        }
        return mono;
    }

    private static short[] downmixToMono(short[] interleaved, int channels) {
        short[] mono = new short[interleaved.length / channels];
        for (int i = 0; i < mono.length; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                sum += interleaved[i * channels + c];
            }
            mono[i] = (short) (sum / channels);
        }
        return mono;
    }

    private static short[] concat(short[] a, short[] b) {
        if (a.length == 0) {
            return b;
        }
        short[] out = new short[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private void offerEof() {
        try {
            queue.put(EOF_MARKER);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
