package com.example.selwatermelon;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class DatasetStore {
    private final Context context;
    private final File root;
    private final File samplesDir;
    private final File metadataFile;

    DatasetStore(Context context) {
        this.context = context.getApplicationContext();
        root = new File(context.getExternalFilesDir(null), "dataset");
        samplesDir = new File(root, "samples");
        metadataFile = new File(root, "metadata.jsonl");
        samplesDir.mkdirs();
    }

    String newAudioId() {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        return "tap_" + stamp + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    File wavFileFor(String audioId) {
        return new File(samplesDir, audioId + ".wav");
    }

    void append(RecordingSession session) throws Exception {
        List<JSONObject> rows = readRows();
        rows.add(toJson(session));
        writeRows(rows);
    }

    void updateFeedback(String audioId, String feedback, int weightKg) throws Exception {
        List<JSONObject> rows = readRows();
        for (JSONObject row : rows) {
            if (audioId.equals(row.optString("audio_id"))) {
                row.put("user_label", feedback);
                row.put("feedback", feedback);
                row.remove("weight_jin");
                row.put("weight_kg", weightKg);
            }
        }
        writeRows(rows);
    }

    void delete(List<RecordingItem> items) throws Exception {
        Set<String> ids = new HashSet<>();
        for (RecordingItem item : items) {
            ids.add(item.audioId);
            if (item.wavFile.exists()) {
                item.wavFile.delete();
            }
        }
        List<JSONObject> kept = new ArrayList<>();
        for (JSONObject row : readRows()) {
            if (!ids.contains(row.optString("audio_id"))) {
                kept.add(row);
            }
        }
        writeRows(kept);
    }

    Uri exportZip() throws Exception {
        return exportZip(Collections.emptySet());
    }

    Uri exportSelected(Set<String> selectedIds) throws Exception {
        return exportZip(selectedIds);
    }

    List<RecordingItem> listRecordings() throws Exception {
        List<RecordingItem> items = new ArrayList<>();
        Set<String> knownFiles = new HashSet<>();
        for (JSONObject row : readRows()) {
            String fileName = new File(row.optString("file")).getName();
            File wav = new File(samplesDir, fileName);
            if (!wav.exists()) {
                continue;
            }
            knownFiles.add(fileName);
            String audioId = row.optString("audio_id", fileName.replace(".wav", ""));
            String feedback = row.optString("feedback", row.optString("user_label", ""));
            items.add(new RecordingItem(
                    audioId,
                    wav,
                    row.optLong("created_at", wav.lastModified()),
                    row.optLong("duration_ms", 0),
                    row.optString("model_label", ""),
                    row.optDouble("model_confidence", 0),
                    row.optString("model_source", ""),
                    featureText(row),
                    row.optString("participant_id", ""),
                    row.optString("participant_name", ""),
                    feedback,
                    row.optInt("weight_kg", 0)
            ));
        }

        File[] wavs = samplesDir.listFiles((dir, name) -> name.endsWith(".wav"));
        if (wavs != null) {
            for (File wav : wavs) {
                if (knownFiles.contains(wav.getName())) {
                    continue;
                }
                String audioId = wav.getName().replace(".wav", "");
                items.add(new RecordingItem(audioId, wav, wav.lastModified(), 0, "", 0, "", "", "", "", "", 0));
            }
        }
        items.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return items;
    }

    int count() {
        File[] wavs = samplesDir.listFiles((dir, name) -> name.endsWith(".wav"));
        return wavs == null ? 0 : wavs.length;
    }

    File getRoot() {
        return root;
    }

    private Uri exportZip(Set<String> selectedIds) throws Exception {
        root.mkdirs();
        File zip = new File(context.getExternalFilesDir(null), selectedIds.isEmpty()
                ? "watermelon_dataset.zip"
                : "watermelon_selected_audio.zip");
        Set<String> includedFiles = new HashSet<>();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            List<JSONObject> exportRows = new ArrayList<>();
            for (JSONObject row : readRows()) {
                String audioId = row.optString("audio_id");
                if (!selectedIds.isEmpty() && !selectedIds.contains(audioId)) {
                    continue;
                }
                String fileName = new File(row.optString("file")).getName();
                File wav = new File(samplesDir, fileName);
                if (wav.exists()) {
                    exportRows.add(withPhoneInfo(row));
                    includedFiles.add(fileName);
                    add(out, wav, "samples/" + fileName);
                }
            }
            File[] wavs = samplesDir.listFiles((dir, name) -> name.endsWith(".wav"));
            if (wavs != null) {
                for (File wav : wavs) {
                    String audioId = wav.getName().replace(".wav", "");
                    boolean selected = selectedIds.isEmpty() || selectedIds.contains(audioId);
                    if (selected && !includedFiles.contains(wav.getName())) {
                        add(out, wav, "samples/" + wav.getName());
                    }
                }
            }
            addText(out, "metadata.jsonl", rowsToText(exportRows));
        }
        return FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", zip);
    }

    private JSONObject toJson(RecordingSession session) throws Exception {
        JSONObject object = new JSONObject();
        object.put("audio_id", session.audioId);
        object.put("participant_id", session.participantId);
        object.put("participant_name", session.participantName);
        addPhoneInfo(object);
        object.put("file", "samples/" + session.wavFile.getName());
        object.put("created_at", session.createdAt);
        object.put("created_at_text", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(session.createdAt)));
        object.put("duration_ms", session.durationMs);
        object.put("sample_rate", WatermelonRecorder.SAMPLE_RATE);
        object.put("model_label", session.prediction.label);
        object.put("model_confidence", session.prediction.confidence);
        object.put("model_source", session.prediction.modelSource);
        object.put("user_label", session.userLabel);
        object.put("feedback", session.userLabel);
        object.put("weight_kg", session.weightKg);
        double[] values = session.features.toArray();
        for (int i = 0; i < AudioFeatures.NAMES.length; i++) {
            object.put(AudioFeatures.NAMES[i], values[i]);
        }
        return object;
    }

    private static JSONObject withPhoneInfo(JSONObject row) throws Exception {
        JSONObject copy = new JSONObject(row.toString());
        addPhoneInfo(copy);
        return copy;
    }

    private static void addPhoneInfo(JSONObject object) throws Exception {
        object.put("phone_manufacturer", Build.MANUFACTURER);
        object.put("phone_model", Build.MODEL);
        object.put("phone_device", Build.DEVICE);
        object.put("phone_label", Build.MANUFACTURER + " " + Build.MODEL);
    }

    private static String featureText(JSONObject row) {
        return String.format(Locale.US,
                "RMS %.3f  Peak %.2f\nZCR %.3f  Centroid %.0f Hz\nLow %.0f%%  Mid %.0f%%  High %.0f%%  Decay %.2f\nMFCC1 %.2f  MFCC2 %.2f  MFCC3 %.2f",
                row.optDouble("rms", 0),
                row.optDouble("peak", 0),
                row.optDouble("zcr", 0),
                row.optDouble("centroid", 0),
                row.optDouble("low_ratio", 0) * 100,
                row.optDouble("mid_ratio", 0) * 100,
                row.optDouble("high_ratio", 0) * 100,
                row.optDouble("decay", 0),
                row.optDouble("mfcc_1_mean", 0),
                row.optDouble("mfcc_2_mean", 0),
                row.optDouble("mfcc_3_mean", 0));
    }

    private List<JSONObject> readRows() throws Exception {
        List<JSONObject> rows = new ArrayList<>();
        if (!metadataFile.exists()) {
            return rows;
        }
        String text = readUtf8(metadataFile);
        for (String line : text.split("\\R")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            rows.add(new JSONObject(line));
        }
        return rows;
    }

    private void writeRows(List<JSONObject> rows) throws Exception {
        root.mkdirs();
        try (FileOutputStream out = new FileOutputStream(metadataFile, false)) {
            out.write(rowsToText(rows).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String rowsToText(List<JSONObject> rows) {
        StringBuilder builder = new StringBuilder();
        for (JSONObject row : rows) {
            builder.append(row).append('\n');
        }
        return builder.toString();
    }

    private static String readUtf8(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void add(ZipOutputStream out, File file, String name) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        out.closeEntry();
    }

    private static void addText(ZipOutputStream out, String name, String text) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
