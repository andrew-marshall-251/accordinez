# Accordinez (Prototype)

Quick polyphonic sine-wave keyboard instrument in Java.

## Setup
```bash
./build.sh
java -jar build/accordinez.jar
```

## Linux Keyboard Input / Wayland
Accordinez now uses normal Java desktop key events instead of a global keyboard hook.
That means it works in Wayland sessions as long as the Accordinez window is focused.

Wayland blocks global keyboard listeners by design, so the app cannot receive keys
while another app has focus.

## Controls
- Left hand scale bank: `a w s e d r f g`
- Pitch shift hold: `t` (up 1 semitone while held)
- Octave: `c` down, `v` up
- Right hand notes (degrees 1-7): `j i k o l p ;`
- Exit: `Esc`

## How It Plays
- Bank switching is **lazy**: sustained notes keep old pitches; new notes use the new bank.
- `t` is **real-time global**: all sounding notes shift up/down instantly when pressed/released.
