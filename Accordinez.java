import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

public final class Accordinez {
    private static final Map<Character, Bank> CHORD_SCALE_BANKS = new LinkedHashMap<>();
    private static final Map<Character, Integer> RIGHT_HAND_KEYS = new LinkedHashMap<>();
    private static final Set<Character> BANK_KEYS = new HashSet<>();

    private static final char PITCH_KEY = 't';
    private static final char OCTAVE_DOWN_KEY = 'c';
    private static final char OCTAVE_UP_KEY = 'v';

    private static final int SAMPLE_RATE = 48_000;
    private static final int BLOCK_SIZE = 256;
    private static final double MASTER_GAIN = 0.2;
    private static final int MIN_MIDI = 0;
    private static final int MAX_MIDI = 127;

    static {
        CHORD_SCALE_BANKS.put('a', new Bank("C ionian", new int[] {60, 62, 64, 65, 67, 69, 71}));
        CHORD_SCALE_BANKS.put('w', new Bank("D dorian", new int[] {62, 64, 65, 67, 69, 71, 72}));
        CHORD_SCALE_BANKS.put('s', new Bank("E phrygian", new int[] {64, 65, 67, 69, 71, 72, 74}));
        CHORD_SCALE_BANKS.put('e', new Bank("F lydian", new int[] {65, 67, 69, 71, 72, 74, 76}));
        CHORD_SCALE_BANKS.put('d', new Bank("G mixolydian", new int[] {67, 69, 71, 72, 74, 76, 77}));
        CHORD_SCALE_BANKS.put('r', new Bank("A aeolian", new int[] {69, 71, 72, 74, 76, 77, 79}));
        CHORD_SCALE_BANKS.put('f', new Bank("B locrian", new int[] {71, 72, 74, 76, 77, 79, 81}));
        CHORD_SCALE_BANKS.put('g', new Bank("E custom", new int[] {64, 65, 68, 69, 71, 72, 74}));
        BANK_KEYS.addAll(CHORD_SCALE_BANKS.keySet());

        RIGHT_HAND_KEYS.put('j', 0);
        RIGHT_HAND_KEYS.put('i', 1);
        RIGHT_HAND_KEYS.put('k', 2);
        RIGHT_HAND_KEYS.put('o', 3);
        RIGHT_HAND_KEYS.put('l', 4);
        RIGHT_HAND_KEYS.put('p', 5);
        RIGHT_HAND_KEYS.put(';', 6);
    }

    private final Object lock = new Object();
    private final Set<Character> heldRightKeys = new HashSet<>();
    private final Set<Character> heldControlKeys = new HashSet<>();
    private final Map<Character, Voice> voicesByRightKey = new HashMap<>();

    private char activeBankKey = 'a';
    private int octaveOffset = 0;
    private boolean pitchShiftActive = false;
    private volatile boolean running = true;

