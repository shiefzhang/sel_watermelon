# 听瓜

听瓜是一款 Android 西瓜成熟度辅助判断应用。用户把手机靠近西瓜侧边，拍打录音后，应用会显示实时波形、提取声学特征，并给出偏生、尚可、甘甜、过熟等 AI 判断。用户还可以补充真实口感和重量反馈，导出音频与元数据，用于持续训练和升级模型。

## 功能概览

- 挑瓜录音：使用手机麦克风采集 16 kHz 单声道 WAV。
- 实时波形：录音时显示滑动窗口音频图形，方便确认敲击是否有效。
- AI 判断：内置规则/模型会输出成熟度标签、置信度和声音特征摘要。
- 反馈采集：支持记录实际口感和重量，重量单位为公斤。
- 音频列表：查看采样时间、AI 判断、实际口感、重量，并支持多选。
- 详情管理：查看单条录音详情，播放音频，修改反馈或删除样本。
- 数据导出：打包导出 WAV 和 `metadata.jsonl`，便于模型训练。
- 模型升级：支持上传训练生成的 TFLite 模型包，后续录音优先使用新模型。

## 使用流程

1. 安装 APK，并允许录音权限。
2. 在“挑瓜”页点击“开始录音”。
3. 按提示在西瓜侧边拍打 3 下，停止后查看 AI 判断结果。
4. 切开或确认口感后，选择“偏生 / 尚可 / 甘甜 / 过熟”，可选填写重量。
5. 点击“保存实际口感”，应用会保存反馈并给出成功提示。
6. 在列表页查看、播放、修改、删除或多选导出录音数据。

## 数据导出

导出的 ZIP 包包含音频文件和 `metadata.jsonl`。`metadata.jsonl` 每行对应一条录音，常见字段包括：

- `audio_id`：录音唯一 ID。
- `file`：音频文件相对路径。
- `created_at` / `created_at_text`：采样时间。
- `duration_ms`：录音时长。
- `phone_manufacturer` / `phone_model` / `phone_device` / `phone_label`：手机设备信息。
- `model_label` / `model_confidence` / `model_source`：AI 判断、置信度和模型来源。
- `feedback` / `user_label`：用户确认后的实际口感。
- `weight_kg`：西瓜重量，单位公斤；`0` 表示不清楚。
- 声音特征：`rms`、`peak`、`zcr`、`centroid`、频段比例、`decay`、MFCC 均值和标准差等。

## 构建

项目使用 Gradle Android 插件构建，主要配置：

- `applicationId`：`com.example.selwatermelon`
- `minSdk`：26
- `targetSdk`：35
- `compileSdk`：35
- Java：17

Windows 环境可执行：

```powershell
$env:JAVA_HOME='D:\android-studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleDebug
```

Debug APK 输出位置：

```text
app/build/outputs/apk/debug/SelWatermelon-debug-1.0.apk
```

## 训练模型

应用导出的数据包可作为训练脚本输入：

```bash
python training/train_model.py watermelon_dataset.zip -o model_bundle.zip
```

训练完成后，把 `model_bundle.zip` 发送到手机，在应用内点击“上传训练好的 TFLite 模型包”。上传成功后，后续录音会优先使用新的 TFLite 模型进行本地推理。

## 采集建议

- 每个西瓜建议采集多个位置、多个敲击样本。
- 训练集和测试集应按“西瓜个体”划分，避免同一个西瓜的不同录音同时进入训练和测试。
- 真实标签最好来自切开验证、糖度或明确口感反馈，只靠听感标注会影响模型质量。
- 不同手机麦克风差异明显，导出数据中会记录手机型号，训练时可用于分析设备偏差。

## 项目结构

```text
app/src/main/java/com/example/selwatermelon/
  MainActivity.java          应用主界面和交互逻辑
  WatermelonRecorder.java    录音采集
  WaveformView.java          实时波形显示
  AudioFeatures.java         声音特征提取
  RipenessModel.java         成熟度模型推理与模型导入
  DatasetStore.java          录音、反馈和导出数据管理
training/
  train_model.py             训练并打包 TFLite 模型
```

## 数据反馈

为不断优化模型准确度，可将导出的数据包发送给作者用于模型训练与升级。

邮箱：zhangxuefeng@batonsoft.com
