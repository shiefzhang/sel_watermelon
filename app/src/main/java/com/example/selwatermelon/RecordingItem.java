package com.example.selwatermelon;

import java.io.File;

final class RecordingItem {
    final String audioId;
    final File wavFile;
    final long createdAt;
    final long durationMs;
    final String modelLabel;
    final double modelConfidence;
    final String modelSource;
    final String featureText;
    final String participantId;
    final String participantName;
    String feedback;
    int weightKg;

    RecordingItem(
            String audioId,
            File wavFile,
            long createdAt,
            long durationMs,
            String modelLabel,
            double modelConfidence,
            String modelSource,
            String featureText,
            String participantId,
            String participantName,
            String feedback,
            int weightKg
    ) {
        this.audioId = audioId;
        this.wavFile = wavFile;
        this.createdAt = createdAt;
        this.durationMs = durationMs;
        this.modelLabel = modelLabel == null ? "" : modelLabel;
        this.modelConfidence = modelConfidence;
        this.modelSource = modelSource == null ? "" : modelSource;
        this.featureText = featureText == null ? "" : featureText;
        this.participantId = participantId;
        this.participantName = participantName;
        this.feedback = feedback == null ? "" : feedback;
        this.weightKg = weightKg;
    }
}
