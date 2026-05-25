package com.example.selwatermelon;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Arrays;

public final class WaveformView extends View {
    private static final int WAVEFORM_SECONDS = 5;
    private static final int BAR_COUNT = 30;
    private static final int SAMPLES_PER_BAR = WatermelonRecorder.SAMPLE_RATE * WAVEFORM_SECONDS / BAR_COUNT;

    private final float[] levels = new float[BAR_COUNT];
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long pendingSquareSum;
    private int pendingSamples;

    public WaveformView(Context context) {
        super(context);
        init();
    }

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        barPaint.setColor(0xFF1B7F45);
        gridPaint.setColor(0xFFE1E9E3);
        gridPaint.setStrokeWidth(1f);
    }

    void reset() {
        Arrays.fill(levels, 0f);
        pendingSquareSum = 0;
        pendingSamples = 0;
        invalidate();
    }

    void addSamples(short[] samples) {
        if (samples.length == 0) {
            return;
        }
        for (short sample : samples) {
            pendingSquareSum += (long) sample * sample;
            pendingSamples++;
            if (pendingSamples >= SAMPLES_PER_BAR) {
                addLevel(pendingSquareSum, pendingSamples);
                pendingSquareSum = 0;
                pendingSamples = 0;
            }
        }
    }

    private void addLevel(long squareSum, int sampleCount) {
        float rms = (float) Math.sqrt(squareSum / (double) sampleCount) / Short.MAX_VALUE;
        float displayLevel = Math.min(1f, rms * 8f);
        System.arraycopy(levels, 1, levels, 0, levels.length - 1);
        levels[levels.length - 1] = displayLevel;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        float center = height / 2f;
        canvas.drawLine(0, center, width, center, gridPaint);

        float slot = width / (float) BAR_COUNT;
        float barWidth = Math.max(3f, slot * 0.55f);
        for (int i = 0; i < BAR_COUNT; i++) {
            float level = Math.max(0.04f, levels[i]);
            float half = level * height * 0.46f;
            float left = i * slot + (slot - barWidth) / 2f;
            canvas.drawRoundRect(left, center - half, left + barWidth, center + half, barWidth, barWidth, barPaint);
        }
    }
}
