#!/usr/bin/env python3
"""Train the on-device MFCC + TFLite model.

Input can be the exported ZIP from the Android app or an unpacked dataset folder.
The default linear output is model.json, which can be uploaded in the app.
The MLP output is model_bundle.zip, which contains:

- model.tflite
- model_metadata.json

Upload that ZIP in the app with "上传训练好的 TFLite 模型包".
"""

from __future__ import annotations

import argparse
import json
import math
import os
import statistics
import tempfile
import wave
import zipfile
from pathlib import Path


BASE_FEATURES = ["rms", "peak", "zcr", "centroid", "low_ratio", "mid_ratio", "high_ratio", "decay"]
MFCC_COUNT = 13
FEATURES = (
    BASE_FEATURES
    + [f"mfcc_{i}_mean" for i in range(1, MFCC_COUNT + 1)]
    + [f"mfcc_{i}_std" for i in range(1, MFCC_COUNT + 1)]
)
LABELS = ["unripe", "acceptable", "bursting", "overripe"]
FRAME_SIZE = 400
HOP_SIZE = 160
FFT_SIZE = 512
MEL_BINS = 26
EPS = 1e-9


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", help="Android exported watermelon_dataset.zip or dataset directory")
    parser.add_argument("-o", "--output", help="Output model file. Defaults to model.json for linear or model_bundle.zip for mlp")
    parser.add_argument(
        "--method",
        choices=["linear", "mlp"],
        default="linear",
        help="Training method. linear is better for small datasets; mlp is the previous neural network.",
    )
    parser.add_argument(
        "--features-source",
        choices=["auto", "metadata", "wav"],
        default="auto",
        help="Use metadata feature columns when available, or recompute from WAV.",
    )
    parser.add_argument("--epochs", type=int, default=120)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--validation-split", type=float, default=0.2)
    args = parser.parse_args()
    output = Path(args.output) if args.output else Path("model.json" if args.method == "linear" else "model_bundle.zip")

    with prepared_dataset(Path(args.dataset)) as dataset_dir:
        rows = load_rows(dataset_dir, args.features_source)
        if not rows:
            raise SystemExit("No labeled rows found. Save feedback in the Android app before training.")
        if len({row["label"] for row in rows}) < 2:
            raise SystemExit("At least two user_label classes are required for training.")
        report_cross_validation(rows)
        if args.method == "linear":
            train_linear_json(rows, output)
        else:
            train_tflite(rows, output, args.method, args.epochs, args.batch_size, args.validation_split)
        print(f"trained {args.method} model on {len(rows)} labeled samples -> {output}")
        for label in LABELS:
            print(f"{label}: {sum(1 for row in rows if row['label'] == label)} samples")


class prepared_dataset:
    def __init__(self, source: Path):
        self.source = source
        self.tmp: tempfile.TemporaryDirectory[str] | None = None

    def __enter__(self) -> Path:
        if self.source.is_dir():
            return self.source
        self.tmp = tempfile.TemporaryDirectory()
        with zipfile.ZipFile(self.source) as zf:
            zf.extractall(self.tmp.name)
        return Path(self.tmp.name)

    def __exit__(self, exc_type, exc, tb) -> None:
        if self.tmp:
            self.tmp.cleanup()


def load_rows(dataset_dir: Path, features_source: str) -> list[dict]:
    metadata = dataset_dir / "metadata.jsonl"
    rows: list[dict] = []
    for line in metadata.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        item = json.loads(line)
        label = item.get("user_label")
        if label not in LABELS:
            continue
        values = feature_values(item, dataset_dir, features_source)
        rows.append({"label": label, "features": values})
    return rows


def feature_values(item: dict, dataset_dir: Path, features_source: str) -> list[float]:
    if features_source in ("auto", "metadata") and has_metadata_features(item):
        return [float(item[name]) for name in FEATURES]
    if features_source == "metadata":
        missing = [name for name in FEATURES if name not in item]
        raise ValueError(f"metadata row is missing feature columns: {', '.join(missing)}")
    wav_path = dataset_dir / item["file"]
    return extract_features(wav_path)


