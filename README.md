# 听瓜

听瓜是一款 Android 西瓜成熟度辅助判断应用。用户在西瓜侧边拍打录音后，应用会显示实时音频波形，提取声音特征，并用内置或上传的模型给出 AI 挑瓜结果。用户也可以补充实际口感和重量反馈，导出音频与元数据用于后续模型训练。

## 主要功能

- 挑瓜录音：使用手机麦克风录制 16 kHz 单声道 WAV。
- 实时波形：录音时显示约 5 秒滑动窗口的音频图形。
- AI 识别：基于 MFCC 与声学特征输出偏生、尚可、爆表、过熟等判断。
- 反馈采集：记录实际口感与可选重量，重量单位为公斤。
- 音频列表：查看采样时间、AI 挑瓜结果、实际口感和重量。
- 详情管理：播放、删除、修改反馈，查看来源模型和声音特征。
- 数据导出：多选导出 WAV 与 `metadata.jsonl`，包含音频 ID、手机型号、采样时间、模型结果、反馈和特征。
- 模型升级：支持上传训练生成的 TFLite 模型包。

## 数据字段

导出的 `metadata.jsonl` 每行对应一条音频，常见字段包括：

- `audio_id`：程序生成的唯一音频 ID。
- `file`：音频文件相对路径。
- `created_at` / `created_at_text`：采样时间。
- `duration_ms`：录音时长。
- `phone_manufacturer` / `phone_model` / `phone_device` / `phone_label`：手机型号信息。
- `model_label` / `model_confidence` / `model_source`：AI 挑瓜结果、置信度和来源模型。
- `feedback` / `user_label`：实际口感反馈。
- `weight_kg`：西瓜重量，单位公斤；`0` 表示不清楚。
- 声音特征：`rms`、`peak`、`zcr`、`centroid`、频段比例、`decay`、MFCC 统计值等。

## 构建

项目使用 Gradle Android 插件构建。Windows 环境下可执行：

```powershell
$env:JAVA_HOME='D:\android-studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleDebug
```

构建产物位于：

```text
app/build/outputs/apk/debug/SelWatermelon-debug-1.0.apk
```

## 训练模型

应用导出的 ZIP 可作为训练脚本输入：

```bash
python training/train_model.py watermelon_dataset.zip -o model_bundle.zip
```

训练完成后，在应用内上传 `model_bundle.zip`，后续录音会优先使用上传的 TFLite 模型。

## 数据反馈

为不断优化模型准确度，您可发送数据给作者，用于模型训练与升级。

mail: zhangxuefeng@batonsoft.com
