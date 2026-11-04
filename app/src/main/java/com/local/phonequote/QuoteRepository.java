package com.local.phonequote;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class QuoteRepository {
    public static final String LOCAL_ONLY_TABLE_ID = "local-manual";
    private static final String LEGACY_FILE_NAME = "prices.json";
    private static final String BRAND_FILE_PREFIX = "prices-";
    private static final String BRAND_FILE_SUFFIX = ".json";
    private static final String TABLES_DIR_NAME = "quote-tables";
    private static final String ACTIVE_FILE_NAME = "active.json";
    private static final String TABLE_MANIFEST_NAME = "manifest.json";
    private static final String LOCAL_CATALOG_NAME = "catalog.json";
    private static final String ACCOUNT_PREFS = "quote_account_scope";
    private static final String KEY_CURRENT_ACCOUNT = "current_account";
    private static final String KEY_LEGACY_CLAIMED = "legacy_claimed";
    private static final String ACCOUNT_DIR_NAME = "quote-accounts";

    private final List<QuoteItem> items;
    private final Map<String, Integer> brandCounts;
    private final String sourceName;
    private final String updatedAt;
    private final boolean imported;

    public static synchronized void setCurrentAccount(Context context, String accountId) {
        String safeAccount = sanitizeTableId(accountId);
        SharedPreferences preferences = context.getSharedPreferences(ACCOUNT_PREFS, Context.MODE_PRIVATE);
        File accountRoot = new File(new File(context.getFilesDir(), ACCOUNT_DIR_NAME), safeAccount);
        if (!accountRoot.exists()) {
            //noinspection ResultOfMethodCallIgnored
            accountRoot.mkdirs();
        }
        String migrationKey = "migrated_" + safeAccount;
        if (!preferences.getBoolean(migrationKey, false)) {
            boolean migrated = true;
            if (!preferences.getBoolean(KEY_LEGACY_CLAIMED, false)) {
                try {
                    migrateLegacyFiles(context.getFilesDir(), accountRoot);
                    preferences.edit().putBoolean(KEY_LEGACY_CLAIMED, true).apply();
                } catch (IOException ignored) {
                    // 保留旧数据，下次登录继续尝试迁移。
                    migrated = false;
                }
            }
            if (migrated) {
                preferences.edit().putBoolean(migrationKey, true).apply();
            }
        }
        preferences.edit().putString(KEY_CURRENT_ACCOUNT, safeAccount).apply();
    }

    public static synchronized void retainAccessibleTables(Context context, Set<String> tableIds)
            throws IOException, JSONException {
        Set<String> safeIds = new HashSet<>();
        for (String tableId : tableIds) {
            safeIds.add(sanitizeTableId(tableId));
        }
        File tablesDir = new File(rootDir(context), TABLES_DIR_NAME);
        File[] children = tablesDir.listFiles(File::isDirectory);
        if (children != null) {
            for (File child : children) {
                if (!safeIds.contains(child.getName()) && !isPreservedLocalTable(child)) {
                    deleteRecursively(child);
                    removeCatalogEntry(context, child.getName());
                } else if (isPreservedLocalTable(child)) {
                    safeIds.add(child.getName());
                }
            }
        }
        List<JSONObject> selections = readSelections(activeFile(context));
        selections.removeIf(value -> !safeIds.contains(sanitizeTableId(value.optString("tableId"))));
        writeSelections(context, selections);
    }

    public QuoteRepository(Context context) throws IOException, JSONException {
        File filesDir = rootDir(context);
        File tablesDir = new File(filesDir, TABLES_DIR_NAME);
        File activeFile = new File(tablesDir, ACTIVE_FILE_NAME);
        if (activeFile.exists()) {
            List<JSONObject> selections = readSelections(activeFile);
            selections.sort((left, right) -> Integer.compare(
                    right.optInt("selectionOrder", 0), left.optInt("selectionOrder", 0)
            ));
            Map<String, QuoteItem> prioritized = new LinkedHashMap<>();
            List<String> names = new ArrayList<>();
            String latest = "未标注";
            for (JSONObject selection : selections) {
                String tableId = sanitizeTableId(requiredText(selection, "tableId"));
                int revision = selection.getInt("revision");
                File revisionDir = new File(new File(tablesDir, tableId), String.valueOf(revision));
                File[] activeBrandFiles = listBrandFiles(revisionDir);
                if (activeBrandFiles.length == 0) {
                    continue;
                }
                ParsedCollection collection = loadBrandFiles(activeBrandFiles);
                for (QuoteItem item : collection.items) {
                    prioritized.putIfAbsent(uniqueKey(item), item);
                }
                File manifestFile = new File(revisionDir, TABLE_MANIFEST_NAME);
                if (manifestFile.exists()) {
                    JSONObject manifest = new JSONObject(new String(
                            Files.readAllBytes(manifestFile.toPath()), StandardCharsets.UTF_8
                    ));
                    String name = manifest.optString("name", "云端报价表");
                    if (!names.contains(name)) {
                        names.add(name);
                    }
                    latest = laterDate(latest, manifest.optString("updatedAt", collection.updatedAt));
                } else {
                    latest = laterDate(latest, collection.updatedAt);
                }
            }
            if (!prioritized.isEmpty()) {
                items = new ArrayList<>(prioritized.values());
                brandCounts = countBrands(items);
                sourceName = names.isEmpty() ? "多报价表" : String.join(" + ", names);
                updatedAt = latest;
                imported = true;
                return;
            }
        }
        File legacyFile = new File(filesDir, LEGACY_FILE_NAME);
        if (activeFile.exists()
                && readSelections(activeFile).isEmpty()
                && listBrandFiles(filesDir).length == 0
                && !legacyFile.exists()) {
            items = new ArrayList<>();
            brandCounts = new LinkedHashMap<>();
            sourceName = "尚未选择报价表";
            updatedAt = "未标注";
            imported = false;
            return;
        }
        splitLegacyIfNeeded(filesDir, legacyFile);
        File[] brandFiles = listBrandFiles(filesDir);
        imported = brandFiles.length > 0 || legacyFile.exists();

        if (brandFiles.length > 0) {
            ParsedCollection collection = loadBrandFiles(brandFiles);
            items = collection.items;
            brandCounts = countBrands(items);
            sourceName = "按品牌隔离报价库";
            updatedAt = collection.updatedAt;
        } else {
            ParsedData data;
            try (InputStream input = context.getAssets().open(LEGACY_FILE_NAME)) {
                data = parse(readAll(input));
            }
            items = data.items;
            brandCounts = countBrands(items);
            sourceName = data.sourceName;
            updatedAt = data.updatedAt;
        }
    }

    public List<QuoteItem> getItems() {
        return items;
    }

    public String getSourceName() {
        return sourceName;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public String getBrandSummary() {
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, Integer> entry : brandCounts.entrySet()) {
            if (summary.length() > 0) {
                summary.append(" · ");
            }
            summary.append(entry.getKey()).append(' ').append(entry.getValue()).append(" 条");
        }
        return summary.toString();
    }

    public boolean isImported() {
        return imported;
    }

    public static int importFrom(Context context, InputStream input) throws IOException, JSONException {
        byte[] bytes = readAll(input);
        ParsedData parsed = parse(bytes);
        File filesDir = rootDir(context);
        splitLegacyIfNeeded(filesDir, new File(filesDir, LEGACY_FILE_NAME));
        Map<String, byte[]> filesByBrand = splitByBrand(bytes);
        for (Map.Entry<String, byte[]> entry : filesByBrand.entrySet()) {
            writeAtomically(
                    new File(filesDir, brandFileName(entry.getKey())),
                    entry.getValue()
            );
        }
        Files.deleteIfExists(new File(new File(filesDir, TABLES_DIR_NAME), ACTIVE_FILE_NAME).toPath());
        return parsed.items.size();
    }

    public static int importTable(
            Context context,
            String tableId,
            int revision,
            String name,
            InputStream input,
            String expectedSha256
    ) throws IOException, JSONException {
        if (revision <= 0) {
            throw new JSONException("报价表版本无效");
        }
        byte[] bytes = readAll(input);
        String actualSha256 = sha256(bytes);
        if (expectedSha256 != null && !expectedSha256.isEmpty()
                && !actualSha256.equalsIgnoreCase(expectedSha256)) {
            throw new IOException("报价表校验失败，请重新下载");
        }
        ParsedData parsed = parse(bytes);
        String safeTableId = sanitizeTableId(tableId);
        File tablesDir = new File(rootDir(context), TABLES_DIR_NAME);
        File tableDir = new File(tablesDir, safeTableId);
        if (!tableDir.exists() && !tableDir.mkdirs()) {
            throw new IOException("无法创建报价表目录");
        }
        File targetDir = new File(tableDir, String.valueOf(revision));
        if (!targetDir.exists()) {
            File stagingDir = new File(tableDir, revision + ".tmp-" + System.currentTimeMillis());
            if (!stagingDir.mkdirs()) {
                throw new IOException("无法创建报价表临时目录");
            }
            try {
                for (Map.Entry<String, byte[]> entry : splitByBrand(bytes).entrySet()) {
                    writeAtomically(new File(stagingDir, brandFileName(entry.getKey())), entry.getValue());
                }
                JSONObject manifest = new JSONObject();
                manifest.put("tableId", tableId);
                manifest.put("revision", revision);
                manifest.put("name", name == null ? parsed.sourceName : name);
                manifest.put("updatedAt", parsed.updatedAt);
                manifest.put("sha256", actualSha256);
                manifest.put("downloadedAt", System.currentTimeMillis());
                manifest.put("cloudDeleted", false);
                manifest.put("sourceType", "云端下载");
                manifest.put("itemCount", parsed.items.size());
                manifest.put("brands", new JSONArray(countBrands(parsed.items).keySet()));
                manifest.put("brandSummary", brandSummary(countBrands(parsed.items)));
                writeAtomically(
                        new File(stagingDir, TABLE_MANIFEST_NAME),
                        manifest.toString(2).getBytes(StandardCharsets.UTF_8)
                );
                loadBrandFiles(listBrandFiles(stagingDir));
                try {
                    Files.move(stagingDir.toPath(), targetDir.toPath(), StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(stagingDir.toPath(), targetDir.toPath());
                }
            } catch (IOException | JSONException exception) {
                deleteRecursively(stagingDir);
                throw exception;
            }
        }
        updateCatalogFromManifest(context, targetDir);
        return parsed.items.size();
    }

    public static void activateTable(Context context, String tableId, int revision)
            throws IOException, JSONException {
        replaceSelections(context, Collections.singletonList(
                selection(tableId, revision, 1)
        ));
    }

    public static void selectTable(Context context, String tableId, int revision, int selectionOrder)
            throws IOException, JSONException {
        validateImportedTable(context, tableId, revision);
        File activeFile = activeFile(context);
        List<JSONObject> selections = readSelections(activeFile);
        int nextOrder = selectionOrder;
        for (int i = selections.size() - 1; i >= 0; i--) {
            JSONObject value = selections.get(i);
            nextOrder = Math.max(nextOrder, value.optInt("selectionOrder", 0) + 1);
            if (tableId.equals(value.optString("tableId"))) {
                selections.remove(i);
            }
        }
        selections.add(selection(tableId, revision, nextOrder));
        writeSelections(context, selections);
    }

    public static void deselectTable(Context context, String tableId) throws IOException, JSONException {
        File activeFile = activeFile(context);
        List<JSONObject> selections = readSelections(activeFile);
        selections.removeIf(value -> tableId.equals(value.optString("tableId")));
        writeSelections(context, selections);
    }

    public static void replaceSelections(Context context, List<JSONObject> selections)
            throws IOException, JSONException {
        for (JSONObject value : selections) {
            validateImportedTable(
                    context,
                    requiredText(value, "tableId"),
                    value.getInt("revision")
            );
        }
        writeSelections(context, selections);
    }

    private static void validateImportedTable(Context context, String tableId, int revision)
            throws IOException, JSONException {
        String safeTableId = sanitizeTableId(tableId);
        File tablesDir = new File(rootDir(context), TABLES_DIR_NAME);
        File revisionDir = new File(new File(tablesDir, safeTableId), String.valueOf(revision));
        File[] brandFiles = listBrandFiles(revisionDir);
        if (brandFiles.length == 0) {
            throw new IOException("报价表尚未下载到本机");
        }
        loadBrandFiles(brandFiles);
    }

    public static boolean isTableImported(Context context, String tableId, int revision) {
        if (revision <= 0) {
            return false;
        }
        File revisionDir = new File(
                new File(new File(rootDir(context), TABLES_DIR_NAME), sanitizeTableId(tableId)),
                String.valueOf(revision)
        );
        return new File(revisionDir, TABLE_MANIFEST_NAME).isFile()
                && listBrandFiles(revisionDir).length > 0;
    }

    public static boolean isTableActive(Context context, String tableId, int revision) {
        File activeFile = activeFile(context);
        if (!activeFile.isFile()) {
            return false;
        }
        try {
            for (JSONObject selection : readSelections(activeFile)) {
                if (tableId.equals(selection.optString("tableId"))
                        && revision == selection.optInt("revision")) {
                    return true;
                }
            }
            return false;
        } catch (IOException | JSONException ignored) {
            return false;
        }
    }

    public static int selectionOrder(Context context, String tableId, int revision) {
        try {
            for (JSONObject selection : readSelections(activeFile(context))) {
                if (tableId.equals(selection.optString("tableId"))
                        && revision == selection.optInt("revision")) {
                    return selection.optInt("selectionOrder", 0);
                }
            }
        } catch (IOException | JSONException ignored) {
            // A damaged selection file is treated as not selected.
        }
        return 0;
    }

    public static List<JSONObject> selectedTables(Context context) {
        try {
            return new ArrayList<>(readSelections(activeFile(context)));
        } catch (IOException | JSONException ignored) {
            return new ArrayList<>();
        }
    }

    public static void deleteTable(Context context, String tableId) throws IOException, JSONException {
        deselectTable(context, tableId);
        File tableDir = new File(
                new File(rootDir(context), TABLES_DIR_NAME), sanitizeTableId(tableId)
        );
        deleteRecursively(tableDir);
        removeCatalogEntry(context, sanitizeTableId(tableId));
    }

    public static synchronized void markCloudDeleted(Context context, String tableId)
            throws IOException, JSONException {
        String safeTableId = sanitizeTableId(tableId);
        File tableDir = new File(new File(rootDir(context), TABLES_DIR_NAME), safeTableId);
        File[] revisions = tableDir.listFiles(File::isDirectory);
        if (revisions == null || revisions.length == 0) {
            return;
        }
        File newest = null;
        int newestRevision = -1;
        for (File revisionDir : revisions) {
            int revision;
            try {
                revision = Integer.parseInt(revisionDir.getName());
            } catch (NumberFormatException ignored) {
                continue;
            }
            File manifestFile = new File(revisionDir, TABLE_MANIFEST_NAME);
            if (!manifestFile.isFile()) {
                continue;
            }
            JSONObject manifest = readObject(manifestFile);
            manifest.put("cloudDeleted", true);
            manifest.put("cloudDeletedAt", System.currentTimeMillis());
            manifest.put("sourceType", "仅本机保存");
            writeAtomically(manifestFile, manifest.toString(2).getBytes(StandardCharsets.UTF_8));
            if (revision > newestRevision) {
                newestRevision = revision;
                newest = revisionDir;
            }
        }
        if (newest != null) {
            updateCatalogFromManifest(context, newest);
        }
    }

    public static boolean isCloudDeletedLocal(Context context, String tableId) {
        File tableDir = new File(
                new File(rootDir(context), TABLES_DIR_NAME), sanitizeTableId(tableId)
        );
        return isPreservedLocalTable(tableDir);
    }

    public static List<QuoteTableInfo> localImportedTableInfos(Context context) {
        List<QuoteTableInfo> result = new ArrayList<>();
        File tablesDir = new File(rootDir(context), TABLES_DIR_NAME);
        File[] tables = tablesDir.listFiles(File::isDirectory);
        if (tables == null) {
            return result;
        }
        Arrays.sort(tables, (left, right) -> left.getName().compareTo(right.getName()));
        for (File tableDir : tables) {
            File revisionDir = latestRevisionDir(tableDir);
            if (revisionDir == null) {
                continue;
            }
            try {
                int revision = Integer.parseInt(revisionDir.getName());
                JSONObject manifest = readObject(new File(revisionDir, TABLE_MANIFEST_NAME));
                ParsedCollection collection = loadBrandFiles(listBrandFiles(revisionDir));
                Map<String, Integer> counts = countBrands(collection.items);
                List<String> brands = new ArrayList<>(counts.keySet());
                boolean active = isTableActive(context, tableDir.getName(), revision);
                boolean cloudDeleted = manifest.optBoolean("cloudDeleted", false);
                result.add(new QuoteTableInfo(
                        manifest.optString("tableId", tableDir.getName()),
                        manifest.optString("name", "本机报价表"),
                        cloudDeleted ? "仅本机保存" : manifest.optString("sourceType", "本机已下载"),
                        manifest.optString("updatedAt", collection.updatedAt),
                        manifest.optInt("itemCount", collection.items.size()),
                        manifest.optString("brandSummary", brandSummary(counts)),
                        active,
                        QuoteTableInfo.Status.EXPIRED,
                        revision,
                        revision,
                        cloudDeleted
                                ? QuoteTableInfo.LocalState.LOCAL_ONLY_CLOUD_DELETED
                                : QuoteTableInfo.LocalState.EXPIRED_LOCAL_ONLY,
                        manifest.optLong("downloadedAt", 0L),
                        brands,
                        selectionOrder(context, tableDir.getName(), revision),
                        false,
                        0L,
                        "OWN",
                        false,
                        manifest.optString("uploaderName"),
                        false
                ));
            } catch (IOException | JSONException | NumberFormatException ignored) {
                // 损坏或未完成的目录不会出现在报价表列表中。
            }
        }
        return result;
    }

    public static boolean hasLocalOnlyTable(Context context) {
        return listBrandFiles(rootDir(context)).length > 0;
    }

    public static QuoteTableInfo localOnlyTableInfo(Context context) {
        File[] files = listBrandFiles(rootDir(context));
        if (files.length == 0) {
            return null;
        }
        try {
            ParsedCollection collection = loadBrandFiles(files);
            Map<String, Integer> counts = countBrands(collection.items);
            List<String> brands = new ArrayList<>(counts.keySet());
            StringBuilder summary = new StringBuilder();
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (summary.length() > 0) summary.append(" · ");
                summary.append(entry.getKey()).append(' ').append(entry.getValue()).append(" 条");
            }
            boolean active = readSelections(activeFile(context)).isEmpty();
            return new QuoteTableInfo(
                    LOCAL_ONLY_TABLE_ID,
                    "本地手动导入报价表",
                    "仅本机保存",
                    collection.updatedAt,
                    collection.items.size(),
                    summary.toString(),
                    active,
                    QuoteTableInfo.Status.EXPIRED,
                    1,
                    1,
                    QuoteTableInfo.LocalState.LOCAL_ONLY_MANUAL,
                    0L,
                    brands,
                    active ? 1 : 0,
                    false,
                    0L
            );
        } catch (IOException | JSONException ignored) {
            return null;
        }
    }

    public static void deleteLocalOnlyTable(Context context) throws IOException {
        for (File file : listBrandFiles(rootDir(context))) {
            Files.deleteIfExists(file.toPath());
        }
        Files.deleteIfExists(new File(rootDir(context), LEGACY_FILE_NAME).toPath());
    }

    public static long downloadedAt(Context context, String tableId, int revision) {
        File manifest = new File(
                new File(new File(new File(rootDir(context), TABLES_DIR_NAME), sanitizeTableId(tableId)),
                        String.valueOf(revision)),
                TABLE_MANIFEST_NAME
        );
        try {
            JSONObject value = new JSONObject(new String(
                    Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8
            ));
            return value.optLong("downloadedAt", 0L);
        } catch (IOException | JSONException ignored) {
            return 0L;
        }
    }

    private static void splitLegacyIfNeeded(File filesDir, File legacyFile)
            throws IOException, JSONException {
        if (!legacyFile.exists() || listBrandFiles(filesDir).length > 0) {
            return;
        }
        byte[] bytes;
        try (InputStream input = new java.io.FileInputStream(legacyFile)) {
            bytes = readAll(input);
        }
        parse(bytes);
        for (Map.Entry<String, byte[]> entry : splitByBrand(bytes).entrySet()) {
            writeAtomically(new File(filesDir, brandFileName(entry.getKey())), entry.getValue());
        }
    }

    private static File rootDir(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(ACCOUNT_PREFS, Context.MODE_PRIVATE);
        String account = preferences.getString(KEY_CURRENT_ACCOUNT, "");
        if (account == null || account.isEmpty()) {
            return context.getFilesDir();
        }
        File root = new File(new File(context.getFilesDir(), ACCOUNT_DIR_NAME), account);
        if (!root.exists()) {
            //noinspection ResultOfMethodCallIgnored
            root.mkdirs();
        }
        return root;
    }

    private static void migrateLegacyFiles(File legacyRoot, File accountRoot) throws IOException {
        File legacyTables = new File(legacyRoot, TABLES_DIR_NAME);
        File targetTables = new File(accountRoot, TABLES_DIR_NAME);
        if (legacyTables.exists() && !targetTables.exists()) {
            copyRecursively(legacyTables, targetTables);
        }
        File legacyJson = new File(legacyRoot, LEGACY_FILE_NAME);
        if (legacyJson.isFile()) {
            Files.copy(legacyJson.toPath(), new File(accountRoot, LEGACY_FILE_NAME).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        for (File brandFile : listBrandFiles(legacyRoot)) {
            Files.copy(brandFile.toPath(), new File(accountRoot, brandFile.getName()).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void copyRecursively(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) {
                throw new IOException("无法创建账号报价目录");
            }
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyRecursively(child, new File(target, child.getName()));
                }
            }
            return;
        }
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static Map<String, byte[]> splitByBrand(byte[] bytes) throws JSONException {
        JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        JSONArray sourceItems = root.getJSONArray("items");
        Map<String, JSONArray> itemsByBrand = new LinkedHashMap<>();
        for (int i = 0; i < sourceItems.length(); i++) {
            JSONObject item = sourceItems.getJSONObject(i);
            String brand = requiredText(item, "brand");
            itemsByBrand.computeIfAbsent(brand, ignored -> new JSONArray()).put(item);
        }
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, JSONArray> entry : itemsByBrand.entrySet()) {
            JSONObject brandRoot = new JSONObject();
            brandRoot.put("version", 1);
            brandRoot.put("sourceName", entry.getKey() + "回收报价");
            brandRoot.put("updatedAt", root.optString("updatedAt", "未标注").trim());
            brandRoot.put("items", entry.getValue());
            result.put(
                    entry.getKey(),
                    brandRoot.toString(2).getBytes(StandardCharsets.UTF_8)
            );
        }
        return result;
    }

    private static void writeAtomically(File target, byte[] bytes) throws IOException {
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
            output.getFD().sync();
        }
        try {
            Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static File[] listBrandFiles(File filesDir) {
        File[] files = filesDir.listFiles((dir, name) ->
                name.startsWith(BRAND_FILE_PREFIX)
                        && name.endsWith(BRAND_FILE_SUFFIX)
                        && !LEGACY_FILE_NAME.equals(name)
        );
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files, (left, right) -> left.getName().compareTo(right.getName()));
        return files;
    }

    private static String brandFileName(String brand) {
        String normalized = OcrParser.normalize(brand);
        if ("小米".equals(normalized)) {
            return "prices-xiaomi.json";
        }
        if ("华为".equals(normalized)) {
            return "prices-huawei.json";
        }
        StringBuilder encoded = new StringBuilder();
        for (byte value : brand.getBytes(StandardCharsets.UTF_8)) {
            encoded.append(String.format("%02x", value & 0xff));
        }
        return BRAND_FILE_PREFIX + encoded + BRAND_FILE_SUFFIX;
    }

    private static Map<String, Integer> countBrands(List<QuoteItem> sourceItems) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (QuoteItem item : sourceItems) {
            counts.put(item.brand, counts.getOrDefault(item.brand, 0) + 1);
        }
        return counts;
    }

    private static String uniqueKey(QuoteItem item) {
        return item.brand + "\u0000" + item.model + "\u0000"
                + (item.configuration.isEmpty() ? item.storage : item.configuration);
    }

    private static ParsedData parse(byte[] bytes) throws JSONException {
        JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (root.optInt("version", -1) != 1) {
            throw new JSONException("仅支持 version=1 的报价文件");
        }
        String sourceName = root.optString("sourceName", "本地报价").trim();
        String updatedAt = root.optString("updatedAt", "未标注").trim();
        JSONArray sourceItems = root.getJSONArray("items");
        List<QuoteItem> items = new ArrayList<>();
        Set<String> uniqueConfigurations = new HashSet<>();
        for (int i = 0; i < sourceItems.length(); i++) {
            JSONObject source = sourceItems.getJSONObject(i);
            String brand = requiredText(source, "brand");
            String model = requiredText(source, "model");
            String storage = requiredText(source, "storage");
            String configuration = source.optString("configuration", "").trim();
            if (OcrParser.capacityToGb(storage) < 0) {
                throw new JSONException("第 " + (i + 1) + " 条容量格式错误: " + storage);
            }
            if (!configuration.isEmpty()
                    && (OcrParser.configurationRamGb(configuration) < 0
                    || OcrParser.configurationStorageGb(configuration) != OcrParser.capacityToGb(storage))) {
                throw new JSONException("第 " + (i + 1) + " 条内存组合格式错误: " + configuration);
            }
            String uniqueKey = brand + "\u0000" + model + "\u0000"
                    + (configuration.isEmpty() ? storage : configuration);
            if (!uniqueConfigurations.add(uniqueKey)) {
                throw new JSONException("存在重复的型号内存组合: " + model + " " + uniqueKey.substring(uniqueKey.lastIndexOf('\u0000') + 1));
            }

            List<String> aliases = new ArrayList<>();
            JSONArray sourceAliases = source.optJSONArray("aliases");
            if (sourceAliases != null) {
                for (int j = 0; j < sourceAliases.length(); j++) {
                    String alias = sourceAliases.getString(j).trim();
                    if (!alias.isEmpty()) {
                        aliases.add(alias);
                    }
                }
            }

            JSONArray sourceGrades = source.getJSONArray("grades");
            if (sourceGrades.length() == 0) {
                throw new JSONException("第 " + (i + 1) + " 条缺少成色价格");
            }
            List<QuoteItem.GradePrice> grades = new ArrayList<>();
            for (int j = 0; j < sourceGrades.length(); j++) {
                JSONObject grade = sourceGrades.getJSONObject(j);
                grades.add(new QuoteItem.GradePrice(
                        requiredText(grade, "name"),
                        grade.getInt("price")
                ));
            }
            items.add(new QuoteItem(brand, model, aliases, configuration, storage, grades));
        }
        if (items.isEmpty()) {
            throw new JSONException("报价文件没有数据");
        }
        return new ParsedData(items, sourceName, updatedAt);
    }

    private static String requiredText(JSONObject object, String key) throws JSONException {
        String value = object.getString(key).trim();
        if (value.isEmpty()) {
            throw new JSONException(key + " 不能为空");
        }
        return value;
    }

    private static ParsedCollection loadBrandFiles(File[] brandFiles)
            throws IOException, JSONException {
        List<QuoteItem> combinedItems = new ArrayList<>();
        Set<String> uniqueConfigurations = new HashSet<>();
        String latestUpdate = "未标注";
        for (File brandFile : brandFiles) {
            ParsedData data;
            try (InputStream input = new java.io.FileInputStream(brandFile)) {
                data = parse(readAll(input));
            }
            for (QuoteItem item : data.items) {
                String key = uniqueKey(item);
                if (!uniqueConfigurations.add(key)) {
                    throw new JSONException("品牌报价文件存在重复配置: " + item.model + " "
                            + (item.configuration.isEmpty() ? item.storage : item.configuration));
                }
                combinedItems.add(item);
            }
            if (latestUpdate.equals("未标注") || data.updatedAt.compareTo(latestUpdate) > 0) {
                latestUpdate = data.updatedAt;
            }
        }
        if (combinedItems.isEmpty()) {
            throw new JSONException("报价表没有品牌数据");
        }
        return new ParsedCollection(combinedItems, latestUpdate);
    }

    private static File activeFile(Context context) {
        return new File(new File(rootDir(context), TABLES_DIR_NAME), ACTIVE_FILE_NAME);
    }

    private static File catalogFile(Context context) {
        return new File(new File(rootDir(context), TABLES_DIR_NAME), LOCAL_CATALOG_NAME);
    }

    private static JSONObject readObject(File file) throws IOException, JSONException {
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    private static File latestRevisionDir(File tableDir) {
        File[] revisions = tableDir.listFiles(File::isDirectory);
        File latest = null;
        int latestNumber = -1;
        if (revisions == null) {
            return null;
        }
        for (File revision : revisions) {
            int value;
            try {
                value = Integer.parseInt(revision.getName());
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (value > latestNumber
                    && new File(revision, TABLE_MANIFEST_NAME).isFile()
                    && listBrandFiles(revision).length > 0) {
                latestNumber = value;
                latest = revision;
            }
        }
        return latest;
    }

    private static boolean isPreservedLocalTable(File tableDir) {
        File[] revisions = tableDir.listFiles(File::isDirectory);
        if (revisions == null) {
            return false;
        }
        for (File revision : revisions) {
            File manifest = new File(revision, TABLE_MANIFEST_NAME);
            if (!manifest.isFile()) {
                continue;
            }
            try {
                if (readObject(manifest).optBoolean("cloudDeleted", false)) {
                    return true;
                }
            } catch (IOException | JSONException ignored) {
                // 损坏的目录按不可保留处理。
            }
        }
        return false;
    }

    private static synchronized void updateCatalogFromManifest(Context context, File revisionDir)
            throws IOException, JSONException {
        File manifestFile = new File(revisionDir, TABLE_MANIFEST_NAME);
        JSONObject manifest = readObject(manifestFile);
        String tableId = sanitizeTableId(manifest.optString(
                "tableId", revisionDir.getParentFile().getName()
        ));
        JSONArray entries = readCatalogEntries(context);
        JSONArray updated = new JSONArray();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            if (!tableId.equals(sanitizeTableId(entry.optString("tableId")))) {
                updated.put(entry);
            }
        }
        JSONObject entry = new JSONObject();
        entry.put("tableId", tableId);
        entry.put("revision", manifest.optInt("revision", Integer.parseInt(revisionDir.getName())));
        entry.put("name", manifest.optString("name", "本机报价表"));
        entry.put("downloadedAt", manifest.optLong("downloadedAt", 0L));
        entry.put("cloudDeleted", manifest.optBoolean("cloudDeleted", false));
        entry.put("path", revisionDir.getAbsolutePath());
        updated.put(entry);
        writeCatalog(context, updated);
    }

    private static synchronized void removeCatalogEntry(Context context, String tableId)
            throws IOException, JSONException {
        JSONArray entries = readCatalogEntries(context);
        JSONArray updated = new JSONArray();
        String safeTableId = sanitizeTableId(tableId);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            if (!safeTableId.equals(sanitizeTableId(entry.optString("tableId")))) {
                updated.put(entry);
            }
        }
        writeCatalog(context, updated);
    }

    private static JSONArray readCatalogEntries(Context context) throws IOException, JSONException {
        File file = catalogFile(context);
        if (!file.isFile()) {
            return new JSONArray();
        }
        return readObject(file).optJSONArray("tables") == null
                ? new JSONArray()
                : readObject(file).getJSONArray("tables");
    }

    private static void writeCatalog(Context context, JSONArray entries) throws IOException, JSONException {
        File tablesDir = new File(rootDir(context), TABLES_DIR_NAME);
        if (!tablesDir.exists() && !tablesDir.mkdirs()) {
            throw new IOException("无法创建本地报价索引目录");
        }
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("tables", entries);
        root.put("updatedAt", System.currentTimeMillis());
        writeAtomically(catalogFile(context), root.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject selection(String tableId, int revision, int order) throws JSONException {
        JSONObject value = new JSONObject();
        value.put("tableId", tableId);
        value.put("revision", revision);
        value.put("selectionOrder", Math.max(1, order));
        return value;
    }

    private static List<JSONObject> readSelections(File activeFile) throws IOException, JSONException {
        List<JSONObject> result = new ArrayList<>();
        if (!activeFile.isFile()) {
            return result;
        }
        JSONObject root = new JSONObject(new String(
                Files.readAllBytes(activeFile.toPath()), StandardCharsets.UTF_8
        ));
        JSONArray values = root.optJSONArray("tables");
        if (values != null) {
            for (int i = 0; i < values.length(); i++) {
                result.add(values.getJSONObject(i));
            }
        } else if (!root.optString("tableId").isEmpty()) {
            result.add(selection(root.getString("tableId"), root.getInt("revision"), 1));
        }
        return result;
    }

    private static void writeSelections(Context context, List<JSONObject> selections)
            throws IOException, JSONException {
        File tablesDir = new File(rootDir(context), TABLES_DIR_NAME);
        if (!tablesDir.exists() && !tablesDir.mkdirs()) {
            throw new IOException("无法创建报价表目录");
        }
        selections.sort((left, right) -> Integer.compare(
                left.optInt("selectionOrder", 0), right.optInt("selectionOrder", 0)
        ));
        JSONArray values = new JSONArray();
        for (JSONObject value : selections) {
            values.put(value);
        }
        JSONObject root = new JSONObject();
        root.put("version", 2);
        root.put("tables", values);
        writeAtomically(
                new File(tablesDir, ACTIVE_FILE_NAME),
                root.toString(2).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String laterDate(String current, String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return current;
        }
        return "未标注".equals(current) || candidate.compareTo(current) > 0 ? candidate : current;
    }

    private static String brandSummary(Map<String, Integer> counts) {
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (summary.length() > 0) {
                summary.append(" · ");
            }
            summary.append(entry.getKey()).append(' ').append(entry.getValue()).append(" 条");
        }
        return summary.toString();
    }

    private static String sanitizeTableId(String tableId) {
        String value = tableId == null ? "" : tableId.replaceAll("[^A-Za-z0-9_-]", "_");
        return value.isEmpty() ? "unknown" : value;
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder();
            for (byte item : digest) {
                value.append(String.format("%02x", item & 0xff));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("设备缺少 SHA-256 校验支持", exception);
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static final class ParsedData {
        final List<QuoteItem> items;
        final String sourceName;
        final String updatedAt;

        ParsedData(List<QuoteItem> items, String sourceName, String updatedAt) {
            this.items = items;
            this.sourceName = sourceName;
            this.updatedAt = updatedAt;
        }
    }

    private static final class ParsedCollection {
        final List<QuoteItem> items;
        final String updatedAt;

        ParsedCollection(List<QuoteItem> items, String updatedAt) {
            this.items = items;
            this.updatedAt = updatedAt;
        }
    }
}