def has_metadata_features(item: dict) -> bool:
    return all(name in item and item[name] not in ("", None) for name in FEATURES)


def train_linear_json(rows: list[dict], output: Path) -> None:
    try:
        import numpy as np
        from sklearn.linear_model import LogisticRegression
        from sklearn.preprocessing import StandardScaler
    except ImportError as exc:
        raise SystemExit("scikit-learn is required for linear training. Install it with: pip install scikit-learn") from exc

    present_labels = [label for label in LABELS if any(row["label"] == label for row in rows)]
    x = np.asarray([row["features"] for row in rows], dtype=np.float32)
    y = np.asarray([present_labels.index(row["label"]) for row in rows], dtype=np.int64)

    scaler = StandardScaler()
    x_norm = scaler.fit_transform(x)
    model = LogisticRegression(class_weight="balanced", max_iter=1000, random_state=20260529)
    model.fit(x_norm, y)

    metadata = {
        "type": "linear",
        "version": 1,
        "sample_rate": 16000,
        "labels": present_labels,
        "features": FEATURES,
        "means": scaler.mean_.astype(float).tolist(),
        "stds": scaler.scale_.astype(float).tolist(),
        "coefficients": linear_coefficients(model, len(present_labels)),
        "intercepts": linear_intercepts(model, len(present_labels)),
        "notes": "Generated by training/train_model.py using metadata features and balanced logistic regression.",
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")


def linear_coefficients(model, label_count: int) -> list[list[float]]:
    coefficients = model.coef_.astype(float)
    if label_count == 2 and len(coefficients) == 1:
        negative = [0.0] * len(coefficients[0])
        positive = coefficients[0].tolist()
        return [negative, positive]
    return coefficients.tolist()


def linear_intercepts(model, label_count: int) -> list[float]:
    intercepts = model.intercept_.astype(float)
    if label_count == 2 and len(intercepts) == 1:
        return [0.0, float(intercepts[0])]
    return intercepts.tolist()


def train_tflite(
    rows: list[dict],
    output: Path,
    method: str,
    epochs: int,
    batch_size: int,
    validation_split: float,
) -> None:
    os.environ.setdefault("PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION", "python")
    try:
        import numpy as np
        import tensorflow as tf
    except ImportError as exc:
        raise SystemExit(
            "TensorFlow is required for TFLite export. Install it with: pip install tensorflow"
        ) from exc

    tf.keras.utils.set_random_seed(20260529)
    x = np.asarray([row["features"] for row in rows], dtype=np.float32)
    y = np.asarray([LABELS.index(row["label"]) for row in rows], dtype=np.int64)
    means = x.mean(axis=0)
    stds = x.std(axis=0)
    stds[stds < 1e-6] = 1.0
    x_norm = (x - means) / stds

    model = build_model(tf, method)
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=0.001),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    callbacks = [
        tf.keras.callbacks.EarlyStopping(
            monitor="val_loss" if len(rows) >= 15 else "loss",
            patience=18,
            restore_best_weights=True,
        )
    ]
    val_split = validation_split if len(rows) >= 15 else 0.0
    model.fit(
        x_norm,
        y,
        epochs=epochs,
        batch_size=batch_size,
        validation_split=val_split,
        callbacks=callbacks,
        class_weight=class_weights(rows),
        verbose=2,
    )

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()
    metadata = {
        "type": "mfcc_mlp_tflite",
        "version": 1,
        "method": method,
        "sample_rate": 16000,
        "labels": LABELS,
        "features": FEATURES,
        "means": means.astype(float).tolist(),
        "stds": stds.astype(float).tolist(),
        "notes": "Generated by training/train_model.py. Upload this ZIP in the Android app.",
    }

    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("model.tflite", tflite_model)
        zf.writestr("model_metadata.json", json.dumps(metadata, ensure_ascii=False, indent=2))


def build_model(tf, method: str):
    layers = [tf.keras.layers.Input(shape=(len(FEATURES),), name="features")]
    if method == "mlp":
        layers.extend(
            [
                tf.keras.layers.Dense(128, activation="relu"),
                tf.keras.layers.Dropout(0.2),
                tf.keras.layers.Dense(64, activation="relu"),
            ]
        )
    layers.append(tf.keras.layers.Dense(len(LABELS), activation="softmax", name="ripeness"))
    return tf.keras.Sequential(layers)


