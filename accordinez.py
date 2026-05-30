#!/usr/bin/env python3
"""
Accordinez prototype: polyphonic sine-wave synth controlled by computer keyboard.

Dependencies:
    pip install pynput sounddevice numpy

Usage:
    python accordinez.py
"""

from __future__ import annotations

import signal
import os
import sys
import threading
from dataclasses import dataclass
from typing import Dict, Optional, Set

import numpy as np
import sounddevice as sd

try:
    from pynput import keyboard
except ImportError as exc:
    keyboard = None
    KEYBOARD_IMPORT_ERROR: Optional[ImportError] = exc
else:
    KEYBOARD_IMPORT_ERROR = None


# Global chord-scale bank definition (requested for easy top-level access).
CHORD_SCALE_BANKS = {
    "a": {"name": "C ionian", "degrees": [60, 62, 64, 65, 67, 69, 71]},
    "w": {"name": "D dorian", "degrees": [62, 64, 65, 67, 69, 71, 72]},
    "s": {"name": "E phrygian", "degrees": [64, 65, 67, 69, 71, 72, 74]},
    "e": {"name": "F lydian", "degrees": [65, 67, 69, 71, 72, 74, 76]},
    "d": {"name": "G mixolydian", "degrees": [67, 69, 71, 72, 74, 76, 77]},
    "r": {"name": "A aeolian", "degrees": [69, 71, 72, 74, 76, 77, 79]},
    "f": {"name": "B locrian", "degrees": [71, 72, 74, 76, 77, 79, 81]},
    "g": {"name": "E custom", "degrees": [64, 65, 68, 69, 71, 72, 74]},
}

RIGHT_HAND_KEYS = {
    "j": 0,
    "i": 1,
    "k": 2,
    "o": 3,
    "l": 4,
    "p": 5,
    ";": 6,
}

BANK_KEYS = set(CHORD_SCALE_BANKS.keys())
PITCH_KEY = "t"
OCTAVE_DOWN_KEY = "c"
OCTAVE_UP_KEY = "v"

SAMPLE_RATE = 48000
BLOCK_SIZE = 256
MASTER_GAIN = 0.2
MIN_MIDI = 0
MAX_MIDI = 127


@dataclass
class Voice:
    right_key: str
    bank_key_at_note_on: str
    degree_idx: int
    base_midi: int
    current_midi: int
    base_freq: float
    current_freq: float
    phase: float = 0.0


def midi_to_freq(midi_note: int) -> float:
    return 440.0 * (2.0 ** ((midi_note - 69) / 12.0))


class SineSynth:
    def __init__(self, get_voices_cb, lock: threading.Lock) -> None:
        self.get_voices_cb = get_voices_cb
        self.lock = lock
        self.stream = sd.OutputStream(
            samplerate=SAMPLE_RATE,
            channels=1,
            dtype="float32",
            blocksize=BLOCK_SIZE,
            callback=self.audio_callback,
        )

    def start(self) -> None:
        self.stream.start()

    def stop(self) -> None:
        self.stream.stop()
        self.stream.close()

    def audio_callback(self, outdata, frames, _time, _status) -> None:
        with self.lock:
            voices = list(self.get_voices_cb().values())

            if not voices:
                outdata.fill(0)
                return

            mix = np.zeros(frames, dtype=np.float32)
            per_voice_gain = MASTER_GAIN / max(1.0, np.sqrt(len(voices)))

            for voice in voices:
                inc = (2.0 * np.pi * voice.current_freq) / SAMPLE_RATE
                phase_series = voice.phase + inc * np.arange(frames, dtype=np.float32)
                wave = np.sin(phase_series, dtype=np.float32)
                mix += per_voice_gain * wave
                voice.phase = float((voice.phase + inc * frames) % (2.0 * np.pi))

            np.clip(mix, -1.0, 1.0, out=mix)
            outdata[:, 0] = mix


