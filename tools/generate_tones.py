"""合成内置闹钟铃声（16bit / 44.1kHz 单声道 WAV）。

用法：python tools/generate_tones.py
输出：app/src/main/res/raw/tone_*.wav
所有铃声首尾均做淡入淡出，可无缝循环播放。
"""

import math
import os
import random
import struct
import wave

SAMPLE_RATE = 44100
OUTPUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                          "app", "src", "main", "res", "raw")


def add_note(buffer, start_sec, freq, dur_sec, amp, decay, harmonics):
    start = int(start_sec * SAMPLE_RATE)
    total = int(dur_sec * SAMPLE_RATE)
    for i in range(total):
        index = start + i
        if index >= len(buffer):
            break
        t = i / SAMPLE_RATE
        attack = 1.0 - math.exp(-t * 500.0)
        envelope = math.exp(-decay * t) * attack
        value = 0.0
        for harmonic, weight in harmonics:
            value += weight * math.sin(2.0 * math.pi * freq * harmonic * t)
        buffer[index] += value * envelope * amp


def loop_fade(buffer, fade_sec=0.02):
    fade = int(fade_sec * SAMPLE_RATE)
    n = len(buffer)
    for i in range(fade):
        k = i / fade
        buffer[i] *= k
        buffer[n - 1 - i] *= k
    # 结束端再加一段静音，保证循环时留出呼吸感
    return buffer


def normalize(buffer, peak=0.85):
    top = max(abs(v) for v in buffer) or 1.0
    scale = peak / top
    return [v * scale for v in buffer]


def write_wav(name, samples):
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    path = os.path.join(OUTPUT_DIR, name)
    with wave.open(path, "w") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SAMPLE_RATE)
        frames = bytearray()
        for sample in samples:
            clipped = max(-1.0, min(1.0, sample))
            frames += struct.pack("<h", int(clipped * 32000))
        handle.writeframes(bytes(frames))
    print("generated", path, len(samples) / SAMPLE_RATE, "s")


def tone_gentle():
    """晨曦：柔和钟琴，五声音阶上行循环"""
    duration = 6.0
    buffer = [0.0] * int(duration * SAMPLE_RATE)
    notes = [523.25, 659.25, 783.99, 1046.50, 783.99, 659.25,
             587.33, 698.46, 880.00, 1174.66, 880.00, 698.46]
    for index, freq in enumerate(notes):
        add_note(buffer, index * 0.5, freq, 1.6, 0.30, 3.2,
                 [(1.0, 1.0), (2.0, 0.40), (3.0, 0.16), (4.76, 0.08)])
    return loop_fade(normalize(buffer, 0.82))


def tone_pulse():
    """脉冲：清晰的双音电子提示"""
    duration = 4.0
    buffer = [0.0] * int(duration * SAMPLE_RATE)
    pattern = [(0.00, 880.00), (0.18, 1174.66)]
    cycle = 0.75
    repeats = int(duration / cycle)
    for repeat in range(repeats):
        for offset, freq in pattern:
            add_note(buffer, repeat * cycle + offset, freq, 0.16, 0.34, 14.0,
                     [(1.0, 1.0), (2.0, 0.30), (3.0, 0.12)])
    return loop_fade(normalize(buffer, 0.80))


def tone_chime():
    """风铃：随机五声音阶短音，轻盈飘散"""
    duration = 6.0
    buffer = [0.0] * int(duration * SAMPLE_RATE)
    random.seed(20260915)
    scale = [783.99, 880.00, 1046.50, 1174.66, 1396.91, 1567.98]
    time_cursor = 0.0
    while time_cursor < duration - 0.6:
        add_note(buffer, time_cursor, random.choice(scale), 2.2,
                 random.uniform(0.16, 0.30), 3.6,
                 [(1.0, 1.0), (2.01, 0.35), (3.02, 0.14), (5.4, 0.06)])
        time_cursor += random.uniform(0.28, 0.62)
    return loop_fade(normalize(buffer, 0.78))


def tone_echo():
    """回响：温暖木质马林巴，节奏稳定"""
    duration = 6.0
    buffer = [0.0] * int(duration * SAMPLE_RATE)
    notes = [440.00, 523.25, 659.25, 523.25, 587.33, 698.46, 587.33, 493.88]
    for index, freq in enumerate(notes):
        add_note(buffer, index * 0.75, freq, 2.4, 0.32, 2.8,
                 [(1.0, 1.0), (4.0, 0.45), (10.0, 0.18), (16.0, 0.07)])
    return loop_fade(normalize(buffer, 0.82))


if __name__ == "__main__":
    write_wav("tone_gentle.wav", tone_gentle())
    write_wav("tone_pulse.wav", tone_pulse())
    write_wav("tone_chime.wav", tone_chime())
    write_wav("tone_echo.wav", tone_echo())