def class_weights(rows: list[dict]) -> dict[int, float]:
    counts = {label: sum(1 for row in rows if row["label"] == label) for label in LABELS}
    present = {label: count for label, count in counts.items() if count > 0}
    total = sum(present.values())
    return {
        LABELS.index(label): total / (len(present) * count)
        for label, count in present.items()
    }


def report_cross_validation(rows: list[dict]) -> None:
    try:
        import numpy as np
        from sklearn.linear_model import LogisticRegression
        from sklearn.metrics import accuracy_score, confusion_matrix
        from sklearn.model_selection import StratifiedKFold
        from sklearn.pipeline import make_pipeline
        from sklearn.preprocessing import StandardScaler
    except ImportError:
        print("sklearn not installed; skipping cross-validation report")
        return

    labels = [row["label"] for row in rows]
    counts = {label: labels.count(label) for label in LABELS if labels.count(label)}
    min_count = min(counts.values())
    if len(counts) < 2 or min_count < 2:
        print("not enough per-class samples for stratified cross-validation")
        return

    x = np.asarray([row["features"] for row in rows], dtype=np.float32)
    y = np.asarray([LABELS.index(row["label"]) for row in rows], dtype=np.int64)
    n_splits = min(5, min_count)
    splitter = StratifiedKFold(n_splits=n_splits, shuffle=True, random_state=20260529)
    predictions = np.zeros_like(y)

    for train_index, test_index in splitter.split(x, y):
        model = make_pipeline(
            StandardScaler(),
            LogisticRegression(class_weight="balanced", max_iter=1000, random_state=20260529),
        )
        model.fit(x[train_index], y[train_index])
        predictions[test_index] = model.predict(x[test_index])

    print(f"linear cross-validation accuracy ({n_splits}-fold): {accuracy_score(y, predictions):.3f}")
    matrix = confusion_matrix(y, predictions, labels=list(range(len(LABELS))))
    print("confusion matrix rows=true cols=pred")
    print("labels: " + ", ".join(LABELS))
    for label, values in zip(LABELS, matrix):
        print(f"{label}: " + " ".join(str(int(value)) for value in values))


def extract_features(path: Path) -> list[float]:
    with wave.open(str(path), "rb") as wav:
        sample_rate = wav.getframerate()
        frames = wav.readframes(wav.getnframes())
        width = wav.getsampwidth()
        channels = wav.getnchannels()
    if width != 2:
        raise ValueError(f"{path} is not 16-bit PCM")
    samples = []
    for i in range(0, len(frames), 2 * channels):
        samples.append(int.from_bytes(frames[i:i + 2], "little", signed=True))
    return audio_features(samples, sample_rate)


