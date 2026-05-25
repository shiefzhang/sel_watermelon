package com.example.selwatermelon;

import java.util.Locale;

final class AudioFeatures {
    private static final int MFCC_COUNT = 13;
    private static final int MEL_BINS = 26;
    private static final int FRAME_SIZE = 400;
    private static final int HOP_SIZE = 160;
    private static final int FFT_SIZE = 512;
    private static final double EPS = 1e-9;

    static final String[] NAMES = buildNames();

    final double rms;
    final double peak;
    final double zcr;
    final double centroid;
    final double lowRatio;
    final double midRatio;
    final double highRatio;
    final double decay;
    private final double[] mfccMeans;
    private final double[] mfccStds;

    AudioFeatures(double rms, double peak, double zcr, double centroid,
                  double lowRatio, double midRatio, double highRatio, double decay,
                  double[] mfccMeans, double[] mfccStds) {
        this.rms = rms;
        this.peak = peak;
        this.zcr = zcr;
        this.centroid = centroid;
        this.lowRatio = lowRatio;
        this.midRatio = midRatio;
        this.highRatio = highRatio;
        this.decay = decay;
        this.mfccMeans = mfccMeans;
        this.mfccStds = mfccStds;
    }

    static AudioFeatures fromPcm(short[] pcm, int sampleRate) {
        if (pcm.length == 0) {
            return new AudioFeatures(0, 0, 0, 0, 0, 0, 0, 0,
                    new double[MFCC_COUNT], new double[MFCC_COUNT]);
        }
        short[] focused = focusOnTap(pcm, sampleRate);
        double sumSq = 0;
        double peak = 0;
        int crossings = 0;
        int prevSign = sign(focused[0]);
        for (short s : focused) {
            double v = s / 32768.0;
            sumSq += v * v;
            peak = Math.max(peak, Math.abs(v));
            int currentSign = sign(s);
            if (currentSign != 0 && prevSign != 0 && currentSign != prevSign) {
                crossings++;
            }
            if (currentSign != 0) {
                prevSign = currentSign;
            }
        }

        int n = Math.min(2048, focused.length);
        int offset = Math.max(0, (focused.length - n) / 2);
        double low = 0;
        double mid = 0;
        double high = 0;
        double weighted = 0;
        double total = 0;
        for (int hz = 120; hz <= 3200; hz += 80) {
            double energy = goertzel(focused, offset, n, sampleRate, hz);
            total += energy;
            weighted += energy * hz;
            if (hz < 500) {
                low += energy;
            } else if (hz < 1400) {
                mid += energy;
            } else {
                high += energy;
            }
        }

        double centroid = total > 0 ? weighted / total : 0;
        double safeTotal = total > 0 ? total : 1;
        double first = windowEnergy(focused, 0, Math.max(1, focused.length / 4));
        double last = windowEnergy(focused, Math.max(0, focused.length * 3 / 4), focused.length);
        double decay = first > 0 ? Math.max(0, Math.min(1.5, 1.0 - last / first)) : 0;
        double[][] mfccStats = mfccStats(focused, sampleRate);
        return new AudioFeatures(
                Math.sqrt(sumSq / focused.length),
                peak,
                crossings / (double) focused.length,
                centroid,
                low / safeTotal,
                mid / safeTotal,
                high / safeTotal,
                decay,
                mfccStats[0],
                mfccStats[1]
        );
    }

    double[] toArray() {
        double[] out = new double[NAMES.length];
        out[0] = rms;
        out[1] = peak;
        out[2] = zcr;
        out[3] = centroid;
        out[4] = lowRatio;
        out[5] = midRatio;
        out[6] = highRatio;
        out[7] = decay;
        System.arraycopy(mfccMeans, 0, out, 8, MFCC_COUNT);
        System.arraycopy(mfccStds, 0, out, 8 + MFCC_COUNT, MFCC_COUNT);
        return out;
    }

    String shortText() {
        return String.format(Locale.US,
                "RMS %.3f  Peak %.2f\nZCR %.3f  Centroid %.0f Hz\nLow %.0f%%  Mid %.0f%%  High %.0f%%  Decay %.2f\nMFCC1 %.2f  MFCC2 %.2f  MFCC3 %.2f",
                rms, peak, zcr, centroid, lowRatio * 100, midRatio * 100, highRatio * 100, decay,
                mfccMeans[0], mfccMeans[1], mfccMeans[2]);
    }

    private static String[] buildNames() {
        String[] names = new String[8 + MFCC_COUNT * 2];
        String[] base = {"rms", "peak", "zcr", "centroid", "low_ratio", "mid_ratio", "high_ratio", "decay"};
        System.arraycopy(base, 0, names, 0, base.length);
        for (int i = 0; i < MFCC_COUNT; i++) {
            names[8 + i] = "mfcc_" + (i + 1) + "_mean";
            names[8 + MFCC_COUNT + i] = "mfcc_" + (i + 1) + "_std";
        }
        return names;
    }

