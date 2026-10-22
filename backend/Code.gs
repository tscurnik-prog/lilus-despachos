/**
 * LILUS DESPACHOS — Backend en Google Apps Script
 *
 * Guarda en Google Drive los comprobantes (PDF y XLSX) de cada despacho cerrado
 * y registra cada bulto en una base central (Google Sheets).
 *
 * SEGURIDAD
 *  - Los celulares sólo pueden ENVIAR despachos nuevos (acción submitDispatch) y probar
 *    la conexión (ping). No pueden modificar ni borrar registros existentes.
 *  - Cada celular usa su propio token. Los tokens se crean y revocan SOLO desde el editor
 *    de Apps Script (funciones de administración de abajo), con la cuenta dueña.
 *  - Conocer la URL no alcanza: sin un token válido todo se rechaza.
 *  - Envío idempotente: el despacho se identifica por su UUID. Si llega dos veces
 *    (reintento, respuesta perdida), no se duplican archivos ni filas.
 *
 * INSTALACIÓN: ver backend/README.md
 */

var ROOT_FOLDER_NAME = 'LILUS - DESPACHOS';
var DB_NAME = 'LILUS - Base de despachos';
var SHEET_DESPACHOS = 'Despachos';
var SHEET_REGISTROS = 'Registros';
var HEAD_DESPACHOS = ['UUID', 'ID de despacho', 'Fecha', 'Hora inicio', 'Hora fin', 'Empresa', 'Logística', 'Operario',
  'Bultos', 'Duplicados detectados', 'PDF', 'XLSX', 'Carpeta', 'Fecha de sincronización'];
var HEAD_REGISTROS = ['ID de despacho', 'Fecha', 'Hora', 'Empresa', 'Logística', 'Operario', 'Código', 'Fecha de sincronización'];

// =====================================================================
//  Entrada web
// =====================================================================

function doPost(e) {
  var out;
  try {
    var body = JSON.parse((e && e.postData && e.postData.contents) || '{}');
    out = handle_(body);
  } catch (err) {
    out = { ok: false, error: 'invalid_payload', message: 'JSON inválido' };
  }
  return ContentService.createTextOutput(JSON.stringify(out)).setMimeType(ContentService.MimeType.JSON);
}

function doGet() {
  return ContentService.createTextOutput(JSON.stringify({ ok: true, service: 'LILUS DESPACHOS' }))
    .setMimeType(ContentService.MimeType.JSON);
}

function handle_(body) {
  if (!isAuthorized_(body.token)) return { ok: false, error: 'unauthorized' };
  if (body.action === 'ping') return { ok: true, rootUrl: rootFolder_().getUrl() };
  if (body.action === 'submitDispatch') return submitDispatch_(body);
  return { ok: false, error: 'invalid_payload', message: 'Acción desconocida' };
}

function isAuthorized_(token) {
  if (!token || typeof token !== 'string' || token.length < 20) return false;
  var list = deviceTokens_();
  for (var i = 0; i < list.length; i++) {
    if (list[i].token === token && !list[i].revoked) return true;
  }
  return false;
}

// =====================================================================
//  Envío de despacho (idempotente)
// =====================================================================

