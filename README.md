# Accordinez (Prototype)

Quick polyphonic sine-wave keyboard instrument in Python.

## Setup
```bash
pip install pynput sounddevice numpy
python accordinez.py
```

## Controls
- Left hand scale bank: `a w s e d r f`
- Pitch shift hold: `t` (up 1 semitone while held)
- Octave: `c` down, `v` up
- Right hand notes (degrees 1-7): `j i k o l p ;`

## How It Plays
- Bank switching is **lazy**: sustained notes keep old pitches; new notes use the new bank.
- `t` is **real-time global**: all sounding notes shift up/down instantly when pressed/released.