class Accordinez:
    def __init__(self) -> None:
        self.lock = threading.Lock()

        self.active_bank_key: str = "a"
        self.octave_offset: int = 0
        self.pitch_shift_active: bool = False

        self.held_right_keys: Set[str] = set()
        self.held_control_keys: Set[str] = set()
        self.voices_by_right_key: Dict[str, Voice] = {}

        self.listener: Optional[keyboard.Listener] = None
        self.running = True

        self.synth = SineSynth(self._voices_ref, self.lock)
        self.synth_started = False

    def _voices_ref(self) -> Dict[str, Voice]:
        return self.voices_by_right_key

    def key_to_char(self, key: keyboard.Key | keyboard.KeyCode) -> Optional[str]:
        if isinstance(key, keyboard.KeyCode):
            if key.char is None:
                return None
            return key.char.lower()
        return None

    def clamp_midi(self, midi_note: int) -> int:
        return max(MIN_MIDI, min(MAX_MIDI, midi_note))

    def effective_note(self, base_midi: int) -> int:
        return self.clamp_midi(base_midi + (1 if self.pitch_shift_active else 0))

    def debug(self, label: str) -> None:
        active = ", ".join(
            f"{k}:{v.current_midi}({v.bank_key_at_note_on})"
            for k, v in sorted(self.voices_by_right_key.items())
        )
        active = active or "-"
        bank_name = CHORD_SCALE_BANKS[self.active_bank_key]["name"]
        print(
            f"[{label}] bank={self.active_bank_key}:{bank_name} "
            f"oct={self.octave_offset:+} shift={self.pitch_shift_active} voices={active}"
        )

    def start_voice(self, right_key: str) -> None:
        if right_key in self.voices_by_right_key:
            return

        degree_idx = RIGHT_HAND_KEYS[right_key]
        base_midi = CHORD_SCALE_BANKS[self.active_bank_key]["degrees"][degree_idx] + self.octave_offset
        base_midi = self.clamp_midi(base_midi)

        current_midi = self.effective_note(base_midi)
        voice = Voice(
            right_key=right_key,
            bank_key_at_note_on=self.active_bank_key,
            degree_idx=degree_idx,
            base_midi=base_midi,
            current_midi=current_midi,
            base_freq=midi_to_freq(base_midi),
            current_freq=midi_to_freq(current_midi),
        )
        self.voices_by_right_key[right_key] = voice
        self.debug(f"note_on {right_key}->{current_midi}")

    def stop_voice(self, right_key: str) -> None:
        voice = self.voices_by_right_key.pop(right_key, None)
        if voice is None:
            return
        self.debug(f"note_off {right_key}->{voice.current_midi}")

    def retune_all_active_voices(self) -> None:
        for voice in self.voices_by_right_key.values():
            target_midi = self.effective_note(voice.base_midi)
            voice.current_midi = target_midi
            voice.current_freq = midi_to_freq(target_midi)
        self.debug("retune_all")

    def handle_bank_select(self, ch: str) -> None:
        if ch == self.active_bank_key:
            return
        self.active_bank_key = ch
        # Lazy update: do not retune sustained voices.
        self.debug(f"bank_switch {ch}")

    def handle_pitch_key_down(self) -> None:
        if self.pitch_shift_active:
            return
        self.pitch_shift_active = True
        self.retune_all_active_voices()

    def handle_pitch_key_up(self) -> None:
        if not self.pitch_shift_active:
            return
        self.pitch_shift_active = False
        self.retune_all_active_voices()

    def all_notes_off(self) -> None:
        self.voices_by_right_key.clear()
        self.held_right_keys.clear()
        self.debug("all_notes_off")

    def on_press(self, key: keyboard.Key | keyboard.KeyCode) -> None:
        ch = self.key_to_char(key)
        if ch is None:
            return

        with self.lock:
            if ch in BANK_KEYS | {PITCH_KEY, OCTAVE_DOWN_KEY, OCTAVE_UP_KEY}:
                if ch in self.held_control_keys:
                    return
                self.held_control_keys.add(ch)

            if ch in BANK_KEYS:
                self.handle_bank_select(ch)
                return

            if ch == PITCH_KEY:
                self.handle_pitch_key_down()
                return

            if ch == OCTAVE_DOWN_KEY:
                self.octave_offset -= 12
                self.debug("octave_down")
                return

            if ch == OCTAVE_UP_KEY:
                self.octave_offset += 12
                self.debug("octave_up")
                return

            if ch in RIGHT_HAND_KEYS:
                if ch in self.held_right_keys:
                    return
                self.held_right_keys.add(ch)
                self.start_voice(ch)

    def on_release(self, key: keyboard.Key | keyboard.KeyCode) -> None:
        ch = self.key_to_char(key)
        if ch is None:
            return

        with self.lock:
            if ch in self.held_control_keys:
                self.held_control_keys.remove(ch)

            if ch == PITCH_KEY:
                self.handle_pitch_key_up()
                return

            if ch in RIGHT_HAND_KEYS:
                if ch in self.held_right_keys:
                    self.held_right_keys.remove(ch)
                self.stop_voice(ch)

    def shutdown(self) -> None:
        with self.lock:
            self.running = False
            self.all_notes_off()

        if self.listener is not None:
            self.listener.stop()

        if self.synth_started:
            self.synth.stop()
            self.synth_started = False

    def run(self) -> None:
        if keyboard is None:
            print("Accordinez cannot start keyboard input.")
            print("The pynput keyboard backend failed to initialize:")
            print(f"  {KEYBOARD_IMPORT_ERROR}")
            if sys.platform.startswith("linux"):
                print()
                print("Linux note: Accordinez currently uses pynput's global keyboard listener.")
                print("That backend requires an X11 session. It usually cannot read keys under Wayland.")
                print("Try logging into an Xorg/X11 session, or run from a terminal with DISPLAY set.")
            return

        if sys.platform.startswith("linux"):
            session_type = os.environ.get("XDG_SESSION_TYPE", "").lower()
            if session_type == "wayland" or (os.environ.get("WAYLAND_DISPLAY") and not os.environ.get("DISPLAY")):
                print("Warning: this looks like a Wayland Linux session.")
                print("pynput keyboard listeners often cannot receive key events under Wayland.")
                print("If keys do nothing, log into an Xorg/X11 session and run Accordinez there.")
                print()

        print("Accordinez running (polyphonic sine-wave synth).")
        print("Left hand: a/w/s/e/d/r/f/g (bank), t (+1 semitone hold), c/v (oct down/up).")
        print("Right hand: j i k o l p ; mapped to degrees 1..7.")
        print("Press Ctrl+C to exit.")

        self.synth.start()
        self.synth_started = True
        self.listener = keyboard.Listener(on_press=self.on_press, on_release=self.on_release)
        self.listener.start()
        self.listener.join()


def main() -> int:
    app = Accordinez()

    def _signal_handler(_sig: int, _frame: object) -> None:
        app.shutdown()
        raise KeyboardInterrupt

    signal.signal(signal.SIGINT, _signal_handler)
    signal.signal(signal.SIGTERM, _signal_handler)

    try:
        app.run()
    except KeyboardInterrupt:
        pass
    finally:
        app.shutdown()
    return 0


if __name__ == "__main__":
    sys.exit(main())
