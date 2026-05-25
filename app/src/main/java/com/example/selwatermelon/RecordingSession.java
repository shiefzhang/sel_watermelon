package com.example.selwatermelon;

import java.io.File;

final class RecordingSession {
    final String audioId;
    final File wavFile;
    final AudioFeatures features;
    final Prediction prediction;
    final long createdAt;
    final long durationMs;
    String participantId = "";
    String participantName = "";
    String userLabel = "";
    int weightKg = 0;

    RecordingSession(String audioId, File wavFile, AudioFeatures features, Prediction prediction, long createdAt, long durationMs) {
        this.audioId = audioId;
        this.wavFile = wavFile;
        this.features = features;
        this.prediction = prediction;
        this.createdAt = createdAt;
        this.durationMs = durationMs;
    }
}
