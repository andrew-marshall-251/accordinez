# Accordinez (Prototype)

Quick polyphonic sine-wave keyboard instrument in Python.

## Setup
```bash
pip install pynput sounddevice numpy
python accordinez.py
```

## Linux Keyboard Input
Accordinez currently uses `pynput` for global keyboard input. On Linux, that backend
requires an X11/Xorg session. It commonly will not receive key presses under Wayland,
because Wayland blocks global keyboard listeners by design.

If the app starts but keys do nothing, log into an Xorg/X11 session and run it there.
You can check your session with:

```bash
echo $XDG_SESSION_TYPE
```

## Controls
- Left hand scale bank: `a w s e d r f g`
- Pitch shift hold: `t` (up 1 semitone while held)
- Octave: `c` down, `v` up
- Right hand notes (degrees 1-7): `j i k o l p ;`

## How It Plays
- Bank switching is **lazy**: sustained notes keep old pitches; new notes use the new bank.
- `t` is **real-time global**: all sounding notes shift up/down instantly when pressed/released.
