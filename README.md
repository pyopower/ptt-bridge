# PTT Bridge

**Use the PTT button of a Bluetooth speaker-mic (like the Abbree) with DVSwitch Mobile, Zello and other radio apps on Android.**

[Español más abajo](#español)

Many Bluetooth speaker-mics sold for POC radios don't send a "PTT" that radio
apps understand. The **Abbree** (it shows up as `KST_vHMIC010`), connected to a
phone, sends its PTT over AVRCP as **FAST FORWARD when pressed** and **REWIND
when released**. Radio apps ignore those keys, so the button does nothing.

PTT Bridge is a tiny app (≈40 KB, no libraries) that catches those keys and
re-sends them as the PTT broadcasts radio apps listen to, with separate press
and release:

| Target | Press | Release |
|---|---|---|
| DVSwitch Mobile | `org.dvswitch.intent.action.PTT_KEY_DOWN` | `org.dvswitch.intent.action.PTT_KEY_UP` |
| Generic POC apps | `android.intent.action.PTT.down` | `android.intent.action.PTT.up` |
| Zello | `com.zello.ptt.down` | `com.zello.ptt.up` |

Tested with an Abbree speaker-mic and DVSwitch Mobile 2.0.7 on a OnePlus 8T
(Android 16).

## Install

Download the APK from [Releases](../../releases) and install it. Android 5.0 or newer.

## Use

1. Pair the speaker-mic with the phone as usual.
2. Open PTT Bridge and tick the app(s) to send the PTT to (DVSwitch comes ticked).
   The bridge keeps running in the background with a notification.
3. Use your radio app normally and press the mic's PTT.

Notes:

- **Only one app gets the media buttons at a time.** Fully close any other app
  that also handles the mic's buttons.
- In **DVSwitch**, use the mic for audio only. Do **not** select it in DVSwitch's
  own Bluetooth PTT menu: that one is for BLE mics and keeps failing to connect
  ("failed to connect", GATT 133) to a classic Bluetooth mic.
- On the Abbree, **P1** sends `AT+BLDN`, which Android itself handles as
  "redial the last number". No app can stop that, so don't use P1.
- **+ / −** are the normal phone volume.
- If the release is ever lost (mic out of range, flat battery), the bridge
  releases the PTT on its own after 180 s.

## How it works

The keys arrive through a `MediaSession`. The tricky part is that Android
delivers media keys to the app that most recently **started playing** among
those currently playing. DVSwitch starts playing audio the moment it transmits,
so it would steal the keys and the REWIND (release) would go to DVSwitch: the
PTT would never release. To prevent that, the bridge keeps a looping, silent
`AudioTrack` (media usage, no audio focus requested, so it interrupts nobody)
and restarts it every 3 s, and every 0.7 s while transmitting. The broadcasts
use `FLAG_RECEIVER_FOREGROUND`; without it they arrived ~0.5 s late.

### What the Abbree sends

Measured with `btmon` on Linux and on Android:

| Connected profiles | PTT | P1 | + / − |
|---|---|---|---|
| A2DP + AVRCP + HFP (a phone) | AVRCP FAST FORWARD on press (tap + ~0.5 s hold), REWIND on release | HFP `AT+BLDN` | volume |
| HFP only | HFP `AT+BLDN` on press and again on release | — | `AT+VGS` |

## Other speaker-mics

If your mic sends other keys, run `adb logcat` while pressing it and open an
issue with what you see, or send a pull request: the key handling is in
`BridgeService.onKey()` and the targets in `BridgeService.TARGETS`.

## Build

```sh
./gradlew assembleRelease
```

Without a release keystore the build is signed with the debug key.

## License

Apache 2.0. See [LICENSE](LICENSE).

---

## Español

**Usa el botón PTT de un micro-altavoz Bluetooth (como el Abbree) con DVSwitch Mobile, Zello y otras apps de radio en Android.**

Muchos micros Bluetooth pensados para radios POC no mandan un "PTT" que
entiendan las apps de radio. El **Abbree** (aparece como `KST_vHMIC010`),
conectado a un móvil, manda el PTT por AVRCP como **AVANCE RÁPIDO al pulsar** y
**RETROCESO al soltar**. Las apps de radio ignoran esas teclas y el botón no hace
nada.

PTT Bridge (en español, "PTT Puente") es una app mínima (≈40 KB, sin librerías)
que recoge esas teclas y las reenvía como los avisos de PTT que escuchan las
apps de radio, con pulsar y soltar por separado (ver la tabla de arriba).

Probada con un Abbree y DVSwitch Mobile 2.0.7 en un OnePlus 8T (Android 16).

### Uso

1. Empareja el micro con el móvil como siempre.
2. Abre PTT Puente y marca a qué app(s) mandar el PTT (DVSwitch viene marcada).
   Se queda funcionando en segundo plano con una notificación.
3. Usa tu app de radio con normalidad y pulsa el PTT del micro.

Avisos:

- **Android solo entrega los botones a una app a la vez.** Cierra del todo las
  demás apps que también usen los botones del micro.
- En **DVSwitch**, usa el micro solo para el audio. **No** lo elijas en su menú
  de PTT Bluetooth: es para micros BLE y con uno de Bluetooth clásico solo da
  "failed to connect" (GATT 133).
- En el Abbree, **P1** manda `AT+BLDN`, que Android interpreta como "rellamar al
  último número". Ninguna app puede evitarlo: no uses P1.
- **+ / −** son el volumen normal del móvil.
- Si alguna vez se pierde el soltado (micro fuera de alcance, sin batería), el
  puente suelta el PTT solo a los 180 s.

### Otros micros

Si tu micro manda otras teclas, mira `adb logcat` mientras lo pulsas y abre un
issue con lo que salga, o manda un pull request: las teclas se tratan en
`BridgeService.onKey()` y los destinos están en `BridgeService.TARGETS`.
