// Pruebas del backend con Google Apps Script simulado. Ejecutar: node backend/backend.test.js
const fs = require('fs');
const vm = require('vm');
const path = require('path');
const assert = require('assert');

function makeEnv() {
  let ids = 0;
  const props = {};
  const files = [];
  function Folder(name) {
    this.id = 'F' + (++ids); this.name = name; this.children = []; this.files = [];
  }
  Folder.prototype.getId = function () { return this.id; };
  Folder.prototype.getUrl = function () { return 'https://drive/' + this.id; };
  Folder.prototype.getFoldersByName = function (n) { return iter(this.children.filter(c => c.name === n)); };
  Folder.prototype.createFolder = function (n) { const f = new Folder(n); this.children.push(f); allFolders.push(f); return f; };
  Folder.prototype.getFiles = function () { return iter(this.files); };
  Folder.prototype.createFile = function (blob) {
    const f = { id: 'D' + (++ids), name: blob.name, desc: '', getUrl() { return 'https://drive/' + this.id; },
      getName() { return this.name; }, getDescription() { return this.desc; }, setDescription(d) { this.desc = d; }, bytes: blob.bytes };
    this.files.push(f); files.push(f); return f;
  };
  const allFolders = [];
  const driveRoot = new Folder('root');
  function iter(arr) { let i = 0; return { hasNext: () => i < arr.length, next: () => arr[i++] }; }

  function Sheet(name) { this.name = name; this.rows = []; }
  Sheet.prototype.getLastRow = function () { return this.rows.length; };
  Sheet.prototype.appendRow = function (r) { this.rows.push(r.slice()); };
  Sheet.prototype.setFrozenRows = function () {};
  Sheet.prototype.getRange = function (row, col, nr, nc) {
    const sh = this; nr = nr || 1; nc = nc || 1;
    const rg = {
      setValues(v) { v.forEach((r, i) => { sh.rows[row - 1 + i] = r.slice(); }); return rg; },
      getValues() { return sh.rows.slice(row - 1, row - 1 + nr).map(r => r.slice(0, nc)); },
      setNumberFormat() { return rg; }, setFontWeight() { return rg; }, setBackground() { return rg; },
      setFontColor() { return rg; }, createFilter() { return rg; },
    };
    return rg;
  };
  const ss = { sheets: [new Sheet('Hoja 1')], getId: () => 'SS1', getUrl: () => 'https://sheet/SS1',
    getSheetByName(n) { return this.sheets.find(s => s.name === n) || null; },
    insertSheet(n) { const s = new Sheet(n); this.sheets.push(s); return s; },
    getSheets() { return this.sheets; }, deleteSheet(s) { this.sheets = this.sheets.filter(x => x !== s); } };

  const ctx = {
    console, JSON, Array, String, Number, Date, Object,
    PropertiesService: { getScriptProperties: () => ({ getProperty: k => props[k] || null, setProperty: (k, v) => { props[k] = v; } }) },
    DriveApp: {
      getFolderById: id => allFolders.find(f => f.id === id),
      getFoldersByName: n => iter(allFolders.filter(f => f.name === n)),
      createFolder: n => driveRoot.createFolder(n),
      getFileById: () => ({ moveTo() {} }),
    },
    SpreadsheetApp: { create: () => ss, openById: () => ss },
    LockService: { getScriptLock: () => ({ tryLock: () => true, releaseLock() {} }) },
    Utilities: {
      formatDate: () => '22/10/2026 18:00:00',
      newBlob: (bytes, mime, name) => ({ bytes, mime, name }),
      base64Decode: s => Buffer.from(s, 'base64'),
      getUuid: () => require('crypto').randomUUID(),
    },
    Session: { getScriptTimeZone: () => 'America/Argentina/Buenos_Aires' },
    Logger: { log() {} },
    ContentService: { MimeType: { JSON: 'json' }, createTextOutput: s => ({ s, setMimeType() { return this; } }) },
  };
  vm.createContext(ctx);
  vm.runInContext(fs.readFileSync(path.join(__dirname, 'Code.gs'), 'utf8'), ctx);
  return { ctx, ss, files, props };
}

