# VoiceSculk

Paper 26.3 / Java 25; compile-only dependencies are the installed Survival Paper libraries and Simple Voice Chat 2.6.25. Build from the repository root:

```powershell
docker run --rm -v "${PWD}:/workspace" -w /workspace/plugins-src/voice-sculk gradle:9-jdk25 gradle --no-daemon clean test build
```

Copy `build/libs/voice-sculk.jar` to `config/survival-plugins/`. Configure `data/survival/plugins/VoiceSculk/config.yml` and restart Survival. `radius` (default 8) limits the distance from the speaker to the sensor center; `whisper-triggers` and `group-triggers` default to false. `cooldown-ms` throttles each speaker. The sound is not decoded. Vanilla vibration range, occlusion, sensor cooldowns and calibrated sensor filtering still apply, so a larger radius cannot extend vanilla range. Test with a real Voice Chat client and sculk sensors.
