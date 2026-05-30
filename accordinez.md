# Accordinez Prototype Spec (Python)

## Goal
Build a playable **polyphonic sine-wave synth prototype** called Accordinez using computer keyboard input.

## Core Behavior

### 1) Left-Hand Chord-Scale Bank (`a w s e d r f g`)
These keys select the active chord-scale context:

- `a` -> C ionian
- `w` -> D dorian
- `s` -> E phrygian
- `e` -> F lydian
- `d` -> G mixolydian
- `r` -> A aeolian
- `f` -> B locrian
- `g` -> E custom (E F G# A B C D)

Important behavior:
- Switching bank is **lazy** for right-hand notes.
- Notes already sustaining keep their original pitch assignment.
- Only newly triggered right-hand notes use the newly selected bank.
- This allows mixed clusters across multiple banks when holding/releasing notes legato.

### 2) Pitch Shift Key (`t`)
- `t` is a momentary semitone sharp modifier: `+1 semitone` to currently sounding notes.
- While `t` is held, sounding notes are sharpened in real time.
- On release of `t`, notes instantly drop back by one semitone.
- This is global and real-time (applies to currently sounding voices, including cluster notes from lazy bank changes).

### 3) Octave Controls (`c`, `v`)
- `c` -> octave down (`-12 semitones`)
- `v` -> octave up (`+12 semitones`)

For this prototype, octave control is a global performance offset for newly triggered notes.

### 4) Right-Hand Note Keys (`j i k o l p ;`)
Map to degrees `1..7` of the currently selected chord-scale bank:

- `j` -> 1
- `i` -> 2
- `k` -> 3
- `o` -> 4
- `l` -> 5
- `p` -> 6
- `;` -> 7

Register intent:
- For C through G banks, degree layout centers around middle C through middle G behavior.
- For A aeolian and B locrian, behavior includes A and B below middle C.
- Practical implementation should encode exact MIDI notes in each bank definition (see data model below), then convert to frequency for synthesis.

## Synth Engine
- Built-in audio synth inside `accordinez.py`
- Waveform: **sine**
- Polyphony: multiple simultaneous right-hand notes
- Audio output: real-time stream to default system audio device

## Data Model Requirement
Define the chord-scale bank as a **global variable at the top of the script**.

Example structure:

```python
CHORD_SCALE_BANKS = {
    "a": {"name": "C ionian", "degrees": [60, 62, 64, 65, 67, 69, 71]},
    "w": {"name": "D dorian", "degrees": [62, 64, 65, 67, 69, 71, 72]},
    "s": {"name": "E phrygian", "degrees": [64, 65, 67, 69, 71, 72, 74]},
    "e": {"name": "F lydian", "degrees": [65, 67, 69, 71, 72, 74, 76]},
    "d": {"name": "G mixolydian", "degrees": [67, 69, 71, 72, 74, 76, 77]},
    "r": {"name": "A aeolian", "degrees": [57, 59, 60, 62, 64, 65, 67]},
    "f": {"name": "B locrian", "degrees": [59, 60, 62, 64, 65, 67, 69]},
    "g": {"name": "E custom", "degrees": [64, 65, 68, 69, 71, 72, 74]},
}
```

Right-hand mapping:

```python
RIGHT_HAND_KEYS = {
    "j": 0, "i": 1, "k": 2, "o": 3, "l": 4, "p": 5, ";": 6
}
```

## State Model

- `active_bank_key`: current selected left-hand bank (`a/w/s/e/d/r/f/g`)
- `octave_offset`: integer semitone offset (default `0`, changed by `c`/`v`)
- `pitch_shift_active`: bool for `t` hold state
- `held_right_keys`: set of currently held right-hand keyboard keys
- `voices_by_right_key`: active synth voices keyed by right-hand key
  - each voice stores:
    - `base_midi` captured at note-on using current bank + octave offset (lazy behavior source)
    - `current_midi` that updates with real-time pitch shift
    - oscillator phase/frequency state for audio callback

## Event Rules

### Note-On (right hand)
1. Determine degree from `RIGHT_HAND_KEYS`.
2. Read current `active_bank_key`.
3. Resolve `base_midi = CHORD_SCALE_BANKS[active_bank_key]["degrees"][degree] + octave_offset`.
4. If `pitch_shift_active`, effective pitch starts at `base_midi + 1`, else `base_midi`.
5. Convert note to frequency and start sine-wave voice; store `base_midi`.

### Note-Off (right hand)
1. Release only that voice.
2. Other sustained voices remain unchanged.

### Bank switch (`a/w/s/e/d/r/f/g`)
1. Update `active_bank_key`.
2. Do **not** retune existing voices.
3. Future right-hand note-ons use new bank.

### Pitch shift press/release (`t`)
- On press: retune all active voices `+1 semitone`.
- On release: retune all active voices `-1 semitone` back instantly.
- Must be low-latency and audible in real time.

### Octave keys (`c`, `v`)
- Update `octave_offset` for subsequent note-ons.
- Do not force-retune already sounding voices in this prototype.

## Suggested Python Stack
- Input: `pynput`
- Audio: `sounddevice`
- DSP/math: `numpy`

## Implementation Steps
1. Create `accordinez.py`.
2. Add global constants at top:
   - `CHORD_SCALE_BANKS`
   - `RIGHT_HAND_KEYS`
   - left-hand control key sets (`BANK_KEYS`, `PITCH_KEY`, `OCTAVE_DOWN_KEY`, `OCTAVE_UP_KEY`)
3. Initialize runtime state variables.
4. Build synth backend abstraction using an audio callback:
   - polyphonic voice map
   - per-voice phase accumulator
   - summed sine output
5. Implement keyboard `on_press`:
   - bank select handling
   - pitch key press handling (`t`)
   - octave adjustments
   - right-hand note-on with lazy bank capture
6. Implement keyboard `on_release`:
   - right-hand note-off
   - pitch key release restores all active voices immediately
7. Add de-bounce/duplicate press guard so held OS key-repeat does not retrigger notes.
8. Print debug line per event (bank, note, effective pitch, active voices) for validation.
9. Test scenarios:
   - sustain notes, switch bank, replay subset -> cluster forms
   - hold notes, toggle `t` rapidly -> all active notes move in real time
   - bank switching never retunes sustained notes
10. Add graceful shutdown to release all notes on exit.

## Acceptance Tests
- Holding 4-note chord, switching bank, replaying 2 notes yields mixed-bank cluster.
- Holding any sustained notes, pressing/releasing `t` shifts all sounding notes up/down instantly by 1 semitone.
- Switching bank while notes sustain does not alter sustained note pitch.
- New right-hand note always reflects currently selected bank and current octave offset.
- Polyphony supports at least 6 simultaneous right-hand notes.
- Audio output is generated internally as summed sine waves (no external MIDI synth required).

## Non-Goals (Prototype)
- No UI required.
- No preset saving required.
- No advanced ADSR editing required.
- No MPE required.
