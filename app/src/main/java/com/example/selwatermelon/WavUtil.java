package com.example.selwatermelon;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class WavUtil {
    private WavUtil() {
    }

    static void writeMono16(File file, short[] pcm, int sampleRate) throws IOException {
        int dataSize = pcm.length * 2;
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(header(dataSize, sampleRate));
            ByteBuffer buffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN);
            for (short sample : pcm) {
                buffer.putShort(sample);
            }
            out.write(buffer.array());
        }
    }

    private static byte[] header(int dataSize, int sampleRate) {
        int byteRate = sampleRate * 2;
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R', 'I', 'F', 'F'});
        b.putInt(36 + dataSize);
        b.put(new byte[]{'W', 'A', 'V', 'E'});
        b.put(new byte[]{'f', 'm', 't', ' '});
        b.putInt(16);
        b.putShort((short) 1);
        b.putShort((short) 1);
        b.putInt(sampleRate);
        b.putInt(byteRate);
        b.putShort((short) 2);
        b.putShort((short) 16);
        b.put(new byte[]{'d', 'a', 't', 'a'});
        b.putInt(dataSize);
        return b.array();
    }
}