def audio_features(pcm: list[int], sample_rate: int) -> list[float]:
    if not pcm:
        return [0.0] * len(FEATURES)
    focused = focus_on_tap(pcm, sample_rate)
    values = [s / 32768.0 for s in focused]
    rms = math.sqrt(sum(v * v for v in values) / len(values))
    peak = max(abs(v) for v in values)
    crossings = sum(1 for a, b in zip(focused, focused[1:]) if (a < 0 <= b) or (a >= 0 > b))
    zcr = crossings / len(focused)

    center = max(0, len(focused) // 2 - 1024)
    window = focused[center: center + 2048]
    low = mid = high = weighted = total = 0.0
    for hz in range(120, 3201, 80):
        energy = goertzel(window, sample_rate, hz)
        total += energy
        weighted += energy * hz
        if hz < 500:
            low += energy
        elif hz < 1400:
            mid += energy
        else:
            high += energy
    total = total or 1.0
    first = energy_window(values[: max(1, len(values) // 4)])
    last = energy_window(values[len(values) * 3 // 4:])
    decay = max(0.0, min(1.5, 1.0 - last / first)) if first else 0.0
    means, stds = mfcc_stats(focused, sample_rate)
    return [rms, peak, zcr, weighted / total, low / total, mid / total, high / total, decay] + means + stds


def focus_on_tap(pcm: list[int], sample_rate: int) -> list[int]:
    peak_index = max(range(len(pcm)), key=lambda i: abs(pcm[i]))
    before = max(1, sample_rate // 20)
    length = min(len(pcm), sample_rate)
    start = max(0, peak_index - before)
    if start + length > len(pcm):
        start = max(0, len(pcm) - length)
    return pcm[start: start + length]


def mfcc_stats(pcm: list[int], sample_rate: int) -> tuple[list[float], list[float]]:
    filters = mel_filter_bank(sample_rate)
    coeffs: list[list[float]] = []
    for start in range(0, max(0, len(pcm) - FRAME_SIZE + 1), HOP_SIZE):
        power = power_spectrum(pcm[start: start + FRAME_SIZE])
        log_mel = []
        for filt in filters:
            energy = sum(p * w for p, w in zip(power, filt))
            log_mel.append(math.log(max(energy, EPS)))
        coeffs.append([dct(log_mel, i) for i in range(MFCC_COUNT)])
    if not coeffs:
        return [0.0] * MFCC_COUNT, [0.0] * MFCC_COUNT
    columns = list(zip(*coeffs))
    means = [statistics.fmean(col) for col in columns]
    stds = [statistics.pstdev(col) for col in columns]
    return means, stds


def power_spectrum(frame: list[int]) -> list[float]:
    out = []
    for k in range(FFT_SIZE // 2 + 1):
        real = imag = 0.0
        for n, sample in enumerate(frame):
            window = 0.5 - 0.5 * math.cos(2.0 * math.pi * n / max(1, FRAME_SIZE - 1))
            value = sample / 32768.0 * window
            angle = -2.0 * math.pi * k * n / FFT_SIZE
            real += value * math.cos(angle)
            imag += value * math.sin(angle)
        out.append(real * real + imag * imag)
    return out


def mel_filter_bank(sample_rate: int) -> list[list[float]]:
    bins = FFT_SIZE // 2 + 1
    min_mel = hz_to_mel(20.0)
    max_mel = hz_to_mel(sample_rate / 2.0)
    hz_points = [mel_to_hz(min_mel + (max_mel - min_mel) * i / (MEL_BINS + 1)) for i in range(MEL_BINS + 2)]
    bin_points = [max(0, min(bins - 1, int(math.floor((FFT_SIZE + 1) * hz / sample_rate)))) for hz in hz_points]
    filters = [[0.0] * bins for _ in range(MEL_BINS)]
    for m in range(1, MEL_BINS + 1):
        left = bin_points[m - 1]
        center = max(left + 1, bin_points[m])
        right = max(center + 1, bin_points[m + 1])
        for k in range(left, min(center, bins)):
            filters[m - 1][k] = (k - left) / max(1, center - left)
        for k in range(center, min(right, bins)):
            filters[m - 1][k] = (right - k) / max(1, right - center)
    return filters


def dct(values: list[float], coefficient: int) -> float:
    return sum(value * math.cos(math.pi * coefficient * (n + 0.5) / len(values)) for n, value in enumerate(values))


def hz_to_mel(hz: float) -> float:
    return 2595.0 * math.log10(1.0 + hz / 700.0)


def mel_to_hz(mel: float) -> float:
    return 700.0 * (10.0 ** (mel / 2595.0) - 1.0)


def goertzel(samples: list[int], sample_rate: int, hz: float) -> float:
    if not samples:
        return 0.0
    omega = 2.0 * math.pi * hz / sample_rate
    coeff = 2.0 * math.cos(omega)
    q1 = q2 = 0.0
    n = len(samples)
    for i, sample in enumerate(samples):
        window = 0.5 - 0.5 * math.cos(2.0 * math.pi * i / max(1, n - 1))
        q0 = coeff * q1 - q2 + sample / 32768.0 * window
        q2, q1 = q1, q0
    return q1 * q1 + q2 * q2 - coeff * q1 * q2


def energy_window(values: list[float]) -> float:
    return sum(v * v for v in values) / len(values) if values else 0.0


if __name__ == "__main__":
    main()
