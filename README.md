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
| EchoLink | `com.echolink.ptt.down` | `com.echolink.ptt.up` |
| VoxDMR | `com.voxdmr.ptt.DOWN` | `com.voxdmr.ptt.UP` |
| Zello | `com.zello.ptt.down` | `com.zello.ptt.up` |
| Generic POC apps | `android.intent.action.PTT.down` | `android.intent.action.PTT.up` |

Broadcasts to known apps are addressed to their package: since Android 8 an
implicit broadcast does not reach receivers declared in a manifest (EchoLink's
are), and an addressed one even starts the app's receiver when it is closed.

## Universal mode (default)

The bridge looks at the radio app in use (the one in front, or the last one
used when the home screen is in front or the screen is off) and picks the best
method by itself, with no setting to change when you switch apps:

1. **Its PTT intent**, if it has one (table above): works in the background and
   with the screen off.
2. **Its on-screen PTT button, held down without root**, if you taught it: an
   accessibility service keeps a finger on the button while the mic's PTT is
   pressed. Works with any app that has a hold-to-talk button on screen
   (Mumla, Peanut, BlueDV, DroidStar...). Needs the app in front and the
   screen on.
3. **Root mode** (optional), if set: hold a key code or a screen point.
4. Otherwise, **every PTT intent known**, for whatever app listens.

**Teaching a button:** in PTT Bridge tap *Learn a PTT button* and open the radio
app within 5 s (or, with the radio app in front, expand the bridge's
notification and tap *Learn PTT button*). A see-through blue layer appears: tap
the app's PTT button on it. Done once per app.

**The accessibility service** (Settings › Accessibility › PTT Bridge) is what
lets the bridge know the app in front and press screen buttons. It reads no
screen content. Two Android quirks with apps installed from outside the Play
Store:
- Android 13+ greys the switch out ("restricted setting"): open
  Settings › Apps › PTT Bridge, ⋮ menu › *Allow restricted settings*, then turn
  the service on.
- **Updating the app turns the service off**: turn it back on after each update.

Manual mode keeps the old behaviour: send to the ticked apps, plus root mode if
set.

## Compatibility

Found by decompiling each app, then tested on a OnePlus 8T (Android 16) by
simulating the mic's keys over adb (`cmd media_session dispatch fast-forward` /
`rewind`) and, for DVSwitch, with the real Abbree on the air.

| App (version) | Works | How | Tested |
|---|---|---|---|
| DVSwitch Mobile 2.0.7 | ✅ | own intent (also the generic one) | ✅ on the air with the Abbree |
| EchoLink 1.8.10 | ✅ | own intent, manifest receiver (setting "pttButton", on by default) | ✅ receives DOWN/UP (`startTx()`/`stopTX()`), even with the app closed |
| VoxDMR 0.15.3 | ✅ | own intent (also generic and Zello's) | ✅ receives DOWN/UP ("Ext PTT down/up"), even with the app closed |
| Zello | ✅ | `com.zello.ptt.*` (documented by Zello) | not tested |
| BlueDV AMBE 1.0.119 | ✅ root | physical key in the foreground: 27 (CAMERA), 131 (F1), 132 (F2), 134, 135, 139, 142, 228–230, 261, 276, 278, 280, 294, 300, 301, 305 | ✅ root key mode, code 27: "PTT ON" 73 ms after press, "PTT OFF" 24 ms after release |
| Peanut 1.81 | ✅ root | key learned in its setup, in the foreground | not tested (same mechanism as BlueDV) |
| Mumla 3.7.3 (Mumble) | ✅ | on-screen PTT button (set *Transmission mode: Push to talk*), taught in the universal mode; or root key | ✅ on the air with the Abbree, **without root** |
| DroidStar | ✅ root | only its on-screen TX button: root screen-point mode (turn off its TX toggle setting so the button is hold-to-talk); the no-root taught button should work too | ✅ root mode on the air with the Abbree; no-root not confirmed yet |


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
- **DroidStar** stays on "connecting" without any message when no vocoder is
  loaded: load one in its settings first.

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

PTT Bridge (en español, "PTT Puente") es una app mínima (≈50 KB, sin librerías)
que recoge esas teclas y las reenvía como los avisos de PTT que escuchan las
apps de radio, con pulsar y soltar por separado.

**Modo universal** (por defecto): el puente mira qué app de radio usas (la de
delante, o la última si estás en el escritorio o con la pantalla apagada) y
elige solo el método: su intent de PTT si lo tiene (DVSwitch, EchoLink, VoxDMR,
Zello: funciona con la pantalla apagada), o **mantener pulsado su botón de PTT
de la pantalla sin root** si se lo enseñaste (Mumla, Peanut, BlueDV,
DroidStar...). Para enseñarlo: en PTT Puente toca *Aprender un botón PTT*, abre
la app de radio antes de 5 s y toca su botón de PTT en la capa azul.

Necesita activar el **servicio de accesibilidad** de PTT Puente (no lee el
contenido de la pantalla). En Android 13+, si el interruptor sale gris: Ajustes
› Apps › PTT Puente › menú ⋮ › *Permitir ajustes restringidos*. **Al actualizar
la app, Android lo desactiva**: vuelve a activarlo.

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
