package com.local.phonequote;

public final class ModelCandidate {
    public final String brand;
    public final String model;
    public final double confidence;

    ModelCandidate(String brand, String model, double confidence) {
        this.brand = brand;
        this.model = model;
        this.confidence = confidence;
    }
}