function submitDispatch_(body) {
  var d = body.dispatch;
  var err = validate_(d);
  if (err) return { ok: false, error: 'invalid_payload', message: err };

  var lock = LockService.getScriptLock();
  if (!lock.tryLock(30000)) return { ok: false, error: 'busy' };
  try {
    var db = database_();
    var shD = db.getSheetByName(SHEET_DESPACHOS);

    // ¿Ya existe? -> devolver lo guardado, sin duplicar
    var existing = findRowByUuid_(shD, d.uuid);
    if (existing) {
      return { ok: true, status: 'already_exists', id: existing[1], pdfUrl: existing[10], xlsxUrl: existing[11], folderUrl: existing[12] };
    }

    var folder = folderFor_(d);
    var seq = String(d.id).split('-').pop();
    if (!/^\d+$/.test(seq)) seq = '001';
    var baseName = 'DESPACHO_' + seq;
    var pdf = body.pdfBase64 ? saveFile_(folder, baseName, '.pdf', 'application/pdf', body.pdfBase64, d.uuid) : null;
    var xlsx = body.xlsxBase64 ? saveFile_(folder, baseName, '.xlsx',
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', body.xlsxBase64, d.uuid) : null;

    var now = Utilities.formatDate(new Date(), Session.getScriptTimeZone() || 'America/Argentina/Buenos_Aires', 'dd/MM/yyyy HH:mm:ss');

    // Registros: una fila por bulto. Todo como TEXTO (formato @) para conservar ceros y sufijos.
    var shR = db.getSheetByName(SHEET_REGISTROS);
    if (d.items.length > 0) {
      var rows = d.items.map(function (it) {
        return [String(d.id), String(it.date), String(it.time), String(d.empresa), String(d.logistica), String(d.operario), String(it.code), now];
      });
      var start = shR.getLastRow() + 1;
      var range = shR.getRange(start, 1, rows.length, HEAD_REGISTROS.length);
      range.setNumberFormat('@');
      range.setValues(rows);
    }

    // La fila del despacho se escribe AL FINAL: marca que todo lo anterior quedó guardado.
    shD.appendRow([String(d.uuid), String(d.id), String(d.fecha), String(d.horaInicio), String(d.horaFin), String(d.empresa),
      String(d.logistica), String(d.operario), d.items.length, Number(d.duplicados || 0),
      pdf ? pdf.getUrl() : '', xlsx ? xlsx.getUrl() : '', folder.getUrl(), now]);

    return { ok: true, status: 'created', id: d.id, pdfUrl: pdf ? pdf.getUrl() : '', xlsxUrl: xlsx ? xlsx.getUrl() : '', folderUrl: folder.getUrl() };
  } finally {
    lock.releaseLock();
  }
}

function validate_(d) {
  if (!d || typeof d !== 'object') return 'Falta el despacho';
  var req = ['uuid', 'id', 'empresa', 'logistica', 'operario', 'fecha', 'year', 'month', 'isoDay'];
  for (var i = 0; i < req.length; i++) if (!d[req[i]]) return 'Falta el campo ' + req[i];
  if (!Array.isArray(d.items)) return 'Faltan los códigos';
  if (d.items.length > 20000) return 'Demasiados códigos';
  for (var j = 0; j < d.items.length; j++) {
    if (typeof d.items[j].code !== 'string' || d.items[j].code.length === 0) return 'Código inválido en la fila ' + (j + 1);
  }
  return null;
}

function findRowByUuid_(sheet, uuid) {
  var last = sheet.getLastRow();
  if (last < 2) return null;
  var values = sheet.getRange(2, 1, last - 1, HEAD_DESPACHOS.length).getValues();
  for (var i = 0; i < values.length; i++) if (String(values[i][0]) === String(uuid)) return values[i];
  return null;
}

/** Reutiliza el archivo si un intento anterior ya lo había creado (descripción = UUID). */
function saveFile_(folder, baseName, ext, mime, b64, uuid) {
  var it = folder.getFiles();
  var taken = {};
  while (it.hasNext()) {
    var f = it.next();
    if (f.getDescription() === uuid && f.getName().slice(-ext.length) === ext) return f;
    taken[f.getName()] = true;
  }
  var name = baseName + ext;
  if (taken[name]) name = baseName + '_' + uuid.slice(0, 8) + ext;
  var blob = Utilities.newBlob(Utilities.base64Decode(b64), mime, name);
  var file = folder.createFile(blob);
  file.setDescription(uuid);
  return file;
}

// /LILUS - DESPACHOS/AÑO/MES/FECHA/LOGÍSTICA
function folderFor_(d) {
  var f = rootFolder_();
  [String(d.year), String(d.month), String(d.isoDay), String(d.logistica)].forEach(function (name) {
    f = childFolder_(f, name);
  });
  return f;
}

function childFolder_(parent, name) {
  var it = parent.getFoldersByName(name);
  return it.hasNext() ? it.next() : parent.createFolder(name);
}

function rootFolder_() {
  var props = PropertiesService.getScriptProperties();
  var id = props.getProperty('ROOT_FOLDER_ID');
  if (id) return DriveApp.getFolderById(id);
  var it = DriveApp.getFoldersByName(ROOT_FOLDER_NAME);
  var folder = it.hasNext() ? it.next() : DriveApp.createFolder(ROOT_FOLDER_NAME);
  props.setProperty('ROOT_FOLDER_ID', folder.getId());
  return folder;
}

function database_() {
  var props = PropertiesService.getScriptProperties();
  var id = props.getProperty('DB_SHEET_ID');
  var ss;
  if (id) {
    ss = SpreadsheetApp.openById(id);
  } else {
    ss = SpreadsheetApp.create(DB_NAME);
    DriveApp.getFileById(ss.getId()).moveTo(rootFolder_());
    props.setProperty('DB_SHEET_ID', ss.getId());
  }
  ensureSheet_(ss, SHEET_DESPACHOS, HEAD_DESPACHOS);
  ensureSheet_(ss, SHEET_REGISTROS, HEAD_REGISTROS);
  return ss;
}

function ensureSheet_(ss, name, headers) {
  var sh = ss.getSheetByName(name);
  if (!sh) {
    sh = ss.insertSheet(name);
    sh.getRange(1, 1, 1, headers.length).setValues([headers]).setFontWeight('bold')
      .setBackground('#0B2545').setFontColor('#FFFFFF');
    sh.setFrozenRows(1);
    sh.getRange(1, 1, 1, headers.length).createFilter();
    var def = ss.getSheetByName('Hoja 1') || ss.getSheetByName('Sheet1');
    if (def && ss.getSheets().length > 1) ss.deleteSheet(def);
  }
  return sh;
}

// =====================================================================
//  Administración (ejecutar SOLO desde el editor de Apps Script)
// =====================================================================

function deviceTokens_() {
  var raw = PropertiesService.getScriptProperties().getProperty('DEVICE_TOKENS');
  try { return raw ? JSON.parse(raw) : []; } catch (e) { return []; }
}

function saveDeviceTokens_(list) {
  PropertiesService.getScriptProperties().setProperty('DEVICE_TOKENS', JSON.stringify(list));
}

/** 1) Ejecutar una vez: crea la carpeta raíz y la base central. */
function configurar() {
  var root = rootFolder_();
  var db = database_();
  Logger.log('Carpeta: ' + root.getUrl());
  Logger.log('Base central: ' + db.getUrl());
}

/** 2) Crear un token para un celular. Editá el nombre y ejecutá. Copiá el token del registro de ejecución. */
function crearTokenCelular() {
  var nombre = 'Celular depósito 1'; // <- cambiar
  var token = Utilities.getUuid().replace(/-/g, '') + Utilities.getUuid().replace(/-/g, '');
  var list = deviceTokens_();
  list.push({ name: nombre, token: token, created: new Date().toISOString(), revoked: false });
  saveDeviceTokens_(list);
  Logger.log('Token para "' + nombre + '": ' + token);
  return token;
}

/** Revocar un celular (perdido, robado, reemplazado). */
function revocarTokenCelular() {
  var nombre = 'Celular depósito 1'; // <- cambiar
  var list = deviceTokens_();
  list.forEach(function (t) { if (t.name === nombre) t.revoked = true; });
  saveDeviceTokens_(list);
  Logger.log('Revocado: ' + nombre);
}

function listarCelulares() {
  deviceTokens_().forEach(function (t) {
    Logger.log(t.name + ' · creado ' + t.created + (t.revoked ? ' · REVOCADO' : '') + ' · ...' + t.token.slice(-6));
  });
}
