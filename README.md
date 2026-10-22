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

## Google Drive (configuración única)

Sin esto la app funciona igual y muestra “Google Drive: pendiente de configurar”.

1. Con la cuenta Google de Lilus, entrar a <https://script.google.com> → **Nuevo proyecto** → nombre “LILUS DESPACHOS Backend”.
2. Pegar el contenido de `backend/Code.gs` en `Código.gs` y guardar.
3. Ejecutar la función **`configurar`** (autorizar los permisos de Drive y Sheets). Crea la carpeta `LILUS - DESPACHOS` y la base central.
4. **Implementar → Nueva implementación → Aplicación web**: ejecutar como *Yo*, acceso *Cualquier usuario*. Copiar la URL `…/exec`.
5. Por cada celular: en `crearTokenCelular` cambiar el nombre, ejecutar y copiar el token del *Registro de ejecución*.
6. En el celular: **Configuración** → pegar URL y token → *Probar conexión* → *Guardar*.

Seguridad: conocer la URL no alcanza; sin un token válido todo se rechaza. Los celulares solo pueden **agregar**
despachos, nunca modificar o borrar. Tokens se crean/revocan solo desde el editor (`revocarTokenCelular`).
El envío es idempotente (UUID por despacho): reintentos no duplican archivos ni filas. No hay contraseñas ni
credenciales de Google en el código.

Estructura en Drive: `LILUS - DESPACHOS/AÑO/MES/FECHA/LOGÍSTICA/DESPACHO_001.pdf` y `.xlsx`, más la planilla
`LILUS - Base de despachos` (hojas *Despachos* y *Registros*, una fila por bulto).

## Técnica

Kotlin · Jetpack Compose · CameraX 1.4 · ML Kit Barcode (modelo incluido, funciona sin internet) · SQLite ·
WorkManager (reintentos) · OkHttp. Gradle 8.11.1 · AGP 8.7.3 · Kotlin 2.0.21 · JDK 17 · minSdk 24 · targetSdk 35.

Compilación: `.github/workflows/android.yml` (push a `main` o manual). Publica el APK como artefacto y corre:
- `app/src/test`: registro, duplicados, ceros iniciales, sufijos, persistencia y recuperación, recuento,
  cierre, sincronización con reintentos e idempotencia (backend simulado por HTTPS), Excel y paginación del PDF.
- `backend/backend.test.js`: tokens, idempotencia, reintentos parciales, códigos como texto.

Requiere teléfono físico (no automatizable en CI): cámara/lectura real, linterna, sonidos y vibración,
dibujo final del PDF e impresión, envío real a Google Drive.
