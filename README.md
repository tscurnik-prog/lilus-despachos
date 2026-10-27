# LILUS DESPACHOS

App Android para registrar con la cámara los bultos que se entregan a cada logística:
escaneo, aviso de duplicados, contador, cierre del despacho, comprobante PDF para firmar,
Excel, historial y envío a Google Drive.

## Instalar el APK

1. Entrar a **Actions** del repositorio → última ejecución verde de **Android APK** → sección *Artifacts*.
2. Descargar `LILUS-DESPACHOS-apk-N` (es un .zip) y descomprimirlo en el celular o pasarlo por cable/WhatsApp.
3. Abrir `LILUS-DESPACHOS-N.apk` y aceptar “Instalar apps de origen desconocido” la primera vez.
4. Requiere Android 7.0 o superior. Las versiones nuevas se instalan **encima** sin perder datos (firma fija).

## Uso

1. Escribir el nombre, elegir empresa y logística → **INICIAR DESPACHO** (aceptar el permiso de cámara).
2. Apuntar al código de barras. Verde = registrado. Rojo = duplicado (no suma).
3. “Cargar a mano” para etiquetas ilegibles. “Ver / quitar” para sacar un código cargado por error (queda auditado).
4. **FINALIZAR DESPACHO** → revisar resumen → confirmar. Se genera el número (ej. `ENVIOPACK-20261022-001`), el PDF y el Excel.
5. Desde el resumen o el **Historial**: abrir, compartir o imprimir.

Si se cierra la app, se corta internet o se reinicia el celular, el despacho en curso queda guardado:
al volver a abrir aparece **CONTINUAR DESPACHO**.

## Google Drive (con la cuenta Google del celular)

En la app: **Configuración → CONECTAR CON GOOGLE** → elegir una cuenta del celular → Permitir.
No se pide contraseña. La app usa el permiso `drive.file`: solo ve y crea sus propios archivos.

Estructura en el Drive de esa cuenta:

```
despachos/
  LILUS - Base de despachos      (planilla: hojas "Registros", una fila por bulto, y "Despachos")
  Colecta Meli/
    2026-10-27 - Colecta Meli - 001.pdf
    2026-10-27 - Colecta Meli - 001.xlsx
  Flex/ ...
```

Envío automático en segundo plano con reintentos; sin internet queda pendiente. Idempotente: cada
archivo lleva el UUID del despacho y la planilla se revisa por UUID antes de agregar filas.
Si varios celulares usan la misma cuenta, comparten la carpeta `despachos`.

**Requisito único (administrador):** la app está registrada en Google Cloud (proyecto con Google Drive API
y Google Sheets API activas, pantalla de consentimiento publicada y un cliente OAuth tipo *Android* con
paquete `com.lilus.despachos` y la huella SHA-1 que imprime el workflow en el paso *Verificar APK*).
Sin eso, al conectar la app muestra "La app todavía no está habilitada en Google".

## Opciones de escaneo

**Confirmar cada lectura con SIGUIENTE** (Configuración, activado de fábrica): después de cada lectura la
cámara se pausa hasta tocar SIGUIENTE, para no leer otro paquete por mover el celular.

## Técnica

Kotlin · Jetpack Compose · CameraX 1.4 · ML Kit Barcode (modelo incluido, funciona sin internet) · SQLite ·
WorkManager (reintentos) · OkHttp · Google Identity (AuthorizationClient) + Drive v3 / Sheets v4. Gradle 8.11.1 · AGP 8.7.3 · Kotlin 2.0.21 · JDK 17 · minSdk 24 · targetSdk 35.

Compilación: `.github/workflows/android.yml` (push a `main` o manual). Publica el APK como artefacto y corre
las pruebas de `app/src/test`: registro, duplicados, ceros iniciales, sufijos, persistencia y recuperación,
recuento, cierre, migración de logísticas, modo SIGUIENTE, Excel, paginación del PDF y subida a Drive contra
un Drive/Sheets simulado (estructura de carpetas, idempotencia, reintentos, errores).

Requiere teléfono físico (no automatizable en CI): cámara/lectura real, linterna, sonidos y vibración,
dibujo final del PDF e impresión, conexión real con una cuenta Google y subida real a Drive.