    private static short[] focusOnTap(short[] pcm, int sampleRate) {
        int peakIndex = 0;
        int peakValue = 0;
        for (int i = 0; i < pcm.length; i++) {
            int value = Math.abs((int) pcm[i]);
            if (value > peakValue) {
                peakValue = value;
                peakIndex = i;
            }
        }
        int before = Math.max(1, sampleRate / 20);
        int length = Math.min(pcm.length, sampleRate);
        int start = Math.max(0, peakIndex - before);
        if (start + length > pcm.length) {
            start = Math.max(0, pcm.length - length);
        }
        short[] out = new short[length];
        System.arraycopy(pcm, start, out, 0, length);
        return out;
    }

    private static double[][] mfccStats(short[] pcm, int sampleRate) {
        double[][] filters = melFilterBank(sampleRate);
        double[] sums = new double[MFCC_COUNT];
        double[] squares = new double[MFCC_COUNT];
        int frames = 0;
        for (int start = 0; start + FRAME_SIZE <= pcm.length; start += HOP_SIZE) {
            double[] power = powerSpectrum(pcm, start);
            double[] logMel = new double[MEL_BINS];
            for (int m = 0; m < MEL_BINS; m++) {
                double energy = 0;
                for (int k = 0; k < power.length; k++) {
                    energy += power[k] * filters[m][k];
                }
                logMel[m] = Math.log(Math.max(energy, EPS));
            }
            for (int c = 0; c < MFCC_COUNT; c++) {
                double value = dct(logMel, c);
                sums[c] += value;
                squares[c] += value * value;
            }
            frames++;
        }
        double[] means = new double[MFCC_COUNT];
        double[] stds = new double[MFCC_COUNT];
        if (frames == 0) {
            return new double[][]{means, stds};
        }
        for (int i = 0; i < MFCC_COUNT; i++) {
            means[i] = sums[i] / frames;
            double variance = squares[i] / frames - means[i] * means[i];
            stds[i] = Math.sqrt(Math.max(0, variance));
        }
        return new double[][]{means, stds};
    }

    private static double[] powerSpectrum(short[] pcm, int start) {
        double[] power = new double[FFT_SIZE / 2 + 1];
        for (int k = 0; k < power.length; k++) {
            double real = 0;
            double imag = 0;
            for (int n = 0; n < FRAME_SIZE; n++) {
                double window = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * n / Math.max(1, FRAME_SIZE - 1));
                double sample = pcm[start + n] / 32768.0 * window;
                double angle = -2.0 * Math.PI * k * n / FFT_SIZE;
                real += sample * Math.cos(angle);
                imag += sample * Math.sin(angle);
            }
            power[k] = real * real + imag * imag;
        }
        return power;
    }

    private static double[][] melFilterBank(int sampleRate) {
        int bins = FFT_SIZE / 2 + 1;
        double[][] filters = new double[MEL_BINS][bins];
        double minMel = hzToMel(20);
        double maxMel = hzToMel(sampleRate / 2.0);
        double[] hzPoints = new double[MEL_BINS + 2];
        for (int i = 0; i < hzPoints.length; i++) {
            double mel = minMel + (maxMel - minMel) * i / (hzPoints.length - 1);
            hzPoints[i] = melToHz(mel);
        }
        int[] binPoints = new int[hzPoints.length];
        for (int i = 0; i < hzPoints.length; i++) {
            binPoints[i] = Math.max(0, Math.min(bins - 1, (int) Math.floor((FFT_SIZE + 1) * hzPoints[i] / sampleRate)));
        }
        for (int m = 1; m <= MEL_BINS; m++) {
            int left = binPoints[m - 1];
            int center = Math.max(left + 1, binPoints[m]);
            int right = Math.max(center + 1, binPoints[m + 1]);
            for (int k = left; k < center && k < bins; k++) {
                filters[m - 1][k] = (k - left) / (double) Math.max(1, center - left);
            }
            for (int k = center; k < right && k < bins; k++) {
                filters[m - 1][k] = (right - k) / (double) Math.max(1, right - center);
            }
        }
        return filters;
    }

    private static double dct(double[] values, int coefficient) {
        double sum = 0;
        for (int n = 0; n < values.length; n++) {
            sum += values[n] * Math.cos(Math.PI * coefficient * (n + 0.5) / values.length);
        }
        return sum;
    }

    private static double hzToMel(double hz) {
        return 2595.0 * Math.log10(1.0 + hz / 700.0);
    }

    private static double melToHz(double mel) {
        return 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0);
    }

    private static int sign(short value) {
        if (value > 0) return 1;
        if (value < 0) return -1;
        return 0;
    }

    private static double windowEnergy(short[] pcm, int start, int end) {
        double sum = 0;
        int count = 0;
        for (int i = start; i < end && i < pcm.length; i++) {
            double v = pcm[i] / 32768.0;
            sum += v * v;
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    private static double goertzel(short[] samples, int offset, int length, int sampleRate, double targetHz) {
        double omega = 2.0 * Math.PI * targetHz / sampleRate;
        double coeff = 2.0 * Math.cos(omega);
        double q0;
        double q1 = 0;
        double q2 = 0;
        for (int i = 0; i < length && offset + i < samples.length; i++) {
            double w = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / Math.max(1, length - 1));
            q0 = coeff * q1 - q2 + (samples[offset + i] / 32768.0) * w;
            q2 = q1;
            q1 = q0;
        }
        return q1 * q1 + q2 * q2 - coeff * q1 * q2;
    }
}
