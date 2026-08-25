# Third-party asset attribution

## Sound effects

The UI feedback sounds in `res/raw/sfx_*.wav` are derived from:

**GUI sounds collection**
- Author: Paolo D'Emilio (copyc4t) — https://opengameart.org/users/copyc4t
- Source: https://opengameart.org/content/gui-sounds-collection
- License: Creative Commons Attribution 3.0 (CC-BY 3.0)
  https://creativecommons.org/licenses/by/3.0/

Modifications made: the original FLAC files were transcoded to 16-bit PCM
mono WAV at 44.1 kHz for Android `SoundPool` compatibility, and renamed to
match Android resource naming rules (`res/raw` requires lowercase names
without hyphens).

Original file → bundled name:

| Original              | Bundled         |
|-----------------------|-----------------|
| TF_GUI-Sound-1.flac   | sfx_01.wav      |
| TF_GUI-Sound-2.flac   | sfx_02.wav      |
| TF_GUI-Sound-3.flac   | sfx_03.wav      |
| TF_GUI-Sound-4.flac   | sfx_04.wav      |
| TF_GUI-Sound-5.flac   | sfx_05.wav      |
| TF_GUI-Sound-6.flac   | sfx_06.wav      |
| TF_GUI-Sound-7.flac   | sfx_07.wav      |
| TF_GUI-Sound-8.flac   | sfx_08.wav      |
| TF_GUI-Sound-9.flac   | sfx_09.wav      |
| TF_GUI-Sound-10.flac  | sfx_10.wav      |
| TF_GUI-Sound-11.flac  | sfx_11.wav      |
| TF_GUI-Sound-12.flac  | sfx_12.wav      |
| TF_GUI-Sound-13.flac  | sfx_13.wav      |
| TF_GUI-Sound-14.flac  | sfx_14.wav      |
| TF_GUI-Sound-15.flac  | sfx_15.wav      |
| TF_Buzz.flac          | sfx_buzz.wav    |
| TF_Meep.flac          | sfx_meep.wav    |

## Speech recognition

**Vosk model `vosk-model-small-en-us-0.15`**
- Source: https://alphacephei.com/vosk/models
- License: Apache 2.0
