package com.example.selwatermelon;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;
import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class RipenessModel {
    static final String BUILTIN_MODEL_ID = "__builtin__";
    private static final String LEGACY_TFLITE_ID = "__legacy_tflite__";
    private static final String LEGACY_JSON_ID = "__legacy_json__";
    private static final String MODEL_DIR = "uploaded_models";
    private static final String PREF_ACTIVE_MODEL_ID = "active_model_id";
    private static final String[] DEFAULT_LABELS = {"unripe", "ripe", "bursting", "overripe"};
    private static final double BURSTING_OVER_RIPE_WEIGHT = 0.65;

    private final Map<String, double[]> centroids = new LinkedHashMap<>();
    private Interpreter interpreter;
    private double[][] linearCoefficients;
    private double[] linearIntercepts;
    private String[] labels = DEFAULT_LABELS;
    private double[] means;
    private double[] stds;
    private String activeModelId = BUILTIN_MODEL_ID;
    private String source = "内置 JSON 质心模型";

    static final class ModelEntry {
        final String id;
        final String name;
        final boolean deletable;

        ModelEntry(String id, String name, boolean deletable) {
            this.id = id;
            this.name = name;
            this.deletable = deletable;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static RipenessModel load(Context context) {
        RipenessModel model = new RipenessModel();
        String selected = context.getSharedPreferences("collector", Context.MODE_PRIVATE)
                .getString(PREF_ACTIVE_MODEL_ID, "");
        if (!selected.isEmpty()) {
            try {
                model.loadModel(context, selected);
                return model;
            } catch (Exception ignored) {
                model.closeInterpreter();
            }
        }

        List<ModelEntry> entries = listAvailable(context);
        if (!entries.isEmpty() && !BUILTIN_MODEL_ID.equals(entries.get(0).id)) {
            try {
                model.loadModel(context, entries.get(0).id);
                return model;
            } catch (Exception ignored) {
                model.closeInterpreter();
            }
        }

        try {
            model.loadModel(context, BUILTIN_MODEL_ID);
        } catch (Exception ignored) {
            model.setFallback();
        }
        return model;
    }

    static List<ModelEntry> listAvailable(Context context) {
        List<ModelEntry> entries = new ArrayList<>();
        File root = modelRoot(context);
        File[] dirs = root.exists() ? root.listFiles(File::isDirectory) : null;
        if (dirs != null) {
            Arrays.sort(dirs, Comparator.comparingLong(File::lastModified).reversed());
            for (File dir : dirs) {
                File tflite = new File(dir, "model.tflite");
                File metadata = new File(dir, "model_metadata.json");
                File json = new File(dir, "model.json");
                if ((tflite.exists() && metadata.exists()) || json.exists()) {
                    entries.add(new ModelEntry(dir.getName(), modelName(dir), true));
                }
            }
        }

        File base = context.getExternalFilesDir(null);
        if (base != null) {
            if (new File(base, "model.tflite").exists() && new File(base, "model_metadata.json").exists()) {
                entries.add(new ModelEntry(LEGACY_TFLITE_ID, "旧版上传 MLP TFLite 模型", false));
            }
            if (new File(base, "model.json").exists()) {
                entries.add(new ModelEntry(LEGACY_JSON_ID, "旧版上传 JSON 质心模型", false));
            }
        }
        entries.add(new ModelEntry(BUILTIN_MODEL_ID, "内置 JSON 质心模型", false));
        return entries;
    }

    static void deleteModel(Context context, String id) throws Exception {
        if (BUILTIN_MODEL_ID.equals(id) || LEGACY_TFLITE_ID.equals(id) || LEGACY_JSON_ID.equals(id)) {
            throw new IllegalArgumentException("内置模型和旧版模型不能删除");
        }
        File dir = new File(modelRoot(context), id);
        File root = modelRoot(context).getCanonicalFile();
        File target = dir.getCanonicalFile();
        if (!target.getPath().startsWith(root.getPath()) || !target.isDirectory()) {
            throw new IllegalArgumentException("模型不存在");
        }
        deleteRecursive(target);
    }

    ModelEntry importFromUri(Context context, Uri uri) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IllegalArgumentException("无法读取模型文件");
            }
            byte[] bytes = readAll(in);
            if (isZip(bytes)) {
                return importBundle(context, bytes, displayName(context, uri));
            }
            return importJson(context, bytes, displayName(context, uri));
        }
    }

    void loadModel(Context context, String id) throws Exception {
        if (BUILTIN_MODEL_ID.equals(id)) {
            closeInterpreter();
            File base = context.getExternalFilesDir(null);
            File custom = base == null ? null : new File(base, "model.json");
            try (InputStream in = custom != null && custom.exists()
                    ? new FileInputStream(custom)
                    : context.getAssets().open("model.json")) {
                parseJsonModel(readUtf8(in));
                source = custom != null && custom.exists() ? "旧版上传 JSON 质心模型" : "内置 JSON 质心模型";
                activeModelId = BUILTIN_MODEL_ID;
            }
            return;
        }

        File base = context.getExternalFilesDir(null);
        if (LEGACY_TFLITE_ID.equals(id) && base != null) {
            loadTflite(new File(base, "model.tflite"), new File(base, "model_metadata.json"), "旧版上传 MLP TFLite 模型", id);
            return;
        }
        if (LEGACY_JSON_ID.equals(id) && base != null) {
            closeInterpreter();
            parseJsonModel(readUtf8(new FileInputStream(new File(base, "model.json"))));
            source = "旧版上传 JSON 质心模型";
            activeModelId = id;
            return;
        }

        File dir = new File(modelRoot(context), id);
        File tflite = new File(dir, "model.tflite");
        File metadata = new File(dir, "model_metadata.json");
        File json = new File(dir, "model.json");
        if (json.exists()) {
            closeInterpreter();
            parseJsonModel(readUtf8(new FileInputStream(json)));
            source = modelName(dir);
            activeModelId = id;
            return;
        }
        if (!tflite.exists() || !metadata.exists()) {
            throw new IllegalArgumentException("模型不存在或不完整");
        }
        loadTflite(tflite, metadata, modelName(dir), id);
    }

    void saveActiveModel(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("collector", Context.MODE_PRIVATE);
        preferences.edit().putString(PREF_ACTIVE_MODEL_ID, activeModelId).apply();
    }

    String activeModelId() {
        return activeModelId;
    }

    String source() {
        return source;
    }

    Prediction predict(AudioFeatures features) {
        if (interpreter != null) {
            return predictTflite(features);
        }
        if (linearCoefficients != null && linearIntercepts != null) {
            return predictLinear(features);
        }
        if (centroids.isEmpty()) {
            return classic(features);
        }
        double[] x = features.toArray();
        String best = "unknown";
        double bestDistance = Double.MAX_VALUE;
        double secondDistance = Double.MAX_VALUE;
        for (Map.Entry<String, double[]> entry : centroids.entrySet()) {
            double d = 0;
            double[] c = entry.getValue();
            for (int i = 0; i < Math.min(Math.min(x.length, c.length), stds.length); i++) {
                double z = (x[i] - c[i]) / stds[i];
                d += z * z;
            }
            if (d < bestDistance) {
                secondDistance = bestDistance;
                bestDistance = d;
                best = entry.getKey();
            } else if (d < secondDistance) {
                secondDistance = d;
            }
        }
        double confidence = secondDistance == Double.MAX_VALUE ? 0.55
                : Math.max(0.35, Math.min(0.96, (secondDistance - bestDistance + 1.0) / (secondDistance + 1.0)));
        return new Prediction(best, confidence, describe(best), source);
    }

    private Prediction predictTflite(AudioFeatures features) {
        double[] values = features.toArray();
        int inputSize = Math.min(values.length, Math.min(means.length, stds.length));
        float[][] input = new float[1][inputSize];
        for (int i = 0; i < inputSize; i++) {
            input[0][i] = (float) ((values[i] - means[i]) / stds[i]);
        }
        float[][] output = new float[1][labels.length];
        interpreter.run(input, output);

        int bestIndex = 0;
        for (int i = 1; i < labels.length; i++) {
            if (output[0][i] > output[0][bestIndex]) {
                bestIndex = i;
            }
        }
        String label = labels[bestIndex];
        return adaptMissingBursting(new Prediction(label, output[0][bestIndex], describe(label), source), features);
    }

    private Prediction predictLinear(AudioFeatures features) {
        double[] values = features.toArray();
        double[] logits = new double[labels.length];
        for (int labelIndex = 0; labelIndex < labels.length; labelIndex++) {
            double score = labelIndex < linearIntercepts.length ? linearIntercepts[labelIndex] : 0.0;
            double[] weights = labelIndex < linearCoefficients.length ? linearCoefficients[labelIndex] : new double[0];
            int inputSize = Math.min(values.length, Math.min(Math.min(means.length, stds.length), weights.length));
            for (int i = 0; i < inputSize; i++) {
                score += ((values[i] - means[i]) / stds[i]) * weights[i];
            }
            logits[labelIndex] = score;
        }

        double max = logits[0];
        for (int i = 1; i < logits.length; i++) {
            max = Math.max(max, logits[i]);
        }
        double total = 0;
        double[] probabilities = new double[logits.length];
        for (int i = 0; i < logits.length; i++) {
            probabilities[i] = Math.exp(logits[i] - max);
            total += probabilities[i];
        }
        int bestIndex = 0;
        for (int i = 0; i < probabilities.length; i++) {
            probabilities[i] = total > 0 ? probabilities[i] / total : 1.0 / probabilities.length;
            if (probabilities[i] > probabilities[bestIndex]) {
                bestIndex = i;
            }
        }
        String label = labels[bestIndex];
        return adaptMissingBursting(new Prediction(label, probabilities[bestIndex], describe(label), source), features);
    }

    private Prediction classic(AudioFeatures f) {
        String label;
        if (isClearlyOverripe(f)) {
            label = "overripe";
        } else if (isBurstingSweetSpot(f)) {
            label = "bursting";
        } else if (f.centroid < 1200 && f.lowRatio > 0.25 && f.highRatio < 0.22 && f.decay > 0.58) {
            label = "ripe";
        } else {
            label = "unripe";
        }
        return new Prediction(label, 0.50, describe(label), "经典阈值");
    }

    private ModelEntry importBundle(Context context, byte[] bytes, String originalName) throws Exception {
        byte[] modelBytes = null;
        byte[] metadataBytes = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = new File(entry.getName()).getName();
                if ("model.tflite".equals(name)) {
                    modelBytes = readAll(zip);
                } else if ("model_metadata.json".equals(name)) {
                    metadataBytes = readAll(zip);
                }
                zip.closeEntry();
            }
        }
        if (modelBytes == null || metadataBytes == null) {
            throw new IllegalArgumentException("模型包需要包含 model.tflite 和 model_metadata.json");
        }
        File dir = createModelDir(context, originalName);
        writeBytes(new File(dir, "model.tflite"), modelBytes);
        writeBytes(new File(dir, "model_metadata.json"), metadataBytes);
        writeModelInfo(dir, cleanModelName(originalName));
        loadTflite(new File(dir, "model.tflite"), new File(dir, "model_metadata.json"), modelName(dir), dir.getName());
        saveActiveModel(context);
        return new ModelEntry(dir.getName(), modelName(dir), true);
    }

    private ModelEntry importJson(Context context, byte[] bytes, String originalName) throws Exception {
        String json = new String(bytes, StandardCharsets.UTF_8);
        parseJsonModel(json);
        File dir = createModelDir(context, originalName);
        writeBytes(new File(dir, "model.json"), bytes);
        writeModelInfo(dir, cleanModelName(originalName));
        closeInterpreter();
        source = modelName(dir);
        activeModelId = dir.getName();
        saveActiveModel(context);
        return new ModelEntry(dir.getName(), modelName(dir), true);
    }

    private void loadTflite(File tflite, File metadata, String modelSource, String id) throws Exception {
        parseMetadata(readUtf8(new FileInputStream(metadata)));
        closeInterpreter();
        try (FileInputStream in = new FileInputStream(tflite);
             FileChannel channel = in.getChannel()) {
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            interpreter = new Interpreter(buffer);
        }
        centroids.clear();
        linearCoefficients = null;
        linearIntercepts = null;
        source = modelSource;
        activeModelId = id;
    }

    private void parseMetadata(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        labels = stringArray(root.getJSONArray("labels"));
        means = array(root.getJSONArray("means"));
        stds = array(root.getJSONArray("stds"));
        normalizeStds();
    }

    private void parseJsonModel(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        String type = root.optString("type", "centroid");
        if ("linear".equals(type)) {
            parseLinear(root);
        } else {
            parseCentroid(root);
        }
    }

    private void parseCentroid(JSONObject root) throws Exception {
        means = array(root.getJSONArray("means"));
        stds = array(root.getJSONArray("stds"));
        labels = root.has("labels") ? stringArray(root.getJSONArray("labels")) : DEFAULT_LABELS;
        normalizeStds();
        centroids.clear();
        linearCoefficients = null;
        linearIntercepts = null;
        JSONObject object = root.getJSONObject("centroids");
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            centroids.put(key, array(object.getJSONArray(key)));
        }
        addDerivedBurstingCentroid();
    }

    private void parseLinear(JSONObject root) throws Exception {
        means = array(root.getJSONArray("means"));
        stds = array(root.getJSONArray("stds"));
        labels = stringArray(root.getJSONArray("labels"));
        normalizeStds();
        centroids.clear();
        linearCoefficients = matrix(root.getJSONArray("coefficients"));
        linearIntercepts = array(root.getJSONArray("intercepts"));
    }

    private void normalizeStds() {
        for (int i = 0; i < stds.length; i++) {
            if (Math.abs(stds[i]) < 1e-9) {
                stds[i] = 1.0;
            }
        }
    }

    private void setFallback() {
        means = new double[]{0.055, 0.42, 0.145, 1230, 0.22, 0.51, 0.27, 0.62};
        stds = new double[]{0.035, 0.20, 0.070, 620, 0.13, 0.16, 0.13, 0.24};
        labels = DEFAULT_LABELS;
        centroids.clear();
        linearCoefficients = null;
        linearIntercepts = null;
        centroids.put("unripe", new double[]{0.05, 0.36, 0.18, 1650, 0.13, 0.46, 0.41, 0.45});
        centroids.put("ripe", new double[]{0.075, 0.52, 0.11, 950, 0.34, 0.53, 0.13, 0.74});
        centroids.put("bursting", new double[]{0.052, 0.377, 0.0905, 755, 0.431, 0.446, 0.124, 0.818});
        centroids.put("overripe", new double[]{0.04, 0.30, 0.08, 650, 0.48, 0.40, 0.12, 0.86});
        activeModelId = BUILTIN_MODEL_ID;
        source = "内置兜底模型";
    }

    private void addDerivedBurstingCentroid() {
        if (centroids.containsKey("bursting")) {
            return;
        }
        double[] acceptable = centroids.containsKey("acceptable") ? centroids.get("acceptable") : centroids.get("ripe");
        double[] overripe = centroids.get("overripe");
        if (acceptable == null || overripe == null) {
            return;
        }
        int size = Math.min(acceptable.length, overripe.length);
        double[] bursting = new double[size];
        for (int i = 0; i < size; i++) {
            bursting[i] = acceptable[i] * (1.0 - BURSTING_OVER_RIPE_WEIGHT)
                    + overripe[i] * BURSTING_OVER_RIPE_WEIGHT;
        }
        centroids.put("bursting", bursting);
    }

    private Prediction adaptMissingBursting(Prediction prediction, AudioFeatures features) {
        if (hasLabel("bursting") || "bursting".equals(prediction.label) || "unripe".equals(prediction.label)) {
            return prediction;
        }
        if (("ripe".equals(prediction.label) || "acceptable".equals(prediction.label) || "overripe".equals(prediction.label))
                && isBurstingSweetSpot(features)
                && !isClearlyOverripe(features)) {
            double confidence = Math.max(0.35, Math.min(0.92, prediction.confidence * 0.92));
            return new Prediction("bursting", confidence, describe("bursting"), prediction.modelSource);
        }
        return prediction;
    }

    private boolean hasLabel(String label) {
        for (String value : labels) {
            if (label.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBurstingSweetSpot(AudioFeatures f) {
        return f.centroid < 980
                && f.lowRatio > 0.38
                && f.highRatio < 0.18
                && f.decay > 0.72
                && (f.peak >= 0.25 || f.rms >= 0.030);
    }

    private static boolean isClearlyOverripe(AudioFeatures f) {
        return f.centroid < 620
                && f.lowRatio > 0.70
                && f.highRatio < 0.08
                && f.decay > 0.84
                && f.peak < 0.25
                && f.rms < 0.030;
    }

    private void closeInterpreter() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }

    private static File modelRoot(Context context) {
        File base = context.getExternalFilesDir(null);
        return new File(base, MODEL_DIR);
    }

    private static File createModelDir(Context context, String originalName) throws Exception {
        File root = modelRoot(context);
        if (!root.exists() && !root.mkdirs()) {
            throw new IllegalStateException("无法创建模型目录");
        }
        String id = System.currentTimeMillis() + "-" + safeName(cleanModelName(originalName));
        File dir = new File(root, id);
        if (!dir.mkdirs()) {
            throw new IllegalStateException("无法保存模型包");
        }
        return dir;
    }

    private static String modelName(File dir) {
        File info = new File(dir, "model_info.json");
        if (info.exists()) {
            try {
                JSONObject object = new JSONObject(readUtf8(new FileInputStream(info)));
                String name = object.optString("name", "");
                if (!name.isEmpty()) {
                    return name;
                }
            } catch (Exception ignored) {
            }
        }
        String name = dir.getName();
        int dash = name.indexOf('-');
        return dash >= 0 && dash + 1 < name.length() ? name.substring(dash + 1) : name;
    }

    private static void writeModelInfo(File dir, String name) throws Exception {
        JSONObject object = new JSONObject();
        object.put("name", name);
        object.put("imported_at", System.currentTimeMillis());
        writeBytes(new File(dir, "model_info.json"), object.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteRecursive(File file) throws Exception {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        if (!file.delete()) {
            throw new IllegalStateException("无法删除模型文件");
        }
    }

    private static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
        }
        String path = uri.getLastPathSegment();
        return path == null || path.trim().isEmpty() ? "模型包" : path;
    }

    private static String cleanModelName(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.endsWith(".zip")) {
            clean = clean.substring(0, clean.length() - 4);
        } else if (clean.endsWith(".json")) {
            clean = clean.substring(0, clean.length() - 5);
        }
        return clean.isEmpty() ? "模型包" : clean;
    }

    private static String safeName(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9._-]+", "_");
        return safe.isEmpty() ? "model" : safe;
    }

    private static double[] array(JSONArray values) throws Exception {
        double[] out = new double[values.length()];
        for (int i = 0; i < values.length(); i++) {
            out[i] = values.getDouble(i);
        }
        return out;
    }

    private static double[][] matrix(JSONArray rows) throws Exception {
        double[][] out = new double[rows.length()][];
        for (int i = 0; i < rows.length(); i++) {
            out[i] = array(rows.getJSONArray(i));
        }
        return out;
    }

    private static String[] stringArray(JSONArray values) throws Exception {
        String[] out = new String[values.length()];
        for (int i = 0; i < values.length(); i++) {
            out[i] = values.getString(i);
        }
        return out;
    }

    private static boolean isZip(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K';
    }

    private static String readUtf8(InputStream in) throws Exception {
        return new String(readAll(in), StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void writeBytes(File out, byte[] bytes) throws Exception {
        try (FileOutputStream file = new FileOutputStream(out)) {
            file.write(bytes);
        }
    }

    private static String describe(String label) {
        switch (label) {
            case "ripe":
                return "成熟：声音低沉、共振较集中，建议结合多次敲击结果确认。";
            case "bursting":
                return "甘甜：位于尚可和过熟之间，更接近过熟，代表当前模型认为甜度最佳的区间。";
            case "overripe":
                return "过熟/异常：低频或衰减特征偏强，可能偏软、空心或过熟。";
            case "unripe":
                return "偏生：高频比例或频谱重心偏高，可能还不够成熟。";
            default:
                return String.format(Locale.US, "未知类别：%s", label);
        }
    }
}
