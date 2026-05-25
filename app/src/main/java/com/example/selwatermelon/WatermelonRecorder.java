package com.example.selwatermelon;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class WatermelonRecorder {
    static final int SAMPLE_RATE = 16000;
    static final int MAX_SECONDS = 3;

    interface Callback {
        void onFinished(short[] samples);

        void onAudioFrame(short[] samples);

        void onError(Exception error);
    }

    private volatile boolean recording;
    private AudioRecord audioRecord;
    private Thread thread;

    boolean isRecording() {
        return recording;
    }

    void start(Callback callback) throws SecurityException {
        if (recording) {
            return;
        }
        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        int bufferSize = Math.max(minBuffer, SAMPLE_RATE / 2);
        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
        );
        recording = true;
        thread = new Thread(() -> runRecording(bufferSize, callback), "watermelon-recorder");
        thread.start();
    }

    void stop() {
        recording = false;
    }

    private void runRecording(int bufferSize, Callback callback) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[bufferSize];
        try {
            audioRecord.startRecording();
            long deadline = System.currentTimeMillis() + MAX_SECONDS * 1000L;
            while (recording && System.currentTimeMillis() < deadline) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    bytes.write(buffer, 0, read);
                    callback.onAudioFrame(toShorts(buffer, read));
                }
            }
            audioRecord.stop();
            callback.onFinished(toShorts(bytes.toByteArray()));
        } catch (Exception e) {
            callback.onError(e);
        } finally {
            recording = false;
            if (audioRecord != null) {
                audioRecord.release();
                audioRecord = null;
            }
        }
    }

    private static short[] toShorts(byte[] data) {
        return toShorts(data, data.length);
    }

    private static short[] toShorts(byte[] data, int length) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        short[] out = new short[length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = buffer.getShort();
        }
        return out;
    }
}
