# Alarma Sentadillas (Android)

Alarma que **solo se apaga haciendo sentadillas**, al estilo de la misión "sentadillas" de Alarmy.
Detecta el movimiento con el acelerómetro (teléfono sostenido contra el pecho), se preconfigura con una fase
de calibración/validación formal (≥30 sentadillas, pruebas de falsación, sensibilidad y especificidad), y
mientras suena cierra el menú de apagado para que no se pueda apagar el teléfono.

## Obtener el APK

**Opción A — GitHub Actions (sin instalar nada):**
1. Crea un repositorio en GitHub (puede ser privado) y sube esta carpeta completa (incluida `.github/`).
2. En la pestaña **Actions** se ejecuta solo el flujo *Build APK* (~5 min). Al terminar, el APK queda en
   **Releases** (`SquatAlarm.apk`) y también como artefacto del flujo.
3. Copia el APK al teléfono e instálalo (permite "instalar apps desconocidas" cuando lo pida).

**Opción B — Android Studio:** `File → Open` → carpeta `SquatAlarm` → esperar el sync → **Run ▶** con el teléfono
por USB, o `Build → Build APK(s)` (sale en `app/build/outputs/apk/`).

Todas las builds (Actions, Android Studio, debug o release) usan la misma clave `keystore/squatalarm.jks`
(contraseña `squatalarm`), así que cualquier APK nuevo se instala encima del anterior sin desinstalar.

Requisitos: Android 8.0+ (API 26). Compila contra Android 15 (API 35).

## Primer uso (una sola vez)

1. **Permisos** → concede los 6 (notificaciones, alarmas exactas, pantalla completa, mostrar sobre otras apps,
   servicio de accesibilidad, sin restricción de batería). El de accesibilidad se activa en
   *Ajustes → Accesibilidad → Apps instaladas → Alarma Sentadillas*.
2. **Preconfigurar** (unos 5 min, ver abajo).
3. **Probar detección**: cada sentadilla debe sumar 1. "Exigencia" multiplica los umbrales por si quieres afinar a mano.
4. **Probar alarma en 10 s**: bloquea el teléfono y espera. Suena, vibra, aparece sobre el bloqueo y fija la pantalla.
   En modo prueba hay un botón para apagarla; en la alarma real no.
5. Pon la hora, los días, cuántas sentadillas exige y el **apagado de seguridad** (minutos tras los cuales se apaga
   sola si la detección fallara; es la única salida sin sentadillas), y activa el interruptor.

## Fase de preconfiguración

Es un pequeño estudio de validación del detector con tus propios datos:

| Paso | Qué haces | Qué pregunta la app | Para qué sirve |
|---|---|---|---|
| Series de 10 (30–60 sentadillas en total) | Sentadillas completas a tu ritmo; tocas «Terminé» | «La app contó X. ¿Cuántas hiciste realmente?» | Positivos verdaderos / falsos negativos (sensibilidad) |
| Falsación 1: quieto (20 s) | De pie, sin moverte | «¿Hiciste alguna sentadilla real?» (normalmente 0) | Falsos positivos por ruido |
| Falsación 2: caminar en el lugar (20 s) | Marcha enérgica | ídem | Falsos positivos por pasos |
| Falsación 3: sacudir el teléfono (20 s) | Arriba y abajo, rápido | ídem | Falsos positivos por sacudidas |
| Falsación 4: inclinarse (20 s) | Flexión de cintura lenta, rodillas rectas | ídem | Falsos positivos por movimientos lentos parecidos |
| Ajuste automático | — | Prioridad sensibilidad ↔ especificidad (peso) | Busca los umbrales que maximizan w·sens + (1−w)·esp |
| Validación | 10 sentadillas + 20 s caminando con los umbrales ya ajustados | Conteo real | Sens/esp con datos **no** usados en el ajuste |

Definiciones (ventanas del largo de una sentadilla tuya = tiempo de las series / sentadillas declaradas):

- **Sensibilidad** = sentadillas reales detectadas / sentadillas reales declaradas.
- **Especificidad** = ventanas sin sentadilla en las que no hubo falsa detección / total de ventanas sin sentadilla.
- Si en una serie la app contó más de lo que declaras, el exceso son falsos positivos; si contó menos, falsos negativos.
- Cada paso se puede repetir antes de confirmarlo. Al final puedes cambiar el peso y re-ajustar al instante, validar
  con una serie extra (mínimos sugeridos: sens 80 %, esp 90 %), guardar o repetir todo.

