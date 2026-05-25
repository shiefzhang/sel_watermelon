package com.example.selwatermelon;

import android.content.Context;
import android.net.Uri;

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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class RipenessModel {
    private static final String[] DEFAULT_LABELS = {"unripe", "ripe", "overripe"};

    private final Map<String, double[]> centroids = new LinkedHashMap<>();
    private Interpreter interpreter;
    private String[] labels = DEFAULT_LABELS;
    private double[] means;
    private double[] stds;
    private String source = "内置示例模型";

    static RipenessModel load(Context context) {
        RipenessModel model = new RipenessModel();
        File dir = context.getExternalFilesDir(null);
        File tflite = new File(dir, "model.tflite");
        File metadata = new File(dir, "model_metadata.json");
        if (tflite.exists() && metadata.exists()) {
            try {
                model.loadTflite(tflite, metadata, "已上传 MLP TFLite 模型");
                return model;
            } catch (Exception ignored) {
                model.closeInterpreter();
            }
        }

        File custom = new File(dir, "model.json");
        try (InputStream in = custom.exists()
                ? new FileInputStream(custom)
                : context.getAssets().open("model.json")) {
            model.source = custom.exists() ? "已上传 JSON 质心模型" : "内置 JSON 质心模型";
            model.parseCentroid(readUtf8(in));
        } catch (Exception ignored) {
            model.setFallback();
        }
        return model;
    }

    void importFromUri(Context context, Uri uri) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IllegalArgumentException("无法读取模型文件");
            }
            byte[] bytes = readAll(in);
            if (isZip(bytes)) {
                importBundle(context, bytes);
            } else {
                String json = new String(bytes, StandardCharsets.UTF_8);
                parseCentroid(json);
                File dir = context.getExternalFilesDir(null);
                File out = new File(dir, "model.json");
                writeBytes(out, bytes);
                new File(dir, "model.tflite").delete();
                new File(dir, "model_metadata.json").delete();
                closeInterpreter();
                source = "已上传 JSON 质心模型";
            }
        }
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

    private void importBundle(Context context, byte[] bytes) throws Exception {
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
        File dir = context.getExternalFilesDir(null);
        File tflite = new File(dir, "model.tflite");
        File metadata = new File(dir, "model_metadata.json");
        writeBytes(tflite, modelBytes);
        writeBytes(metadata, metadataBytes);
        loadTflite(tflite, metadata, "已上传 MLP TFLite 模型");
    }

    private void loadTflite(File tflite, File metadata, String modelSource) throws Exception {
        parseMetadata(readUtf8(new FileInputStream(metadata)));
        closeInterpreter();
        try (FileInputStream in = new FileInputStream(tflite);
             FileChannel channel = in.getChannel()) {
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            interpreter = new Interpreter(buffer);
        }
        centroids.clear();
        source = modelSource;
    }

    private void parseMetadata(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        labels = stringArray(root.getJSONArray("labels"));
        means = array(root.getJSONArray("means"));
        stds = array(root.getJSONArray("stds"));
        for (int i = 0; i < stds.length; i++) {
            if (Math.abs(stds[i]) < 1e-9) {
                stds[i] = 1.0;
            }
        }
    }

    private void parseCentroid(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        means = array(root.getJSONArray("means"));
        stds = array(root.getJSONArray("stds"));
        labels = root.has("labels") ? stringArray(root.getJSONArray("labels")) : DEFAULT_LABELS;
        for (int i = 0; i < stds.length; i++) {
            if (Math.abs(stds[i]) < 1e-9) {
                stds[i] = 1.0;
            }
        }
        centroids.clear();
        JSONObject object = root.getJSONObject("centroids");
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            centroids.put(key, array(object.getJSONArray(key)));
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
        source = "内置兜底模型";
    }

    private void closeInterpreter() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
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
