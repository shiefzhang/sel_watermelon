# 听瓜

听瓜是一款 Android 西瓜成熟度辅助判断应用。用户把手机靠近西瓜侧边拍打录音后，应用会显示实时波形、提取声学特征，并给出“偏生 / 尚可 / 甘甜 / 过熟”的判断。用户也可以补充真实口感和重量反馈，导出音频与元数据，用于持续训练和升级模型。

当前版本：`1.1.0`

## 版本更新历史

完整更新记录见 [CHANGELOG.md](CHANGELOG.md) 或更适合阅读的 [CHANGELOG.html](CHANGELOG.html)。

### 1.1.0

- 增加“甘甜”作为独立成熟阶段，位于“尚可”和“过熟”之间，并更偏向“过熟”，用于表达最佳甜度区间。
- 内置模型从三类扩展为四类：`unripe / ripe / bursting / overripe`。
- 外部模型如果没有 `bursting` 标签，应用会根据声学特征后处理适配“甘甜”判断。
- 列表页删除录音前增加确认弹框。
- 列表页下载所选录音前增加提示弹框，并默认勾选“下载后删除选中数据中有反馈的数据”。
- 下载后自动删除时，只删除选中数据中已填写反馈的记录，未反馈记录会保留。
- 支持上传、切换和删除训练好的模型包。
- 训练脚本支持线性 JSON 模型、MLP TFLite 模型、元数据特征复用和交叉验证报告。

## 功能概览

- 挑瓜录音：使用手机麦克风采集 16 kHz 单声道 WAV。
- 实时波形：录音时显示滑动窗口音频图形，便于确认敲击是否有效。
- AI 判断：提取 RMS、峰值、过零率、频谱质心、频段比例、衰减和 MFCC 等特征。
- 反馈采集：记录实际口感和重量，形成可训练数据。
- 音频列表：查看采样时间、AI 判断、实际口感、重量，并支持多选下载和删除。
- 详情管理：播放单条录音，修改反馈，删除样本。
- 数据导出：打包导出 WAV 和 `metadata.jsonl`，用于模型训练。
- 模型评估：上传多个训练生成的模型包，在应用内切换当前模型。

## 使用流程

1. 安装 APK，并允许录音权限。
2. 在“挑瓜”页点击“开始录音”。
3. 按提示在西瓜侧边拍打 1-3 次，停止后查看 AI 判断结果。
4. 切开或确认口感后，选择“偏生 / 尚可 / 甘甜 / 过熟”，可选填写重量。
5. 点击“保存实际口感”，应用会保存反馈。
6. 在“列表”页查看、播放、修改、删除或多选下载录音数据。

## 成熟度标签

- `unripe` / 偏生：高频比例或频谱重心偏高，可能还不够成熟。
- `ripe` 或 `acceptable` / 尚可：成熟度可接受，声音低沉、共振较集中。
- `bursting` / 甘甜：位于尚可和过熟之间，更接近过熟，代表最佳甜度区间。
- `overripe` / 过熟：低频或衰减特征偏强，可能偏软、空心或过熟。

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

列表页下载所选数据时，会提示是否在下载后删除已反馈数据。默认勾选后，只会删除选中数据中已经填写反馈的记录，未填写反馈的数据会保留。

## 构建

项目使用 Gradle Android 插件构建，主要配置：

- `applicationId`：`com.example.selwatermelon`
- `versionName`：`1.1.0`
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
app/build/outputs/apk/debug/SelWatermelon-debug-1.1.0.apk
```

## 训练模型

应用导出的数据包可作为训练脚本输入：

```bash
python training/train_model.py watermelon_dataset.zip -o model.json
```

默认会训练线性 JSON 模型，适合小样本快速迭代。也可以训练 TFLite 模型包：

```bash
python training/train_model.py watermelon_dataset.zip --method mlp -o model_bundle.zip
```

训练完成后，把 `model.json` 或 `model_bundle.zip` 发送到手机，在应用内上传。新模型会出现在“当前模型”下拉框中，并自动启用；后续可在下拉框中切换不同模型。

## 采集建议

- 每个西瓜建议采集多个位置、多个敲击样本。
- 训练集和测试集应按“西瓜个体”划分，避免同一个西瓜的不同录音同时进入训练和测试。
- 真实标签最好来自切开验证、糖度或明确口感反馈。
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
  train_model.py             训练并导出 JSON 或 TFLite 模型
```

## 数据反馈

为持续优化模型准确度，可将导出的数据包发送给作者用于模型训练与升级。

邮箱：zhangxuefeng@batonsoft.com