    private SineSynth synth;
    private JLabel statusLabel;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                new Accordinez().start();
            } catch (LineUnavailableException ex) {
                System.err.println("Could not open audio output: " + ex.getMessage());
                System.exit(1);
            }
        });
    }

    private void start() throws LineUnavailableException {
        synth = new SineSynth();
        synth.start();

        JFrame frame = new JFrame("Accordinez");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(440, 220));
        frame.setContentPane(buildPanel());
        frame.addKeyListener(new KeyHandler());
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent event) {
                shutdown();
            }
        });
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        frame.requestFocusInWindow();

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
        debug("ready");
    }

    private JPanel buildPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(24, 25, 27));
        panel.setBorder(BorderFactory.createEmptyBorder(36, 36, 36, 36));
        panel.setFocusable(true);

        JLabel title = new JLabel("Accordinez", SwingConstants.CENTER);
        title.setForeground(new Color(238, 238, 232));
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 32));

        statusLabel = new JLabel("", SwingConstants.CENTER);
        statusLabel.setForeground(new Color(174, 183, 191));
        statusLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 15));

        panel.add(title, BorderLayout.CENTER);
        panel.add(statusLabel, BorderLayout.SOUTH);
        return panel;
    }

    private static double midiToFreq(int midiNote) {
        return 440.0 * Math.pow(2.0, (midiNote - 69) / 12.0);
    }

    private int clampMidi(int midiNote) {
        return Math.max(MIN_MIDI, Math.min(MAX_MIDI, midiNote));
    }

    private int effectiveNote(int baseMidi) {
        return clampMidi(baseMidi + (pitchShiftActive ? 1 : 0));
    }

    private void updateStatus() {
        if (statusLabel == null) {
            return;
        }
        Bank bank = CHORD_SCALE_BANKS.get(activeBankKey);
        String text = String.format("bank %c:%s   oct %+d   shift %s   voices %d",
                activeBankKey, bank.name, octaveOffset, pitchShiftActive ? "on" : "off", voicesByRightKey.size());
        statusLabel.setText(text);
    }

    private void debug(String label) {
        StringBuilder active = new StringBuilder();
        List<Character> keys = new ArrayList<>(voicesByRightKey.keySet());
        keys.sort(Character::compareTo);
        for (char key : keys) {
            Voice voice = voicesByRightKey.get(key);
            if (active.length() > 0) {
                active.append(", ");
            }
            active.append(key)
                    .append(":")
                    .append(voice.currentMidi)
                    .append("(")
                    .append(voice.bankKeyAtNoteOn)
                    .append(")");
        }
        if (active.length() == 0) {
            active.append("-");
        }

        Bank bank = CHORD_SCALE_BANKS.get(activeBankKey);
        System.out.printf("[%s] bank=%c:%s oct=%+d shift=%s voices=%s%n",
                label, activeBankKey, bank.name, octaveOffset, pitchShiftActive, active);
        updateStatus();
    }

    private void startVoice(char rightKey) {
        if (voicesByRightKey.containsKey(rightKey)) {
            return;
        }

        int degreeIdx = RIGHT_HAND_KEYS.get(rightKey);
        int baseMidi = CHORD_SCALE_BANKS.get(activeBankKey).degrees[degreeIdx] + octaveOffset;
        baseMidi = clampMidi(baseMidi);
        int currentMidi = effectiveNote(baseMidi);

        Voice voice = new Voice(rightKey, activeBankKey, degreeIdx, baseMidi, currentMidi,
                midiToFreq(baseMidi), midiToFreq(currentMidi));
        voicesByRightKey.put(rightKey, voice);
        debug("note_on " + rightKey + "->" + currentMidi);
    }

    private void stopVoice(char rightKey) {
        Voice voice = voicesByRightKey.remove(rightKey);
        if (voice == null) {
            return;
        }
        debug("note_off " + rightKey + "->" + voice.currentMidi);
    }

    private void retuneAllActiveVoices() {
        for (Voice voice : voicesByRightKey.values()) {
            int targetMidi = effectiveNote(voice.baseMidi);
            voice.currentMidi = targetMidi;
            voice.currentFreq = midiToFreq(targetMidi);
        }
        debug("retune_all");
    }

    private void handleBankSelect(char ch) {
        if (ch == activeBankKey) {
            return;
        }
        activeBankKey = ch;
        debug("bank_switch " + ch);
    }

    private void handlePitchKeyDown() {
        if (pitchShiftActive) {
            return;
        }
        pitchShiftActive = true;
        retuneAllActiveVoices();
    }

    private void handlePitchKeyUp() {
        if (!pitchShiftActive) {
            return;
        }
        pitchShiftActive = false;
        retuneAllActiveVoices();
    }

    private void allNotesOff() {
        voicesByRightKey.clear();
        heldRightKeys.clear();
        debug("all_notes_off");
    }

    private void onPress(char rawChar) {
        char ch = Character.toLowerCase(rawChar);
        synchronized (lock) {
            if (BANK_KEYS.contains(ch) || ch == PITCH_KEY || ch == OCTAVE_DOWN_KEY || ch == OCTAVE_UP_KEY) {
                if (heldControlKeys.contains(ch)) {
                    return;
                }
                heldControlKeys.add(ch);
            }

            if (BANK_KEYS.contains(ch)) {
                handleBankSelect(ch);
                return;
            }

            if (ch == PITCH_KEY) {
                handlePitchKeyDown();
                return;
            }

            if (ch == OCTAVE_DOWN_KEY) {
                octaveOffset -= 12;
                debug("octave_down");
                return;
            }

            if (ch == OCTAVE_UP_KEY) {
                octaveOffset += 12;
                debug("octave_up");
                return;
            }

            if (RIGHT_HAND_KEYS.containsKey(ch)) {
                if (heldRightKeys.contains(ch)) {
                    return;
                }
                heldRightKeys.add(ch);
                startVoice(ch);
            }
        }
    }

    private void onRelease(char rawChar) {
        char ch = Character.toLowerCase(rawChar);
        synchronized (lock) {
            if (ch == PITCH_KEY) {
                heldControlKeys.remove(ch);
                handlePitchKeyUp();
                return;
            }

            if (ch == OCTAVE_DOWN_KEY || ch == OCTAVE_UP_KEY || BANK_KEYS.contains(ch)) {
                heldControlKeys.remove(ch);
                return;
            }

            if (RIGHT_HAND_KEYS.containsKey(ch)) {
                heldRightKeys.remove(ch);
                stopVoice(ch);
            }
        }
    }

    private void shutdown() {
        if (!running) {
            return;
        }
        running = false;
        synchronized (lock) {
            allNotesOff();
        }
        if (synth != null) {
            synth.stop();
        }
    }

    private final class KeyHandler extends KeyAdapter {
        @Override
        public void keyPressed(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                shutdown();
                System.exit(0);
                return;
            }

            char ch = event.getKeyChar();
            if (ch != KeyEvent.CHAR_UNDEFINED) {
                onPress(ch);
            }
        }

        @Override
        public void keyReleased(KeyEvent event) {
            char ch = event.getKeyChar();
            if (ch != KeyEvent.CHAR_UNDEFINED) {
                onRelease(ch);
            }
        }
    }

    private final class SineSynth {
        private final SourceDataLine line;
        private final Thread thread;

        SineSynth() throws LineUnavailableException {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, BLOCK_SIZE * 4);
            thread = new Thread(this::runAudio, "accordinez-audio");
            thread.setDaemon(true);
        }

        void start() {
            line.start();
            thread.start();
        }

        void stop() {
            line.stop();
            line.flush();
            line.close();
        }

        private void runAudio() {
            byte[] buffer = new byte[BLOCK_SIZE * 2];
            while (running) {
                render(buffer);
                line.write(buffer, 0, buffer.length);
            }
        }

        private void render(byte[] buffer) {
            synchronized (lock) {
                List<Voice> voices = new ArrayList<>(voicesByRightKey.values());
                if (voices.isEmpty()) {
                    for (int i = 0; i < buffer.length; i++) {
                        buffer[i] = 0;
                    }
                    return;
                }

                double perVoiceGain = MASTER_GAIN / Math.sqrt(voices.size());
                int byteIndex = 0;
                for (int frame = 0; frame < BLOCK_SIZE; frame++) {
                    double mix = 0.0;
                    for (Voice voice : voices) {
                        mix += perVoiceGain * Math.sin(voice.phase);
                        double inc = (2.0 * Math.PI * voice.currentFreq) / SAMPLE_RATE;
                        voice.phase = (voice.phase + inc) % (2.0 * Math.PI);
                    }
                    mix = Math.max(-1.0, Math.min(1.0, mix));
                    short sample = (short) Math.round(mix * Short.MAX_VALUE);
                    buffer[byteIndex++] = (byte) (sample & 0xff);
                    buffer[byteIndex++] = (byte) ((sample >>> 8) & 0xff);
                }
            }
        }
    }

    private static final class Bank {
        final String name;
        final int[] degrees;

        Bank(String name, int[] degrees) {
            this.name = name;
            this.degrees = degrees;
        }
    }

    private static final class Voice {
        final char rightKey;
        final char bankKeyAtNoteOn;
        final int degreeIdx;
        final int baseMidi;
        final double baseFreq;
        int currentMidi;
        double currentFreq;
        double phase;

        Voice(char rightKey, char bankKeyAtNoteOn, int degreeIdx, int baseMidi,
                int currentMidi, double baseFreq, double currentFreq) {
            this.rightKey = rightKey;
            this.bankKeyAtNoteOn = bankKeyAtNoteOn;
            this.degreeIdx = degreeIdx;
            this.baseMidi = baseMidi;
            this.currentMidi = currentMidi;
            this.baseFreq = baseFreq;
            this.currentFreq = currentFreq;
            this.phase = 0.0;
        }
    }
}
