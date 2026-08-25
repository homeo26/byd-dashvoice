# Third-party asset attribution

## Sound effects

The feedback cues in `res/raw/sfx_*.wav` are **generated speech**, not
third-party recordings, so no external attribution is required:

| File               | Words                    | Length |
|--------------------|--------------------------|--------|
| `sfx_listen.wav`   | "Listening"              | 0.66 s |
| `sfx_ok.wav`       | "Done"                   | 0.40 s |
| `sfx_unheard.wav`  | "Didn't catch that"      | 1.23 s |
| `sfx_refused.wav`  | "The car refused that"   | 1.40 s |

Synthesised with the Piper `amy` voice (US English, female), then trimmed of
leading and trailing silence and loudness normalised to -18 LUFS with a -2 dBTP
ceiling, and converted to 16-bit PCM mono WAV at 44.1 kHz for Android
`SoundPool`.

### Previously used, now removed

An earlier build used abstract tones from the **GUI sounds collection** by
Paolo D'Emilio (copyc4t), https://opengameart.org/content/gui-sounds-collection,
licensed CC-BY 3.0. They were replaced because the abstract beeps were hard to
distinguish from the stock assistant's own prompt. No files from that pack
remain in this repository.

## Speech recognition

**Vosk model `vosk-model-small-en-us-0.15`**
- Source: https://alphacephei.com/vosk/models
- License: Apache 2.0
