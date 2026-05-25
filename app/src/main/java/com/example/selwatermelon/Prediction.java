package com.example.selwatermelon;

final class Prediction {
    final String label;
    final double confidence;
    final String text;
    final String modelSource;

    Prediction(String label, double confidence, String text, String modelSource) {
        this.label = label;
        this.confidence = confidence;
        this.text = text;
        this.modelSource = modelSource;
    }
}
