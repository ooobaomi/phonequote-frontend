package com.local.phonequote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class QuoteItem {
    public final String brand;
    public final String model;
    public final List<String> aliases;
    public final String configuration;
    public final int ramGb;
    public final String storage;
    public final int storageGb;
    public final List<GradePrice> grades;

    public QuoteItem(
            String brand,
            String model,
            List<String> aliases,
            String storage,
            List<GradePrice> grades
    ) {
        this(brand, model, aliases, "", storage, grades);
    }

    public QuoteItem(
            String brand,
            String model,
            List<String> aliases,
            String configuration,
            String storage,
            List<GradePrice> grades
    ) {
        this.brand = brand;
        this.model = model;
        this.aliases = Collections.unmodifiableList(new ArrayList<>(aliases));
        this.configuration = configuration;
        this.ramGb = OcrParser.configurationRamGb(configuration);
        this.storage = storage;
        this.storageGb = OcrParser.capacityToGb(storage);
        this.grades = Collections.unmodifiableList(new ArrayList<>(grades));
    }

    public static final class GradePrice {
        public final String name;
        public final int price;

        public GradePrice(String name, int price) {
            this.name = name;
            this.price = price;
        }
    }
}
