# AudioDSP Engine Pro — Android 14 / no-root build

Native Android audio DSP project targeting API 34.

## DSP engine

The project keeps the original software DSP engine:

- 32-band RBJ biquad equalizer
- Pre-gain
- Bass boost
- Bass / Mid / Treble tone stack
- MDRC (3-band dynamic range compressor)
- Auto Gain
- Virtualizer / stereo widening
- Master gain
- Balance
- Brickwall limiter
- Peak/RMS metering
- DataStore persistence and presets

## Default audio route: native Android effect

The default **Start DSP** action no longer creates a capture-and-replay loop. It attempts to attach Android's `DynamicsProcessing` effect to the global output-mix session and translates the app settings to the platform effect:

- 32 native EQ bands
- 3-band MBC
- native limiter
- per-channel input gain for master gain/balance
- optional native Virtualizer when the device exposes it

This is the closest public Android API path to a no-root system equalizer. Android documents session 0/global output effects, but also marks global insert effects such as Equalizer/BassBoost/Virtualizer as deprecated. Device firmware therefore decides whether this route is available. The app reports a real error if Android rejects the effect; it does not claim that DSP is active when it is not.

The app's custom DSP engine remains in the project and is used by the explicit diagnostic capture path. That path uses MediaProjection + AudioPlaybackCapture + AudioTrack and is intentionally not the default because source audio may remain audible and produce a dry/processed mix on some devices.

## Android 14 requirements

The project targets/compiles against API 34 and uses Java/Kotlin 17. Android 14 requires foreground services to declare their service types and corresponding permissions.

## GitHub Actions

The repository did not include a Gradle wrapper, so CI uses the official Gradle Actions `setup-gradle` action to install Gradle 8.2.2 directly. Push the repository to GitHub and open **Actions**; the workflow builds `app-debug.apk` and publishes it as a workflow artifact.

## Important device limitation

No-root system-wide processing cannot be guaranteed uniformly across Android 14 devices. The public `AudioEffect` API exposes audio-session effects, while global output-mix insert effects are deprecated. OEM audio HAL/effect implementations can therefore accept, restrict, replace, or reject this path.
