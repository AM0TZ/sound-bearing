# Sound Bearing

Android app that estimates which direction a sound comes from using the phone's two built-in microphones (GCC-PHAT time-difference), and draws it over the live camera view with loudness.

- Hold the phone in landscape. Clap to your right; if the marker goes left, tap **Swap L/R**.
- On start the app tries each Android audio source until one delivers true stereo (shown as **STEREO OK**).
- Front and back are mirrored with two mics. dB values are estimates until you set the offset against a sound level meter.

Every push to `main` builds the APK on GitHub Actions and attaches it to the **latest** release.
