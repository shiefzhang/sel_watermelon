package com.example.selwatermelon;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQUEST_MODEL_FILE = 40;
    private static final int RECORD_COLOR = 0xFF1B7F45;
    private static final int AUDIO_COLOR = 0xFF2367B2;
    private static final String[] FEEDBACK_LABELS = {"偏生", "尚可", "爆表", "过熟"};
    private static final String[] FEEDBACK_VALUES = {"unripe", "acceptable", "bursting", "overripe"};

    private final WatermelonRecorder recorder = new WatermelonRecorder();
    private final Set<String> selectedAudioIds = new HashSet<>();
    private DatasetStore datasetStore;
    private RipenessModel model;
    private RecordingSession currentSession;
    private SharedPreferences preferences;
    private MediaPlayer mediaPlayer;

    private TextView statusText;
    private TextView resultText;
    private TextView featureText;
    private TextView listSummaryText;
    private RadioGroup labelGroup;
    private Spinner feedbackWeightSpinner;
    private Button recordButton;
    private Button saveFeedbackButton;
    private Button recordTabButton;
    private Button listTabButton;
    private Button downloadSelectedButton;
    private Button deleteSelectedButton;
    private CheckBox selectAllCheckBox;
    private WaveformView waveformView;
    private LinearLayout recordPage;
    private LinearLayout listPage;
    private LinearLayout audioList;
    private List<RecordingItem> visibleItems = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("collector", MODE_PRIVATE);
        ensureParticipant();
        datasetStore = new DatasetStore(this);
        model = RipenessModel.load(this);
        setContentView(buildUi());
        refreshStatus("准备录音。靠近西瓜敲击 1-3 次，录音时会显示实时音频图形。");
        refreshAudioList();
        showTab(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        root.setBackgroundColor(0xFFF2F7F0);
        scroll.addView(root);

        TextView title = text("听瓜", 30, 0xFF12351F, true);
        root.addView(title);
        TextView subtitle = text("闻声识瓜韵，挑瓜不用猜", 18, 0xFF188A45, true);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(dp(14), dp(10), dp(14), dp(10));
        subtitle.setBackground(boxBackground(0xFFE7F8EC, 0xFF188A45));
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.setMargins(0, dp(8), 0, dp(12));
        subtitle.setLayoutParams(subtitleParams);
        root.addView(subtitle);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, 0, 0, dp(8));
        recordTabButton = compactButton("挑瓜");
        listTabButton = compactButton("列表");
        recordTabButton.setOnClickListener(v -> showTab(true));
        listTabButton.setOnClickListener(v -> showTab(false));
        tabs.addView(recordTabButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        tabs.addView(listTabButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        root.addView(tabs);

        statusText = panelText();
        statusText.setGravity(Gravity.CENTER);
        statusText.setTextSize(18);
        statusText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        statusText.setTextColor(RECORD_COLOR);
        statusText.setBackground(boxBackground(0xFFE7F8EC, RECORD_COLOR));
        root.addView(statusText);

        recordPage = new LinearLayout(this);
        recordPage.setOrientation(LinearLayout.VERTICAL);
        buildRecordPage(recordPage);
        root.addView(recordPage);

        listPage = new LinearLayout(this);
        listPage.setOrientation(LinearLayout.VERTICAL);
        buildListPage(listPage);
        root.addView(listPage);

        return scroll;
    }

    private void buildRecordPage(LinearLayout root) {
        waveformView = new WaveformView(this);
        LinearLayout.LayoutParams waveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(118)
        );
        waveParams.setMargins(0, dp(8), 0, dp(8));
        waveformView.setLayoutParams(waveParams);
        waveformView.setBackgroundColor(0xFFFFFFFF);
        root.addView(waveformView);

        recordButton = primaryButton("开始录音");
        recordButton.setOnClickListener(v -> toggleRecording());
        root.addView(recordButton);

        resultText = panelText();
        resultText.setText("暂无判断结果");
        root.addView(resultText);

        featureText = panelText();
        featureText.setTextSize(12);
        featureText.setText("录音后显示 MFCC 和声学特征");
        root.addView(featureText);

        LinearLayout feedbackPanel = panel();
        LinearLayout feedbackRow = rowLayout();
        feedbackRow.addView(sectionPill("实际口感"), new LinearLayout.LayoutParams(
                dp(94),
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        labelGroup = new RadioGroup(this);
        labelGroup.setOrientation(RadioGroup.HORIZONTAL);
        labelGroup.setPadding(dp(12), 0, 0, 0);
        for (int i = 0; i < FEEDBACK_LABELS.length; i++) {
            addRadio(labelGroup, FEEDBACK_LABELS[i], FEEDBACK_VALUES[i]);
        }
        feedbackRow.addView(labelGroup, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        ));
        feedbackPanel.addView(feedbackRow);

        LinearLayout weightRow = rowLayout();
        weightRow.setPadding(0, dp(4), 0, dp(4));
        weightRow.addView(sectionPill("重量"), new LinearLayout.LayoutParams(
                dp(94),
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        feedbackWeightSpinner = weightSpinner(0, false);
        weightRow.addView(feedbackWeightSpinner, new LinearLayout.LayoutParams(
                dp(150),
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        feedbackPanel.addView(weightRow);

        saveFeedbackButton = secondaryButton("保存实际口感");
        saveFeedbackButton.setEnabled(false);
        saveFeedbackButton.setOnClickListener(v -> saveFeedback());
        feedbackPanel.addView(saveFeedbackButton);
        root.addView(feedbackPanel);

        Button importModelButton = secondaryButton("上传训练好的 TFLite 模型包");
        importModelButton.setOnClickListener(v -> pickModelFile());
        root.addView(importModelButton);
    }

    private void buildListPage(LinearLayout root) {
        LinearLayout listPanel = panel();
        listSummaryText = text("", 13, 0xFF66756B, false);
        listPanel.addView(listSummaryText);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        downloadSelectedButton = compactButton("下载所选");
        downloadSelectedButton.setOnClickListener(v -> exportSelectedAudio());
        deleteSelectedButton = compactButton("删除所选");
        deleteSelectedButton.setOnClickListener(v -> deleteSelectedAudio());
        actions.addView(downloadSelectedButton, new LinearLayout.LayoutParams(0, dp(42), 1));
        actions.addView(deleteSelectedButton, new LinearLayout.LayoutParams(0, dp(42), 1));
        listPanel.addView(actions);

        audioList = new LinearLayout(this);
        audioList.setOrientation(LinearLayout.VERTICAL);
        listPanel.addView(audioList);
        root.addView(listPanel);

        TextView tip = text("为不断优化模型准确度，您可发送数据给作者，用于模型训练与升级。mail: zhangxuefeng@batonsoft.com", 13, 0xFF66756B, false);
        tip.setPadding(0, dp(8), 0, 0);
        root.addView(tip);
    }

    private void showTab(boolean recordingTab) {
        recordPage.setVisibility(recordingTab ? View.VISIBLE : View.GONE);
        listPage.setVisibility(recordingTab ? View.GONE : View.VISIBLE);
        styleTab(recordTabButton, recordingTab);
        styleTab(listTabButton, !recordingTab);
        if (!recordingTab) {
            refreshAudioList();
            refreshStatus("在西瓜侧边拍打3下");
        }
    }

    private void styleTab(Button button, boolean selected) {
        int color = button == listTabButton ? AUDIO_COLOR : RECORD_COLOR;
        button.setTextColor(selected ? 0xFFFFFFFF : color);
        button.setBackgroundColor(selected ? color : 0xFFFFFFFF);
    }

    private void toggleRecording() {
        if (recorder.isRecording()) {
            recorder.stop();
            recordButton.setEnabled(false);
            recordButton.setText("正在分析...");
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 7);
            return;
        }
        try {
            currentSession = null;
            saveFeedbackButton.setEnabled(false);
            labelGroup.clearCheck();
            feedbackWeightSpinner.setSelection(weightPosition(0, false));
            waveformView.reset();
            refreshStatus("正在录音，敲击西瓜 1-3 次后点停止，最多自动录制 " + WatermelonRecorder.MAX_SECONDS + " 秒。");
            recordButton.setText("停止录音并分析");
            recorder.start(new WatermelonRecorder.Callback() {
                @Override
                public void onFinished(short[] samples) {
                    runOnUiThread(() -> handleRecording(samples));
                }

                @Override
                public void onAudioFrame(short[] samples) {
                    runOnUiThread(() -> waveformView.addSamples(samples));
                }

                @Override
                public void onError(Exception error) {
                    runOnUiThread(() -> {
                        refreshStatus("录音失败：" + error.getMessage());
                        recordButton.setText("开始录音");
                        recordButton.setEnabled(true);
                    });
                }
            });
        } catch (SecurityException e) {
            refreshStatus("没有录音权限。");
        }
    }

    private void handleRecording(short[] samples) {
        try {
            String audioId = datasetStore.newAudioId();
            File wav = datasetStore.wavFileFor(audioId);
            WavUtil.writeMono16(wav, samples, WatermelonRecorder.SAMPLE_RATE);
            AudioFeatures features = AudioFeatures.fromPcm(samples, WatermelonRecorder.SAMPLE_RATE);
            Prediction prediction = model.predict(features);
            long durationMs = samples.length * 1000L / WatermelonRecorder.SAMPLE_RATE;
            currentSession = new RecordingSession(audioId, wav, features, prediction, System.currentTimeMillis(), durationMs);
            currentSession.participantId = participantId();
            currentSession.participantName = participantName();
            currentSession.weightKg = selectedWeight(feedbackWeightSpinner);
            datasetStore.append(currentSession);
            resultText.setText(aiResultText(prediction));
            featureText.setText(features.shortText());
            saveFeedbackButton.setEnabled(true);
            refreshAudioList();
        } catch (Exception e) {
            refreshStatus("分析失败：" + e.getMessage());
        } finally {
            recordButton.setText("开始录音");
            recordButton.setEnabled(true);
        }
    }

    private void saveFeedback() {
        if (currentSession == null) {
            return;
        }
        String feedback = checkedFeedback(labelGroup);
        if (feedback.isEmpty()) {
            Toast.makeText(this, "请选择偏生、尚可、爆表或过熟", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            currentSession.userLabel = feedback;
            currentSession.weightKg = selectedWeight(feedbackWeightSpinner);
            datasetStore.updateFeedback(currentSession.audioId, feedback, currentSession.weightKg);
            saveFeedbackButton.setEnabled(false);
            refreshAudioList();
        } catch (Exception e) {
            refreshStatus("保存反馈失败：" + e.getMessage());
        }
    }

    private void refreshAudioList() {
        audioList.removeAllViews();
        try {
            visibleItems = datasetStore.listRecordings();
            Set<String> visibleIds = new HashSet<>();
            for (RecordingItem item : visibleItems) {
                visibleIds.add(item.audioId);
            }
            selectedAudioIds.retainAll(visibleIds);
            listSummaryText.setText("共 " + visibleItems.size() + " 条录音，可多选下载，也可点击时间查看详情。");
            audioList.addView(listHeader());
            for (RecordingItem item : visibleItems) {
                audioList.addView(audioRow(item));
            }
        } catch (Exception e) {
            listSummaryText.setText("读取音频列表失败：" + e.getMessage());
        }
        updateSelectionActions();
    }

    private View listHeader() {
        LinearLayout row = rowLayout();
        row.setPadding(0, dp(8), 0, dp(4));
        selectAllCheckBox = new CheckBox(this);
        boolean allSelected = !visibleItems.isEmpty() && selectedAudioIds.size() == visibleItems.size();
        selectAllCheckBox.setChecked(allSelected);
        selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            selectedAudioIds.clear();
            if (isChecked) {
                for (RecordingItem item : visibleItems) {
                    selectedAudioIds.add(item.audioId);
                }
            }
            refreshAudioList();
        });
        row.addView(selectAllCheckBox, new LinearLayout.LayoutParams(dp(40), dp(40)));
        row.addView(text("采样时间", 12, 0xFF66756B, true), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f));
        TextView aiHeader = text("AI挑瓜", 12, 0xFF66756B, true);
        aiHeader.setPadding(dp(12), 0, 0, 0);
        row.addView(aiHeader, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f));
        row.addView(text("实际口感", 12, 0xFF66756B, true), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(text("重量", 12, 0xFF66756B, true), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f));
        return row;
    }

    private View audioRow(RecordingItem item) {
        LinearLayout row = rowLayout();
        row.setPadding(0, dp(8), 0, dp(8));
        row.setBackgroundColor(0xFFFFFFFF);

        CheckBox checkBox = new CheckBox(this);
        checkBox.setChecked(selectedAudioIds.contains(item.audioId));
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                selectedAudioIds.add(item.audioId);
            } else {
                selectedAudioIds.remove(item.audioId);
            }
            updateSelectionActions();
        });
        row.addView(checkBox, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView time = text(shortTime(item.createdAt), 15, AUDIO_COLOR, true);
        time.setGravity(Gravity.CENTER_VERTICAL);
        time.setOnClickListener(v -> showAudioDetails(item));
        row.addView(time, new LinearLayout.LayoutParams(0, dp(40), 1.2f));

        TextView modelText = text(ripenessText(item.modelLabel), 14, ripenessColor(item.modelLabel), true);
        modelText.setGravity(Gravity.CENTER_VERTICAL);
        modelText.setPadding(dp(12), 0, 0, 0);
        row.addView(modelText, new LinearLayout.LayoutParams(0, dp(40), 0.9f));

        TextView feedbackText = text(feedbackText(item.feedback), 14, feedbackColor(item.feedback), true);
        feedbackText.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(feedbackText, new LinearLayout.LayoutParams(0, dp(40), 1f));

        TextView weightText = text(listWeightText(item.weightKg), 13, 0xFF223128, false);
        weightText.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(weightText, new LinearLayout.LayoutParams(0, dp(40), 0.8f));
        return row;
    }

    private void showAudioDetails(RecordingItem item) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(10), dp(12), dp(12));
        content.setBackground(boxBackground(0xFFF7FBF7, 0xFFE1E9E3));

        LinearLayout titleRow = rowLayout();
        TextView title = text(shortTime(item.createdAt) + "  时长 " + formatDuration(item.durationMs), 18, 0xFF12351F, true);
        Button closeButton = compactButton("×");
        closeButton.setTextSize(20);
        closeButton.setTextColor(0xFFFFFFFF);
        closeButton.setBackground(boxBackground(0xFFB33A3A, 0xFFB33A3A));
        titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        titleRow.addView(closeButton, new LinearLayout.LayoutParams(dp(48), dp(44)));
        content.addView(titleRow);

        content.addView(aiPickBlock(item));

        LinearLayout table = new LinearLayout(this);
        table.setOrientation(LinearLayout.VERTICAL);
        table.setBackground(boxBackground(0xFFFFFFFF, 0xFFE1E9E3));
        table.addView(tableRow("采样时间", fullTime(item.createdAt)));
        table.addView(tableRow("音频 ID", item.audioId));
        table.addView(tableRow("来源", item.modelSource.isEmpty() ? "不清楚" : item.modelSource));
        table.addView(tableRow("声音特征", item.featureText.isEmpty() ? "不清楚" : item.featureText));
        content.addView(table);

        LinearLayout feedbackRow = rowLayout();
        feedbackRow.setPadding(0, dp(12), 0, dp(4));
        TextView feedbackTitle = sectionPill("实际口感");
        feedbackRow.addView(feedbackTitle, new LinearLayout.LayoutParams(
                dp(94),
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout feedbackOptions = new LinearLayout(this);
        feedbackOptions.setOrientation(LinearLayout.VERTICAL);
        feedbackOptions.setPadding(dp(12), 0, 0, 0);
        List<RadioButton> feedbackButtons = new ArrayList<>();
        feedbackOptions.addView(feedbackOptionRow(item.feedback, feedbackButtons, 0, 2));
        feedbackOptions.addView(feedbackOptionRow(item.feedback, feedbackButtons, 2, 4));
        feedbackRow.addView(feedbackOptions, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        ));
        content.addView(feedbackRow);

        LinearLayout weightRow = rowLayout();
        weightRow.setPadding(0, dp(4), 0, dp(4));
        weightRow.addView(sectionPill("重量"), new LinearLayout.LayoutParams(
                dp(94),
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        Spinner weightSpinner = weightSpinner(item.weightKg, false);
        weightRow.addView(weightSpinner, new LinearLayout.LayoutParams(
                dp(150),
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        content.addView(weightRow);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button playButton = compactButton("播放");
        Button saveButton = primaryButton("保存");
        Button deleteButton = dangerButton("删除");
        actions.setPadding(0, dp(8), 0, 0);
        actions.addView(playButton, actionParams());
        actions.addView(saveButton, actionParams());
        actions.addView(deleteButton, actionParams());
        content.addView(actions);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(content)
                .create();

        closeButton.setOnClickListener(v -> dialog.dismiss());
        playButton.setOnClickListener(v -> playAudio(item));
        saveButton.setOnClickListener(v -> {
            String feedback = checkedFeedback(feedbackButtons);
            try {
                item.feedback = feedback;
                item.weightKg = selectedWeight(weightSpinner);
                datasetStore.updateFeedback(item.audioId, item.feedback, item.weightKg);
                refreshAudioList();
                dialog.dismiss();
            } catch (Exception e) {
                refreshStatus("更新失败：" + e.getMessage());
            }
        });
        deleteButton.setOnClickListener(v -> {
            deleteAudio(item);
            dialog.dismiss();
        });
        dialog.show();
    }

    private TextView detailLine(String label, String value) {
        TextView view = text(label + "：" + value, 14, 0xFF223128, false);
        view.setPadding(0, 0, 0, dp(6));
        return view;
    }

    private View tableRow(String label, String value) {
        LinearLayout row = rowLayout();
        row.setGravity(Gravity.TOP);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        TextView labelView = text(label, 13, 0xFF66756B, true);
        TextView valueView = text(value, 13, 0xFF223128, false);
        row.addView(labelView, new LinearLayout.LayoutParams(dp(86), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(valueView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private View aiPickBlock(RecordingItem item) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.HORIZONTAL);
        block.setGravity(Gravity.CENTER_VERTICAL);
        block.setPadding(dp(14), dp(10), dp(14), dp(10));
        block.setBackground(boxBackground(0xFFE7F5EA, RECORD_COLOR));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(10), 0, dp(4));
        block.setLayoutParams(params);

        TextView label = text("AI挑瓜", 18, 0xFFFFFFFF, true);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(8), dp(8), dp(8), dp(8));
        label.setBackground(boxBackground(RECORD_COLOR, RECORD_COLOR));

        LinearLayout resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        resultBox.setPadding(dp(12), 0, 0, 0);
        TextView result = text(ripenessText(item.modelLabel), 28, ripenessColor(item.modelLabel), true);
        TextView confidence = text("置信度：" + confidenceValue(item.modelConfidence), 12, 0xFF52645A, false);
        result.setGravity(Gravity.LEFT);
        confidence.setGravity(Gravity.LEFT);
        resultBox.addView(result);
        resultBox.addView(confidence);
        block.addView(label, new LinearLayout.LayoutParams(dp(94), LinearLayout.LayoutParams.WRAP_CONTENT));
        block.addView(resultBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return block;
    }

    private TextView sectionPill(String value) {
        TextView title = text(value, 18, 0xFFFFFFFF, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(8), dp(6), dp(8), dp(6));
        title.setBackground(boxBackground(RECORD_COLOR, RECORD_COLOR));
        return title;
    }

    private View feedbackOptionRow(String selectedFeedback, List<RadioButton> buttons, int start, int end) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = start; i < end; i++) {
            RadioButton button = new RadioButton(this);
            button.setText(FEEDBACK_LABELS[i]);
            button.setTextSize(15);
            button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            button.setTextColor(feedbackColor(FEEDBACK_VALUES[i]));
            button.setTag(FEEDBACK_VALUES[i]);
            button.setChecked(FEEDBACK_VALUES[i].equals(selectedFeedback));
            buttons.add(button);
            button.setOnCheckedChangeListener((view, isChecked) -> {
                if (!isChecked) {
                    return;
                }
                for (RadioButton other : buttons) {
                    if (other != view) {
                        other.setChecked(false);
                    }
                }
            });
            row.addView(button, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        return row;
    }

    private void updateSelectionActions() {
        int count = selectedAudioIds.size();
        downloadSelectedButton.setText(count == 0 ? "下载所选" : "下载所选(" + count + ")");
        deleteSelectedButton.setText(count == 0 ? "删除所选" : "删除所选(" + count + ")");
        downloadSelectedButton.setEnabled(count > 0);
        deleteSelectedButton.setEnabled(count > 0);
    }

    private void playAudio(RecordingItem item) {
        try {
            stopPlayback();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(item.wavFile.getAbsolutePath());
            mediaPlayer.setOnCompletionListener(mp -> stopPlayback());
            mediaPlayer.prepare();
            mediaPlayer.start();
            refreshStatus("正在播放：" + item.audioId);
        } catch (Exception e) {
            refreshStatus("播放失败：" + e.getMessage());
        }
    }

    private void stopPlayback() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void deleteAudio(RecordingItem item) {
        try {
            stopPlayback();
            List<RecordingItem> items = new ArrayList<>();
            items.add(item);
            datasetStore.delete(items);
            refreshAudioList();
            refreshStatus("已删除音频：" + item.audioId);
        } catch (Exception e) {
            refreshStatus("删除失败：" + e.getMessage());
        }
    }

    private void deleteSelectedAudio() {
        try {
            List<RecordingItem> deleteItems = new ArrayList<>();
            for (RecordingItem item : datasetStore.listRecordings()) {
                if (selectedAudioIds.contains(item.audioId)) {
                    deleteItems.add(item);
                }
            }
            datasetStore.delete(deleteItems);
            refreshAudioList();
            refreshStatus("已删除 " + deleteItems.size() + " 条音频。");
        } catch (Exception e) {
            refreshStatus("删除所选失败：" + e.getMessage());
        }
    }

    private void exportDataset() {
        try {
            shareZip(datasetStore.exportZip(), "导出 watermelon_dataset.zip");
        } catch (Exception e) {
            refreshStatus("导出失败：" + e.getMessage());
        }
    }

    private void exportSelectedAudio() {
        if (selectedAudioIds.isEmpty()) {
            return;
        }
        try {
            shareZip(datasetStore.exportSelected(new HashSet<>(selectedAudioIds)), "导出所选音频");
        } catch (Exception e) {
            refreshStatus("导出所选失败：" + e.getMessage());
        }
    }

    private void shareZip(Uri uri, String title) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, title));
    }

    private void onModelPicked(Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            model.importFromUri(this, uri);
            refreshStatus("模型已上传并启用。后续录音会优先使用 MLP TFLite，旧 JSON 模型仍可作为兜底。");
        } catch (Exception e) {
            refreshStatus("模型上传失败：" + e.getMessage());
        }
    }

    private void pickModelFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_MODEL_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MODEL_FILE && resultCode == RESULT_OK && data != null) {
            onModelPicked(data.getData());
        }
    }

    private void refreshStatus(String text) {
        statusText.setText(text);
    }

    private void ensureParticipant() {
        if (!preferences.contains("participant_id")) {
            String id = "person_" + UUID.randomUUID().toString();
            preferences.edit()
                    .putString("participant_id", id)
                    .putString("participant_name", "采集人-" + id.substring(id.length() - 4))
                    .apply();
        }
    }

    private String participantId() {
        return preferences.getString("participant_id", "");
    }

    private String participantName() {
        return preferences.getString("participant_name", "");
    }

    private RadioButton addRadio(RadioGroup group, String text, String tag) {
        RadioButton button = new RadioButton(this);
        button.setText(text);
        button.setTextSize(14);
        button.setTag(tag);
        button.setId(View.generateViewId());
        group.addView(button);
        return button;
    }

    private String checkedFeedback(RadioGroup group) {
        int checked = group.getCheckedRadioButtonId();
        if (checked == View.NO_ID) {
            return "";
        }
        RadioButton radio = group.findViewById(checked);
        return radio == null ? "" : String.valueOf(radio.getTag());
    }

    private String checkedFeedback(List<RadioButton> buttons) {
        for (RadioButton button : buttons) {
            if (button.isChecked()) {
                return String.valueOf(button.getTag());
            }
        }
        return "";
    }

    private int feedbackColor(String feedback) {
        switch (feedback) {
            case "unripe":
                return 0xFF7FBF7B;
            case "acceptable":
                return 0xFFE889A8;
            case "bursting":
                return 0xFFE00000;
            case "overripe":
                return 0xFF8B1E3F;
            default:
                return 0xFF223128;
        }
    }

    private Spinner weightSpinner(int selectedWeightKg, boolean defaultToFive) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, weightOptions());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(weightPosition(selectedWeightKg, defaultToFive));
        return spinner;
    }

    private List<String> weightOptions() {
        List<String> options = new ArrayList<>();
        options.add("不清楚");
        for (int i = 2; i <= 10; i++) {
            options.add(i + " 公斤");
        }
        return options;
    }

    private int selectedWeight(Spinner spinner) {
        int position = spinner.getSelectedItemPosition();
        return position == 0 ? 0 : position + 1;
    }

    private int weightPosition(int weightKg, boolean defaultToFive) {
        int weight = weightKg > 0 ? weightKg : (defaultToFive ? 5 : 0);
        if (weight <= 0) {
            return 0;
        }
        return Math.max(1, Math.min(9, weight - 1));
    }

    private String shortTime(long time) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(time);
    }

    private String fullTime(long time) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(time);
    }

    private String ripenessText(String label) {
        switch (label) {
            case "unripe":
                return "偏生";
            case "acceptable":
            case "ripe":
                return "尚可";
            case "bursting":
                return "爆表";
            case "overripe":
                return "过熟";
            default:
                return "未知";
        }
    }

    private String feedbackText(String feedback) {
        for (int i = 0; i < FEEDBACK_VALUES.length; i++) {
            if (FEEDBACK_VALUES[i].equals(feedback)) {
                return FEEDBACK_LABELS[i];
            }
        }
        return "未反馈";
    }

    private int ripenessColor(String label) {
        switch (label) {
            case "unripe":
                return feedbackColor("unripe");
            case "acceptable":
            case "ripe":
                return feedbackColor("acceptable");
            case "bursting":
                return feedbackColor("bursting");
            case "overripe":
                return feedbackColor("overripe");
            default:
                return 0xFF223128;
        }
    }

    private String confidenceSuffix(double confidence) {
        return confidence > 0 ? String.format(Locale.US, " %.0f%%", confidence * 100) : "";
    }

    private String confidenceValue(double confidence) {
        return confidence > 0 ? String.format(Locale.US, "%.0f%%", confidence * 100) : "不清楚";
    }

    private SpannableString aiResultText(Prediction prediction) {
        String resultLine = "AI识瓜：" + ripenessText(prediction.label);
        String text = String.format(Locale.US, "%s\n置信度 %.0f%%", resultLine, prediction.confidence * 100);
        SpannableString span = new SpannableString(text);
        int titleStart = 0;
        int titleEnd = "AI识瓜：".length();
        int resultStart = titleEnd;
        int resultEnd = resultLine.length();
        span.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), titleStart, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new RelativeSizeSpan(1.2f), titleStart, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new ForegroundColorSpan(RECORD_COLOR), titleStart, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), resultStart, resultEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new RelativeSizeSpan(1.55f), resultStart, resultEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new ForegroundColorSpan(ripenessColor(prediction.label)), resultStart, resultEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return span;
    }

    private String listWeightText(int weightKg) {
        return weightKg > 0 ? weightKg + " 公斤" : "-";
    }

    private String weightText(int weightKg) {
        return weightKg > 0 ? weightKg + " 公斤" : "不清楚";
    }

    private String formatDuration(long durationMs) {
        if (durationMs <= 0) {
            return "未知时长";
        }
        return String.format(Locale.US, "%.1fs", durationMs / 1000.0);
    }

    private LinearLayout rowLayout() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout panel() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, dp(8), 0, dp(8));
        view.setLayoutParams(lp);
        view.setBackgroundColor(0xFFFFFFFF);
        return view;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private TextView panelText() {
        TextView view = text("", 15, 0xFF223128, false);
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, dp(8), 0, dp(8));
        view.setLayoutParams(lp);
        view.setBackgroundColor(0xFFFFFFFF);
        return view;
    }

    private Button primaryButton(String text) {
        Button button = secondaryButton(text);
        button.setTextColor(0xFFFFFFFF);
        button.setBackground(boxBackground(RECORD_COLOR, RECORD_COLOR));
        return button;
    }

    private Button dangerButton(String text) {
        Button button = secondaryButton(text);
        button.setTextColor(0xFFFFFFFF);
        button.setBackground(boxBackground(0xFFB33A3A, 0xFFB33A3A));
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
        );
        lp.setMargins(0, dp(8), 0, 0);
        button.setLayoutParams(lp);
        button.setTextColor(0xFF1B7F45);
        button.setBackground(boxBackground(0xFFFFFFFF, 0xFFD4E5D8));
        return button;
    }

    private Button compactButton(String text) {
        Button button = secondaryButton(text);
        button.setTextSize(14);
        return button;
    }

    private GradientDrawable boxBackground(int fillColor, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setStroke(dp(1), strokeColor);
        drawable.setCornerRadius(dp(10));
        return drawable;
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
