package com.local.phonequote;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OcrParser {
    private static final Pattern CAPACITY_PATTERN = Pattern.compile(
            "(?i)(?<![0-9])([0-9]{1,4})\\s*(TB|GB|T|G)(?![A-Z])"
    );
    private static final Pattern CONFIGURATION_PATTERN = Pattern.compile(
            "(?i)(?<![0-9])([0-9]{1,2})\\s*(?:GB|G)?\\s*[+＋]\\s*"
                    + "([0-9]{2,4}|1)\\s*(TB|GB|T|G)?(?![A-Z])"
    );
    private static final Pattern NUMBER_PATTERN = Pattern.compile("[0-9]+");
    private static final double MIN_MODEL_SCORE = 0.72d;
    private static final String[] SERIES = {
            "hi畅享", "畅享", "mate", "nova", "pura", "麦芒", "iphone",
            "redmi", "红米", "mix", "civi", "荣耀", "magic"
    };
    private static final String[] SUFFIXES = {"pro+", "pro", "ultra", "max", "se", "青春版"};

    private OcrParser() {}

    public static MatchResult match(String ocrText, List<QuoteItem> items) {
        String safe = ocrText == null ? "" : ocrText;
        String[] rawLines = safe.split("\\r?\\n");
        List<OcrTextLine> lines = new ArrayList<>();
        if (rawLines.length == 0) {
            lines.add(OcrTextLine.plain(safe, 0));
        } else {
            for (int index = 0; index < rawLines.length; index++) {
                if (!rawLines[index].trim().isEmpty()) {
                    lines.add(OcrTextLine.plain(rawLines[index], index));
                }
            }
        }
        return matchLines(lines, items);
    }

    public static MatchResult matchLines(List<OcrTextLine> lines, List<QuoteItem> items) {
        List<OcrTextLine> safeLines = lines == null ? Collections.emptyList() : lines;
        List<QuoteItem> safeItems = items == null ? Collections.emptyList() : items;
        String allText = joinLines(safeLines);
        List<Integer> allCapacities = extractCapacitiesGb(allText);
        BrandSelection brand = selectBrand(safeLines, safeItems);
        if (brand == null) {
            return result(MatchResult.Status.BRAND_NOT_FOUND, null, "", 0d,
                    allCapacities, "", Collections.emptyList());
        }
        List<ModelEvaluation> evaluations = evaluateModels(safeLines, brand.items);
        if (evaluations.isEmpty() || evaluations.get(0).score < MIN_MODEL_SCORE) {
            double score = evaluations.isEmpty() ? 0d : evaluations.get(0).score;
            return result(MatchResult.Status.MODEL_NOT_FOUND, null, "", score,
                    allCapacities, brandOnlySummary(brand.brand), Collections.emptyList());
        }
        ModelEvaluation best = evaluations.get(0);
        if (evaluations.size() > 1 && shouldConfirm(best, evaluations.get(1))) {
            return confirmation(evaluations.subList(0, Math.min(2, evaluations.size())), allCapacities);
        }
        return resolveModel(best, safeLines, brand.items);
    }

    public static MatchResult matchPasses(
            List<List<OcrTextLine>> passes,
            List<QuoteItem> items,
            long elapsedMillis
    ) {
        Map<String, Vote> votes = new LinkedHashMap<>();
        MatchResult fallback = null;
        List<OcrTextLine> combined = new ArrayList<>();
        if (passes != null) {
            for (List<OcrTextLine> pass : passes) {
                if (pass == null || pass.isEmpty()) continue;
                combined.addAll(pass);
                MatchResult current = matchLines(pass, items);
                if (fallback == null || current.confidence > fallback.confidence) fallback = current;
                if (current.status == MatchResult.Status.MODEL_CONFIRMATION_REQUIRED) {
                    for (ModelCandidate candidate : current.candidates) {
                        addVote(votes, candidate.brand, candidate.model, candidate.confidence);
                    }
                } else if (!current.matchedModel.isEmpty()) {
                    String brand = current.item == null ? brandForModel(items, current.matchedModel) : current.item.brand;
                    addVote(votes, brand, current.matchedModel, current.confidence);
                }
            }
        }
        if (votes.isEmpty()) {
            MatchResult value = fallback == null ? matchLines(combined, items) : fallback;
            return value.withElapsed(elapsedMillis);
        }
        List<Vote> ranked = new ArrayList<>(votes.values());
        ranked.sort((left, right) -> {
            int count = Integer.compare(right.count, left.count);
            return count != 0 ? count : Double.compare(right.bestScore, left.bestScore);
        });
        Vote top = ranked.get(0);
        if (ranked.size() > 1 && top.count == ranked.get(1).count) {
            List<ModelCandidate> candidates = new ArrayList<>();
            for (int i = 0; i < Math.min(2, ranked.size()); i++) {
                Vote vote = ranked.get(i);
                candidates.add(new ModelCandidate(vote.brand, vote.model, vote.bestScore));
            }
            return new MatchResult(
                    MatchResult.Status.MODEL_CONFIRMATION_REQUIRED,
                    null,
                    "",
                    top.bestScore,
                    extractCapacitiesGb(joinLines(combined)),
                    "型号识别结果存在关键数字或系列冲突",
                    candidates,
                    elapsedMillis
            );
        }
        return matchModel(combined, top.model, items).withElapsed(elapsedMillis);
    }

    public static MatchResult matchModel(
            List<OcrTextLine> lines,
            String model,
            List<QuoteItem> items
    ) {
        List<QuoteItem> modelItems = new ArrayList<>();
        for (QuoteItem item : items) {
            if (item.model.equals(model)) modelItems.add(item);
        }
        if (modelItems.isEmpty()) {
            return result(MatchResult.Status.MODEL_NOT_FOUND, null, "", 0d,
                    extractCapacitiesGb(joinLines(lines)), "", Collections.emptyList());
        }
        List<ModelEvaluation> evaluations = evaluateModels(lines, modelItems);
        ModelEvaluation evaluation = evaluations.isEmpty()
                ? new ModelEvaluation(modelItems.get(0), MIN_MODEL_SCORE, model.length(), 0)
                : evaluations.get(0);
        return resolveModel(evaluation, lines, modelItems);
    }

    public static List<OcrTextLine> findLikelyModelLines(
            List<OcrTextLine> lines,
            List<QuoteItem> items
    ) {
        List<LineScore> scores = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            OcrTextLine line = lines.get(index);
            double best = 0d;
            for (QuoteItem item : uniqueModels(items)) {
                for (String alias : normalizedNames(item)) {
                    AliasScore score = scoreAlias(line.text, normalize(line.text), alias, item.model);
                    best = Math.max(best, score.score);
                }
            }
            if (best >= 0.55d || containsKnownSeries(normalize(line.text))) {
                scores.add(new LineScore(line, best));
            }
        }
        scores.sort(Comparator.comparingDouble((LineScore value) -> value.score).reversed());
        List<OcrTextLine> result = new ArrayList<>();
        for (int i = 0; i < Math.min(2, scores.size()); i++) result.add(scores.get(i).line);
        return result;
    }

    private static MatchResult resolveModel(
            ModelEvaluation best,
            List<OcrTextLine> lines,
            List<QuoteItem> brandItems
    ) {
        String memoryText = nearbyText(lines, best.lineIndex);
        List<MemoryConfiguration> configurations = extractConfigurations(memoryText);
        List<Integer> capacities = extractCapacitiesGb(memoryText);
        if (capacities.isEmpty()) {
            String allText = joinLines(lines);
            List<MemoryConfiguration> pageConfigurations = extractConfigurations(allText);
            List<Integer> pageCapacities = extractCapacitiesGb(allText);
            if (pageConfigurations.size() == 1) configurations = pageConfigurations;
            if (pageCapacities.size() == 1) capacities = pageCapacities;
        }
        if (capacities.isEmpty()) {
            return result(MatchResult.Status.STORAGE_NOT_FOUND, null, best.item.model,
                    best.score, capacities,
                    recognitionSummary(best.item, configurations, capacities, null),
                    Collections.emptyList());
        }
        QuoteItem priced = null;
        for (QuoteItem item : brandItems) {
            if (sameModel(best.item, item) && matchesMemory(item, configurations, capacities)) {
                priced = item;
                break;
            }
        }
        if (priced == null) {
            return result(MatchResult.Status.PRICE_NOT_FOUND, null, best.item.model,
                    best.score, capacities,
                    recognitionSummary(best.item, configurations, capacities, null),
                    Collections.emptyList());
        }
        return result(MatchResult.Status.MATCHED, priced, priced.model, best.score,
                capacities, recognitionSummary(best.item, configurations, capacities, priced),
                Collections.emptyList());
    }

    private static BrandSelection selectBrand(List<OcrTextLine> lines, List<QuoteItem> items) {
        Map<String, List<QuoteItem>> byBrand = new LinkedHashMap<>();
        for (QuoteItem item : items) byBrand.computeIfAbsent(item.brand, ignored -> new ArrayList<>()).add(item);
        if (byBrand.isEmpty()) return null;
        String normalized = normalize(joinLines(lines));
        List<String> mentioned = new ArrayList<>();
        for (String brand : byBrand.keySet()) if (containsBrand(normalized, brand)) mentioned.add(brand);
        if (mentioned.size() == 1) return new BrandSelection(mentioned.get(0), byBrand.get(mentioned.get(0)));

        List<BrandScore> scores = new ArrayList<>();
        for (Map.Entry<String, List<QuoteItem>> entry : byBrand.entrySet()) {
            if (!mentioned.isEmpty() && !mentioned.contains(entry.getKey())) continue;
            List<ModelEvaluation> models = evaluateModels(lines, entry.getValue());
            if (!models.isEmpty()) scores.add(new BrandScore(entry.getKey(), entry.getValue(), models.get(0)));
        }
        scores.sort((left, right) -> {
            int score = Double.compare(right.model.score, left.model.score);
            return score != 0 ? score : Integer.compare(right.model.specificity, left.model.specificity);
        });
        if (scores.isEmpty() || scores.get(0).model.score < MIN_MODEL_SCORE) return null;
        if (scores.size() > 1) {
            BrandScore first = scores.get(0);
            BrandScore second = scores.get(1);
            if (Math.abs(first.model.score - second.model.score) < 0.05d
                    && first.model.specificity == second.model.specificity) return null;
        }
        BrandScore best = scores.get(0);
        return new BrandSelection(best.brand, best.items);
    }

    private static List<ModelEvaluation> evaluateModels(List<OcrTextLine> lines, List<QuoteItem> items) {
        List<ModelEvaluation> result = new ArrayList<>();
        for (QuoteItem item : uniqueModels(items)) {
            ModelEvaluation best = new ModelEvaluation(item, 0d, 0, 0);
            List<String> aliases = normalizedNames(item);
            for (int index = 0; index < lines.size(); index++) {
                OcrTextLine line = lines.get(index);
                String normalizedLine = normalize(line.text);
                for (String alias : aliases) {
                    AliasScore score = scoreAlias(line.text, normalizedLine, alias, item.model);
                    double contextual = Math.min(1d, score.score + contextBoost(line.text));
                    if (contextual > best.score
                            || (contextual == best.score && score.specificity > best.specificity)) {
                        best = new ModelEvaluation(item, contextual, score.specificity, index);
                    }
                }
            }
            result.add(best);
        }
        result.sort((left, right) -> {
            int score = Double.compare(right.score, left.score);
            return score != 0 ? score : Integer.compare(right.specificity, left.specificity);
        });
        return result;
    }

    private static List<QuoteItem> uniqueModels(List<QuoteItem> items) {
        Map<String, QuoteItem> unique = new LinkedHashMap<>();
        for (QuoteItem item : items) unique.putIfAbsent(item.brand + "\u0000" + item.model, item);
        return new ArrayList<>(unique.values());
    }

    private static AliasScore scoreAlias(String rawLine, String normalizedLine, String alias, String model) {
        if (alias.length() < 3 || !hardCompatible(rawLine, model)) return new AliasScore(0d, 0);
        int specificity = aliasSpecificity(alias);
        if (normalizedLine.contains(alias)) return new AliasScore(1d, specificity);
        return new AliasScore(bestWindowSimilarity(normalizedLine, alias), specificity);
    }

    private static boolean hardCompatible(String observed, String model) {
        String observedNormalized = normalize(observed);
        String modelNormalized = normalize(model);
        String observedSeries = seriesOf(observedNormalized);
        String modelSeries = seriesOf(modelNormalized);
        if (!observedSeries.isEmpty() && !modelSeries.isEmpty() && !observedSeries.equals(modelSeries)) return false;
        if (!observedSeries.isEmpty() && modelSeries.isEmpty()) return false;

        List<String> targetNumbers = modelCoreNumbersFromRaw(model);
        List<String> observedNumbers = modelCoreNumbersFromRaw(observed);
        for (String number : targetNumbers) if (!observedNumbers.contains(number)) return false;

        String observedSuffix = suffixOf(observedNormalized);
        String modelSuffix = suffixOf(modelNormalized);
        if (!observedSuffix.isEmpty() && !observedSuffix.equals(modelSuffix)) return false;
        String observedNetwork = networkOf(observedNormalized);
        String modelNetwork = networkOf(modelNormalized);
        return observedNetwork.isEmpty() || modelNetwork.isEmpty() || observedNetwork.equals(modelNetwork);
    }

    private static boolean shouldConfirm(ModelEvaluation first, ModelEvaluation second) {
        if (first.score < MIN_MODEL_SCORE || second.score < MIN_MODEL_SCORE) return false;
        if (first.item.model.equals(second.item.model)) return false;
        if (first.score == second.score && first.specificity - second.specificity >= 500) return false;
        if (first.score == 1d && first.specificity > second.specificity
                && normalize(first.item.model).contains(normalize(second.item.model))) return false;
        return Math.abs(first.score - second.score) <= 0.035d
                && criticalModelDifference(first.item.model, second.item.model);
    }

    private static boolean criticalModelDifference(String first, String second) {
        return !modelCoreNumbers(normalize(first)).equals(modelCoreNumbers(normalize(second)))
                || !seriesOf(normalize(first)).equals(seriesOf(normalize(second)))
                || !suffixOf(normalize(first)).equals(suffixOf(normalize(second)))
                || !networkOf(normalize(first)).equals(networkOf(normalize(second)));
    }

    private static MatchResult confirmation(List<ModelEvaluation> values, List<Integer> capacities) {
        List<ModelCandidate> candidates = new ArrayList<>();
        for (ModelEvaluation value : values) {
            candidates.add(new ModelCandidate(value.item.brand, value.item.model, value.score));
        }
        return new MatchResult(MatchResult.Status.MODEL_CONFIRMATION_REQUIRED, null, "",
                values.get(0).score, capacities, "请确认识别到的手机型号", candidates, 0L);
    }

    private static MatchResult result(
            MatchResult.Status status,
            QuoteItem item,
            String model,
            double confidence,
            List<Integer> capacities,
            String summary,
            List<ModelCandidate> candidates
    ) {
        return new MatchResult(status, item, model, confidence, capacities, summary, candidates, 0L);
    }

    private static String nearbyText(List<OcrTextLine> lines, int modelLineIndex) {
        StringBuilder value = new StringBuilder();
        int from = Math.max(0, modelLineIndex - 2);
        int to = Math.min(lines.size() - 1, modelLineIndex + 3);
        for (int index = from; index <= to; index++) {
            if (value.length() > 0) value.append('\n');
            value.append(lines.get(index).text);
        }
        return value.toString();
    }

    private static String joinLines(List<OcrTextLine> lines) {
        StringBuilder value = new StringBuilder();
        for (OcrTextLine line : lines) {
            if (line == null || line.text.isEmpty()) continue;
            if (value.length() > 0) value.append('\n');
            value.append(line.text);
        }
        return value.toString();
    }

    private static double contextBoost(String line) {
        String normalized = normalize(line);
        double result = 0d;
        if (normalized.contains("当前型号") || normalized.contains("手机型号")
                || normalized.contains("商品名称") || normalized.contains("机型")) result += 0.08d;
        if (normalized.contains("推荐") || normalized.contains("猜你喜欢")
                || normalized.contains("相关商品")) result -= 0.10d;
        return result;
    }

    private static void addVote(Map<String, Vote> votes, String brand, String model, double score) {
        String key = brand + "\u0000" + model;
        Vote vote = votes.get(key);
        if (vote == null) votes.put(key, new Vote(brand, model, score));
        else {
            vote.count++;
            vote.bestScore = Math.max(vote.bestScore, score);
        }
    }

    private static String brandForModel(List<QuoteItem> items, String model) {
        String brand = "";
        for (QuoteItem item : items) {
            if (!item.model.equals(model)) continue;
            if (!brand.isEmpty() && !brand.equals(item.brand)) return "";
            brand = item.brand;
        }
        return brand;
    }

    private static boolean containsBrand(String normalizedText, String brand) {
        String normalizedBrand = normalize(brand);
        if (!normalizedBrand.isEmpty() && normalizedText.contains(normalizedBrand)) return true;
        if ("小米".equals(normalizedBrand)) return normalizedText.contains("xiaomi");
        if ("华为".equals(normalizedBrand)) return normalizedText.contains("huawei");
        return false;
    }

    private static String seriesOf(String normalized) {
        for (String series : SERIES) if (normalized.contains(normalize(series))) return normalize(series);
        return "";
    }

    private static boolean containsKnownSeries(String normalized) {
        return !seriesOf(normalized).isEmpty();
    }

    private static String suffixOf(String normalized) {
        for (String suffix : SUFFIXES) if (normalized.contains(normalize(suffix))) return normalize(suffix);
        return "";
    }

    private static String networkOf(String normalized) {
        if (normalized.contains("4g")) return "4g";
        if (normalized.contains("5g")) return "5g";
        return "";
    }

    private static List<String> modelCoreNumbers(String normalizedModel) {
        String value = normalizedModel.replaceAll("(?:4g|5g)", "");
        return numbers(value);
    }

    private static List<String> modelCoreNumbersFromRaw(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[45]\\s*g", " ");
        return numbers(normalized);
    }

    private static List<String> numbers(String value) {
        List<String> result = new ArrayList<>();
        Matcher matcher = NUMBER_PATTERN.matcher(value);
        while (matcher.find()) result.add(trimNumber(matcher.group()));
        return result;
    }

    private static String trimNumber(String value) {
        try {
            return Integer.toString(Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private static boolean sameModel(QuoteItem left, QuoteItem right) {
        return left.brand.equals(right.brand) && left.model.equals(right.model);
    }

    private static String recognitionSummary(
            QuoteItem modelItem,
            List<MemoryConfiguration> configurations,
            List<Integer> capacities,
            QuoteItem pricedItem
    ) {
        StringBuilder summary = new StringBuilder("品牌：").append(modelItem.brand)
                .append("  型号：").append(modelItem.model);
        String memory = "";
        if (pricedItem != null) memory = pricedItem.configuration.isEmpty() ? pricedItem.storage : pricedItem.configuration;
        else if (!configurations.isEmpty()) {
            MemoryConfiguration configuration = configurations.get(0);
            memory = configuration.ramGb + "+" + formatStorage(configuration.storageGb);
        } else if (!capacities.isEmpty()) memory = formatStorage(capacities.get(0));
        if (!memory.isEmpty()) summary.append("  内存：").append(memory);
        return summary.toString();
    }

    private static String brandOnlySummary(String brand) {
        return brand.isEmpty() ? "" : "品牌：" + brand;
    }

    private static String formatStorage(int storageGb) {
        return storageGb >= 1024 && storageGb % 1024 == 0 ? (storageGb / 1024) + "TB" : Integer.toString(storageGb);
    }

    public static List<Integer> extractCapacitiesGb(String text) {
        Set<Integer> result = new LinkedHashSet<>();
        String safeText = text == null ? "" : text;
        for (MemoryConfiguration configuration : extractConfigurations(safeText)) result.add(configuration.storageGb);
        Matcher matcher = CAPACITY_PATTERN.matcher(Normalizer.normalize(safeText, Normalizer.Form.NFKC));
        while (matcher.find()) {
            int value;
            try { value = Integer.parseInt(matcher.group(1)); }
            catch (NumberFormatException ignored) { continue; }
            String unit = matcher.group(2).toUpperCase(Locale.ROOT);
            int gb = unit.startsWith("T") ? value * 1024 : value;
            if (gb >= 32 && gb <= 4096) result.add(gb);
        }
        return new ArrayList<>(result);
    }

    public static int capacityToGb(String storage) {
        Matcher matcher = CAPACITY_PATTERN.matcher(storage == null ? "" : storage.trim());
        if (!matcher.find()) return -1;
        int value = Integer.parseInt(matcher.group(1));
        return matcher.group(2).toUpperCase(Locale.ROOT).startsWith("T") ? value * 1024 : value;
    }

    public static int configurationRamGb(String configuration) {
        List<MemoryConfiguration> values = extractConfigurations(configuration);
        return values.isEmpty() ? -1 : values.get(0).ramGb;
    }

    public static int configurationStorageGb(String configuration) {
        List<MemoryConfiguration> values = extractConfigurations(configuration);
        return values.isEmpty() ? -1 : values.get(0).storageGb;
    }

    private static List<MemoryConfiguration> extractConfigurations(String text) {
        List<MemoryConfiguration> result = new ArrayList<>();
        String safe = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC);
        Matcher matcher = CONFIGURATION_PATTERN.matcher(safe);
        while (matcher.find()) {
            int ram;
            int storage;
            try { ram = Integer.parseInt(matcher.group(1)); storage = Integer.parseInt(matcher.group(2)); }
            catch (NumberFormatException ignored) { continue; }
            String unit = matcher.group(3);
            if (unit != null && unit.toUpperCase(Locale.ROOT).startsWith("T")) storage *= 1024;
            if (ram >= 2 && ram <= 32 && storage >= 32 && storage <= 4096) {
                MemoryConfiguration value = new MemoryConfiguration(ram, storage);
                if (!result.contains(value)) result.add(value);
            }
        }
        return result;
    }

    private static boolean matchesMemory(
            QuoteItem item,
            List<MemoryConfiguration> configurations,
            List<Integer> capacities
    ) {
        if (!configurations.isEmpty() && item.ramGb > 0) {
            for (MemoryConfiguration configuration : configurations) {
                if (configuration.ramGb == item.ramGb && configuration.storageGb == item.storageGb) return true;
            }
            return false;
        }
        return capacities.contains(item.storageGb);
    }

    static String normalize(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[^a-z0-9\\p{IsHan}+＋]", "").replace('＋', '+');
    }

    private static List<String> normalizedNames(QuoteItem item) {
        Set<String> names = new LinkedHashSet<>();
        for (String alias : item.aliases) addNameVariants(names, alias, item.brand);
        addNameVariants(names, item.model, item.brand);
        addNameVariants(names, item.brand + item.model, item.brand);
        return new ArrayList<>(names);
    }

    private static void addNameVariants(Set<String> names, String name, String brand) {
        String normalized = normalize(name);
        if (normalized.isEmpty()) return;
        names.add(normalized);
        String normalizedBrand = normalize(brand);
        if (!normalizedBrand.isEmpty() && normalized.startsWith(normalizedBrand)) {
            String withoutBrand = normalized.substring(normalizedBrand.length());
            if (withoutBrand.length() >= 3) names.add(withoutBrand);
        }
        String withoutNetwork = normalized.replaceFirst("(?:4g|5g)$", "");
        if (withoutNetwork.length() >= 3) names.add(withoutNetwork);
    }

    private static int aliasSpecificity(String alias) {
        boolean descriptive = alias.matches(".*\\p{IsHan}.*") || alias.matches(".*[a-z]{2,}.*");
        return (descriptive ? 1000 : 0) + alias.length();
    }

    private static double bestWindowSimilarity(String text, String target) {
        if (text.isEmpty() || target.isEmpty()) return 0d;
        int minLength = Math.max(1, target.length() - 2);
        int maxLength = Math.min(text.length(), target.length() + 2);
        double best = 0d;
        for (int length = minLength; length <= maxLength; length++) {
            for (int start = 0; start + length <= text.length(); start++) {
                String window = text.substring(start, start + length);
                int distance = levenshtein(window, target);
                best = Math.max(best, 1d - ((double) distance / Math.max(window.length(), target.length())));
            }
        }
        return best;
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous; previous = current; current = swap;
        }
        return previous[right.length()];
    }

    private static final class AliasScore {
        final double score;
        final int specificity;
        AliasScore(double score, int specificity) { this.score = score; this.specificity = specificity; }
    }

    private static final class ModelEvaluation {
        final QuoteItem item;
        final double score;
        final int specificity;
        final int lineIndex;
        ModelEvaluation(QuoteItem item, double score, int specificity, int lineIndex) {
            this.item = item; this.score = score; this.specificity = specificity; this.lineIndex = lineIndex;
        }
    }

    private static final class BrandSelection {
        final String brand;
        final List<QuoteItem> items;
        BrandSelection(String brand, List<QuoteItem> items) { this.brand = brand; this.items = items; }
    }

    private static final class BrandScore {
        final String brand;
        final List<QuoteItem> items;
        final ModelEvaluation model;
        BrandScore(String brand, List<QuoteItem> items, ModelEvaluation model) {
            this.brand = brand; this.items = items; this.model = model;
        }
    }

    private static final class Vote {
        final String brand;
        final String model;
        int count = 1;
        double bestScore;
        Vote(String brand, String model, double bestScore) {
            this.brand = brand; this.model = model; this.bestScore = bestScore;
        }
    }

    private static final class LineScore {
        final OcrTextLine line;
        final double score;
        LineScore(OcrTextLine line, double score) { this.line = line; this.score = score; }
    }

    private static final class MemoryConfiguration {
        final int ramGb;
        final int storageGb;
        MemoryConfiguration(int ramGb, int storageGb) { this.ramGb = ramGb; this.storageGb = storageGb; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof MemoryConfiguration)) return false;
            MemoryConfiguration value = (MemoryConfiguration) other;
            return ramGb == value.ramGb && storageGb == value.storageGb;
        }
        @Override public int hashCode() { return 31 * ramGb + storageGb; }
    }
}