function payload(token, uuid, codes) {
  return {
    action: 'submitDispatch', token,
    dispatch: { uuid, id: 'ENVIOPACK-20261022-001', empresa: 'ProntoTec', logistica: 'EnvíoPack', operario: 'Juan',
      fecha: '22/10/2026', isoDay: '2026-10-22', year: '2026', month: '10', horaInicio: '10:00:00', horaFin: '11:00:00',
      duplicados: 1, total: codes.length,
      items: codes.map((c, i) => ({ n: i + 1, code: c, date: '22/10/2026', time: '10:0' + i + ':00' })) },
    pdfBase64: Buffer.from('%PDF-1.4 x').toString('base64'),
    xlsxBase64: Buffer.from('PK x').toString('base64'),
  };
}

let passed = 0;
function test(name, fn) { fn(); passed++; console.log('✔ ' + name); }

test('rechaza sin token / token desconocido', () => {
  const { ctx } = makeEnv();
  assert.strictEqual(ctx.handle_({ action: 'ping' }).error, 'unauthorized');
  assert.strictEqual(ctx.handle_({ action: 'ping', token: 'x'.repeat(40) }).error, 'unauthorized');
});

test('token revocado no funciona', () => {
  const { ctx } = makeEnv();
  ctx.crearTokenCelular(); ctx.revocarTokenCelular();
  const t = JSON.parse(ctx.PropertiesService.getScriptProperties().getProperty('DEVICE_TOKENS'))[0].token;
  assert.strictEqual(ctx.handle_({ action: 'ping', token: t }).error, 'unauthorized');
});

test('crea carpetas, archivos y registros; códigos como texto', () => {
  const { ctx, ss, files } = makeEnv();
  const tok = ctx.crearTokenCelular();
  const r = ctx.handle_(payload(tok, 'u-1', ['000123', "EP013434737N'01", "EP013434737N'02"]));
  assert.strictEqual(r.ok, true); assert.strictEqual(r.status, 'created');
  assert.deepStrictEqual(files.map(f => f.name).sort(), ['DESPACHO_001.pdf', 'DESPACHO_001.xlsx']);
  const reg = ss.getSheetByName('Registros').rows.slice(1);
  assert.strictEqual(reg.length, 3);
  assert.strictEqual(reg[0][6], '000123');
  assert.strictEqual(ss.getSheetByName('Despachos').rows.length, 2);
  assert.strictEqual(ss.getSheetByName('Hoja 1'), null);
});

test('reenvío del mismo despacho no duplica nada (idempotente)', () => {
  const { ctx, ss, files } = makeEnv();
  const tok = ctx.crearTokenCelular();
  ctx.handle_(payload(tok, 'u-1', ['A', 'B']));
  const r2 = ctx.handle_(payload(tok, 'u-1', ['A', 'B']));
  assert.strictEqual(r2.status, 'already_exists');
  assert.strictEqual(files.length, 2);
  assert.strictEqual(ss.getSheetByName('Registros').rows.length, 3);
  assert.strictEqual(ss.getSheetByName('Despachos').rows.length, 2);
});

test('reintento tras falla a mitad de camino reutiliza archivos ya subidos', () => {
  const { ctx, files } = makeEnv();
  const tok = ctx.crearTokenCelular();
  const orig = ctx.Utilities.formatDate;
  ctx.Utilities.formatDate = () => { throw new Error('falla simulada'); };
  assert.throws(() => ctx.handle_(payload(tok, 'u-9', ['A'])));
  ctx.Utilities.formatDate = orig;
  const r = ctx.handle_(payload(tok, 'u-9', ['A']));
  assert.strictEqual(r.status, 'created');
  assert.strictEqual(files.length, 2); // no se duplicaron
});

test('mismo número de despacho de otro celular no pisa archivos', () => {
  const { ctx, files } = makeEnv();
  const tok = ctx.crearTokenCelular();
  ctx.handle_(payload(tok, 'u-1', ['A']));
  ctx.handle_(payload(tok, 'u-2', ['B']));
  assert.strictEqual(files.length, 4);
  assert.strictEqual(new Set(files.map(f => f.name)).size, 4);
});

test('valida datos', () => {
  const { ctx } = makeEnv();
  const tok = ctx.crearTokenCelular();
  const p = payload(tok, 'u-1', ['A']); p.dispatch.items[0].code = '';
  assert.strictEqual(ctx.handle_(p).error, 'invalid_payload');
});

console.log(`\n${passed} pruebas del backend OK`);
