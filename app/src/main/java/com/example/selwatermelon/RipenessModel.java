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
    private static final String[] DEFAULT_LABELS = {"unripe", "ripe", "overripe"};

    private final Map<String, double[]> centroids = new LinkedHashMap<>();
    private Interpreter interpreter;
    private String[] labels = DEFAULT_LABELS;
    private double[] means;
    private double[] stds;
    private String activeModelId = BUILTIN_MODEL_ID;
    private String source = "内置 JSON 质心模型";

    static final class ModelEntry {
        final String id;
        final String name;

        ModelEntry(String id, String name) {
            this.id = id;
            this.name = name;
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
                    entries.add(new ModelEntry(dir.getName(), modelName(dir)));
                }
            }
        }

        File base = context.getExternalFilesDir(null);
        if (base != null) {
            if (new File(base, "model.tflite").exists() && new File(base, "model_metadata.json").exists()) {
                entries.add(new ModelEntry(LEGACY_TFLITE_ID, "旧版上传 MLP TFLite 模型"));
            }
            if (new File(base, "model.json").exists()) {
                entries.add(new ModelEntry(LEGACY_JSON_ID, "旧版上传 JSON 质心模型"));
            }
        }
        entries.add(new ModelEntry(BUILTIN_MODEL_ID, "内置 JSON 质心模型"));
        return entries;
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
                parseCentroid(readUtf8(in));
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
            parseCentroid(readUtf8(new FileInputStream(new File(base, "model.json"))));
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
            parseCentroid(readUtf8(new FileInputStream(json)));
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
        return new Prediction(label, output[0][bestIndex], describe(label), source);
    }

    private Prediction classic(AudioFeatures f) {
        String label;
        if (f.centroid < 760 && f.lowRatio > 0.42 && f.decay > 0.78) {
            label = "overripe";
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
        return new ModelEntry(dir.getName(), modelName(dir));
    }

    private ModelEntry importJson(Context context, byte[] bytes, String originalName) throws Exception {
        String json = new String(bytes, StandardCharsets.UTF_8);
        parseCentroid(json);
        File dir = createModelDir(context, originalName);
        writeBytes(new File(dir, "model.json"), bytes);
        writeModelInfo(dir, cleanModelName(originalName));
        closeInterpreter();
        source = modelName(dir);
        activeModelId = dir.getName();
        saveActiveModel(context);
        return new ModelEntry(dir.getName(), modelName(dir));
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

    private void parseCentroid(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        means = array(root.getJSONArray("means"));
        stds = array(root.getJSONArray("stds"));
        labels = root.has("labels") ? stringArray(root.getJSONArray("labels")) : DEFAULT_LABELS;
        normalizeStds();
        centroids.clear();
        JSONObject object = root.getJSONObject("centroids");
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            centroids.put(key, array(object.getJSONArray(key)));
        }
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
        centroids.put("unripe", new double[]{0.05, 0.36, 0.18, 1650, 0.13, 0.46, 0.41, 0.45});
        centroids.put("ripe", new double[]{0.075, 0.52, 0.11, 950, 0.34, 0.53, 0.13, 0.74});
        centroids.put("overripe", new double[]{0.04, 0.30, 0.08, 650, 0.48, 0.40, 0.12, 0.86});
        activeModelId = BUILTIN_MODEL_ID;
        source = "内置兜底模型";
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
            case "overripe":
                return "过熟/异常：低频或衰减特征偏强，可能偏软、空心或过熟。";
            case "unripe":
                return "偏生：高频比例或频谱重心偏高，可能还不够成熟。";
            default:
                return String.format(Locale.US, "未知类别：%s", label);
        }
    }
}
