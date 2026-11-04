package com.local.phonequote;

import java.util.Collections;
import java.util.List;

public final class MatchResult {
    public enum Status {
        MATCHED,
        MODEL_CONFIRMATION_REQUIRED,
        BRAND_NOT_FOUND,
        MODEL_NOT_FOUND,
        STORAGE_NOT_FOUND,
        PRICE_NOT_FOUND
    }

    public final Status status;
    public final QuoteItem item;
    public final String matchedModel;
    public final double confidence;
    public final List<Integer> detectedCapacitiesGb;
    public final String recognizedSummary;
    public final List<ModelCandidate> candidates;
    public final long elapsedMillis;

    MatchResult(
            Status status,
            QuoteItem item,
            String matchedModel,
            double confidence,
            List<Integer> detectedCapacitiesGb,
            String recognizedSummary
    ) {
        this(status, item, matchedModel, confidence, detectedCapacitiesGb,
                recognizedSummary, Collections.emptyList(), 0L);
    }

    MatchResult(
            Status status,
            QuoteItem item,
            String matchedModel,
            double confidence,
            List<Integer> detectedCapacitiesGb,
            String recognizedSummary,
            List<ModelCandidate> candidates,
            long elapsedMillis
    ) {
        this.status = status;
        this.item = item;
        this.matchedModel = matchedModel;
        this.confidence = confidence;
        this.detectedCapacitiesGb = Collections.unmodifiableList(detectedCapacitiesGb);
        this.recognizedSummary = recognizedSummary;
        this.candidates = Collections.unmodifiableList(candidates);
        this.elapsedMillis = elapsedMillis;
    }

    MatchResult withElapsed(long elapsed) {
        return new MatchResult(status, item, matchedModel, confidence, detectedCapacitiesGb,
                recognizedSummary, candidates, elapsed);
    }
}