El ajuste re-analiza toda la señal grabada con cada combinación de umbrales (bajada 0,4–3,0 m/s², subida 0,4–3,0 m/s²,
tiempo mínimo bajada→subida 300/400/500 ms: 2.187 combinaciones, < 2 s en el teléfono) y, entre los empates, elige
el centro de la meseta para que el resultado sea robusto. La pantalla principal muestra con cuántas sentadillas se
preconfiguró y las métricas de ajuste y validación.

## Cómo funciona

- **Detección** (`squat/SquatCounter.kt`, Kotlin puro): proyecta la aceleración lineal sobre la gravedad (vale en
  cualquier orientación), la filtra (~2-3 Hz) y busca el patrón de una sentadilla: lóbulo negativo (empieza a bajar)
  seguido de lóbulo positivo (frena abajo y se impulsa). Sacudidas y pasos se descartan por ser demasiado rápidos.
  `squat/SquatDetector.kt` es la capa de sensores; `squat/Tuner.kt` el replay, las métricas y la búsqueda en rejilla.
- **Alarma** (`alarm/`): `AlarmManager.setAlarmClock` → `AlarmReceiver` → `AlarmService` (primer plano: sonido de
  alarma a volumen máximo, vibración, wakelock) → `AlarmActivity` sobre la pantalla de bloqueo. Un vigilante cada 4 s
  vuelve a subir el volumen, reenciende la pantalla y trae la alarma al frente si la minimizas. El progreso de
  sentadillas se conserva si la pantalla se apaga.
- **Que no se pueda apagar el teléfono**:
  - `block/PowerMenuBlockerService` (accesibilidad): en cuanto aparece el menú de apagado/reinicio lo cierra con *Atrás*.
  - La pantalla de alarma queda **fijada** (`startLockTask`): Inicio, Recientes y notificaciones quedan bloqueados.
    La primera vez Android pide confirmar "Fijar pantalla": acepta.
  - Atrás y los botones de volumen no hacen nada; la alarma usa el canal de alarma, que ignora No molestar.
  - Si aun así el teléfono se reinicia (botón de encendido mantenido 10+ s, que ninguna app puede impedir),
    `BootReceiver` vuelve a hacer sonar la alarma al encender.
  - Red de seguridad: la alarma se apaga sola a los N minutos configurados ("Apagado de seguridad", 5–180).

### Bloqueo al 100 % (opcional, avanzado)

Android solo desactiva de verdad el menú de encendido para apps "device owner". Requiere un teléfono **sin cuentas
configuradas** (o un perfil nuevo) y ADB:

```
adb shell dpm set-device-owner com.squatalarm.app/.block.AdminReceiver
```

Con eso la pantalla de alarma entra en modo kiosco sin diálogo y el sistema oculta por completo el menú de apagado.
Para revertir: `adb shell dpm remove-active-admin com.squatalarm.app/.block.AdminReceiver`.

## Pruebas del núcleo sin SDK de Android

`core-check/run.sh` compila `SquatCounter`, `Calibration` y `Tuner` con el Kotlin embebido de Gradle y corre
asserts con señales sintéticas (sentadillas, sacudidas, caminar, inclinarse, conteos corregidos por el usuario,
efecto del peso sens/esp). Requiere solo `gradle` en el PATH.

## Limitaciones conocidas

- Mover el teléfono arriba y abajo con los brazos al ritmo de una sentadilla también cuenta (igual que en Alarmy);
  no se incluye como prueba de falsación porque ningún umbral de aceleración lo separa.
- Algunas capas de fabricante (Xiaomi, Huawei) matan servicios en segundo plano: concede "sin restricción de batería"
  y, si existe, "inicio automático".
- El menú de apagado se cierra en cuanto aparece, pero en teléfonos muy lentos puede verse un instante.
- El código Android/Compose no se pudo compilar en el entorno donde se generó (sin SDK); el núcleo sí. Si Android
  Studio o Actions marcan algo, suele ser una versión de AGP/Kotlin: acepta la que sugieran.
