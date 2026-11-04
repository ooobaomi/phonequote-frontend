package com.local.phonequote;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class OcrParserTest {
    private static QuoteItem item(String model, String alias, String storage) {
        return new QuoteItem(
                "Apple",
                model,
                Collections.singletonList(alias),
                storage,
                Collections.singletonList(new QuoteItem.GradePrice("靓机", 1000))
        );
    }

    private static QuoteItem configuredItem(String model, String alias, String configuration, String storage) {
        return configuredItem("小米", model, alias, configuration, storage);
    }

    private static QuoteItem configuredItem(
            String brand,
            String model,
            String alias,
            String configuration,
            String storage
    ) {
        return new QuoteItem(
                brand,
                model,
                Collections.singletonList(alias),
                configuration,
                storage,
                Collections.singletonList(new QuoteItem.GradePrice("靓机", 1000))
        );
    }

    private static QuoteItem pricedItem(String model, String configuration, int price) {
        return new QuoteItem(
                "小米",
                model,
                Collections.singletonList(model.replace(" ", "")),
                configuration,
                configuration.endsWith("512") ? "512GB" : "256GB",
                Collections.singletonList(new QuoteItem.GradePrice("靓机", price))
        );
    }

    @Test
    public void extractsStorageAndIgnoresCommonRamSize() {
        List<Integer> values = OcrParser.extractCapacitiesGb("运行内存 12GB 机身容量 256 GB");
        assertEquals(Collections.singletonList(256), values);
    }

    @Test
    public void convertsTerabytesToGigabytes() {
        assertEquals(1024, OcrParser.capacityToGb("1TB"));
        assertEquals(Collections.singletonList(1024), OcrParser.extractCapacitiesGb("存储：1 T"));
    }

    @Test
    public void choosesMostSpecificExactModel() {
        List<QuoteItem> items = Arrays.asList(
                item("iPhone 15 Pro", "苹果15 Pro", "256GB"),
                item("iPhone 15 Pro Max", "苹果15 Pro Max", "256GB")
        );
        MatchResult result = OcrParser.match("苹果15 Pro Max 国行 256GB", items);
        assertEquals(MatchResult.Status.MATCHED, result.status);
        assertEquals("iPhone 15 Pro Max", result.item.model);
    }

    @Test
    public void toleratesOneCharacterOcrError() {
        QuoteItem phone = item("iPhone 15 Pro", "iPhone15Pro", "256GB");
        MatchResult result = OcrParser.match("iPh0ne 15 Pro\n256G", Collections.singletonList(phone));
        assertEquals(MatchResult.Status.MATCHED, result.status);
        assertTrue(result.confidence >= 0.85d);
    }

    @Test
    public void reportsMissingStorageWithoutGuessingPrice() {
        QuoteItem phone = item("iPhone 15 Pro", "iPhone15Pro", "256GB");
        MatchResult result = OcrParser.match("iPhone 15 Pro 颜色 黑色", Collections.singletonList(phone));
        assertEquals(MatchResult.Status.STORAGE_NOT_FOUND, result.status);
    }

    @Test
    public void reportsCapacityAbsentFromLocalPrices() {
        QuoteItem phone = item("iPhone 15 Pro", "iPhone15Pro", "256GB");
        MatchResult result = OcrParser.match("iPhone 15 Pro 512GB", Collections.singletonList(phone));
        assertEquals(MatchResult.Status.PRICE_NOT_FOUND, result.status);
    }

    @Test
    public void extractsStorageFromRamPlusStorageShorthand() {
        assertEquals(Collections.singletonList(512), OcrParser.extractCapacitiesGb("内存配置 12+512"));
        assertEquals(16, OcrParser.configurationRamGb("16GB+1TB"));
        assertEquals(1024, OcrParser.configurationStorageGb("16GB+1TB"));
    }

    @Test
    public void choosesExactRamAndStorageConfiguration() {
        List<QuoteItem> items = Arrays.asList(
                configuredItem("小米 17 Ultra 5G", "小米17Ultra", "12+512", "512GB"),
                configuredItem("小米 17 Ultra 5G", "小米17Ultra", "16+512", "512GB")
        );
        MatchResult result = OcrParser.match("小米17 Ultra 5G 16+512", items);
        assertEquals(MatchResult.Status.MATCHED, result.status);
        assertEquals("16+512", result.item.configuration);
    }

    @Test
    public void neverUsesMemoryMatchedPriceFromAnotherModel() {
        List<QuoteItem> items = Arrays.asList(
                configuredItem("小米 14 5G", "小米145G", "12+256", "256GB"),
                configuredItem("小米 12 5G", "小米125G", "16+512", "512GB")
        );

        MatchResult result = OcrParser.match(
                "当前型号 小米14 5G\n页面推荐 小米12 5G\n内存配置 16+512",
                items
        );

        assertEquals(MatchResult.Status.PRICE_NOT_FOUND, result.status);
        assertEquals("小米 14 5G", result.matchedModel);
    }

    @Test
    public void choosesMemoryOnlyWithinRecognizedModel() {
        List<QuoteItem> items = Arrays.asList(
                configuredItem("小米 14 5G", "小米145G", "12+256", "256GB"),
                configuredItem("小米 14 5G", "小米145G", "16+512", "512GB"),
                configuredItem("小米 12 5G", "小米125G", "16+512", "512GB")
        );

        MatchResult result = OcrParser.match("小米14 5G 16+512", items);

        assertEquals(MatchResult.Status.MATCHED, result.status);
        assertEquals("小米 14 5G", result.item.model);
        assertEquals("16+512", result.item.configuration);
    }

    @Test
    public void prefersExplicitModelOverRamAndNetworkThatLookLikeAnotherAlias() {
        List<QuoteItem> items = Arrays.asList(
                configuredItem("小米 12 5G", "125G", "12+256", "256GB"),
                configuredItem("小米 14 5G", "145G", "12+256", "256GB")
        );

        MatchResult result = OcrParser.match("小米14\n12 5G\n256GB", items);

        assertEquals(MatchResult.Status.MATCHED, result.status);
        assertEquals("小米 14 5G", result.item.model);
    }

    @Test
    public void confinesModelAndPriceSearchToExplicitBrand() {
        List<QuoteItem> items = Arrays.asList(
                configuredItem("华为", "Note 12 5G", "Note125G", "8+256", "256GB"),
                configuredItem("小米", "Note 12 5G", "Note125G", "8+256", "256GB")
        );

        MatchResult result = OcrParser.match("小米 Note 12 5G 8+256", items);

        assertEquals(MatchResult.Status.MATCHED, result.status);
        assertEquals("小米", result.item.brand);
    }

    @Test
    public void usesLaterSelectedTableFirstForSameBrandModelAndMemory() {
        List<QuoteItem> prioritized = Arrays.asList(
                pricedItem("小米 14 5G", "16+512", 1500),
                pricedItem("小米 14 5G", "16+512", 1200)
        );

        MatchResult result = OcrParser.match("小米14 5G 16+512", prioritized);

        assertEquals(MatchResult.Status.MATCHED, result.status);
        assertEquals(1500, result.item.grades.get(0).price);
    }

    @Test
    public void fallsBackToEarlierTableWhenPriorityTableLacksMemory() {
        List<QuoteItem> prioritized = Arrays.asList(
                pricedItem("小米 14 5G", "12+256", 1300),
                pricedItem("小米 14 5G", "16+512", 1200)
        );

        MatchResult result = OcrParser.match("小米14 5G 16+512", prioritized);

        assertEquals(MatchResult.Status.MATCHED, result.status);
        assertEquals(1200, result.item.grades.get(0).price);
    }

    @Test
    public void rejectsSameModelAcrossBrandsWhenBrandIsMissing() {
        List<QuoteItem> items = Arrays.asList(
                configuredItem("华为", "Nova 12 5G", "Nova125G", "8+256", "256GB"),
                configuredItem("小米", "Nova 12 5G", "Nova125G", "8+256", "256GB")
        );

        MatchResult result = OcrParser.match("Nova 12 5G 8+256", items);

        assertEquals(MatchResult.Status.BRAND_NOT_FOUND, result.status);
    }

    @Test
    public void recognitionSummaryContainsOnlyBrandModelAndMemory() {
        QuoteItem phone = configuredItem(
                "小米", "小米 14 5G", "小米145G", "16+512", "512GB"
        );

        MatchResult result = OcrParser.match(
                "今日补贴活动 颜色黑色 小米14 5G 16+512 包邮 到手价",
                Collections.singletonList(phone)
        );

        assertEquals(MatchResult.Status.MATCHED, result.status);
        assertEquals(
                "品牌：小米  型号：小米 14 5G  内存：16+512",
                result.recognizedSummary
        );
    }

    @Test
    public void neverChangesNovaCoreNumberDuringFuzzyMatching() {
        List<QuoteItem> items = Arrays.asList(
                configuredItem("华为", "华为 Nova 7", "Nova7", "8+128", "128GB"),
                configuredItem("华为", "华为 Nova 8", "Nova8", "8+128", "128GB"),
                configuredItem("华为", "华为 Nova 9", "Nova9", "8+128", "128GB")
        );

        MatchResult result = OcrParser.match("华为 Nova 7\n8G+128G", items);

        assertEquals(MatchResult.Status.MATCHED, result.status);
        assertEquals("华为 Nova 7", result.item.model);
    }

    @Test
    public void mateSeriesCannotFallIntoEnjoySeriesWithSameNumber() {
        List<QuoteItem> items = Arrays.asList(
                configuredItem("华为", "华为 Mate 60 Pro", "Mate60Pro", "12+512", "512GB"),
                configuredItem("华为", "华为 畅享 60 Pro 4G", "畅享60Pro4G", "12+512", "512GB")
        );

        MatchResult result = OcrParser.match("Mate 60 Pro\n12G+512G", items);

        assertEquals(MatchResult.Status.MATCHED, result.status);
        assertEquals("华为 Mate 60 Pro", result.item.model);
    }

    @Test
    public void conflictingCriticalDigitsRequireConfirmation() {
        List<QuoteItem> items = Arrays.asList(
                configuredItem("华为", "华为 Nova 7", "Nova7", "8+128", "128GB"),
                configuredItem("华为", "华为 Nova 8", "Nova8", "8+128", "128GB")
        );
        List<List<OcrTextLine>> passes = Arrays.asList(
                Arrays.asList(OcrTextLine.plain("华为 Nova 8", 0), OcrTextLine.plain("8+128", 1)),
                Arrays.asList(OcrTextLine.plain("华为 Nova 7", 0), OcrTextLine.plain("8+128", 1))
        );

        MatchResult result = OcrParser.matchPasses(passes, items, 2300L);

        assertEquals(MatchResult.Status.MODEL_CONFIRMATION_REQUIRED, result.status);
        assertEquals(2, result.candidates.size());
    }

    @Test
    public void thirdPassMajorityResolvesCriticalDigitConflict() {
        List<QuoteItem> items = Arrays.asList(
                configuredItem("华为", "华为 Nova 7", "Nova7", "8+128", "128GB"),
                configuredItem("华为", "华为 Nova 8", "Nova8", "8+128", "128GB")
        );
        List<List<OcrTextLine>> passes = Arrays.asList(
                Arrays.asList(OcrTextLine.plain("华为 Nova 8", 0), OcrTextLine.plain("8+128", 1)),
                Arrays.asList(OcrTextLine.plain("华为 Nova 7", 0), OcrTextLine.plain("8+128", 1)),
                Arrays.asList(OcrTextLine.plain("华为 Nova 7", 0), OcrTextLine.plain("8+128", 1))
        );

        MatchResult result = OcrParser.matchPasses(passes, items, 3100L);

        assertEquals(MatchResult.Status.MATCHED, result.status);
        assertEquals("华为 Nova 7", result.item.model);
    }
}
