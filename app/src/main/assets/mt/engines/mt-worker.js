// Translation worker: NLLB-200-distilled-600M (quantized) via
// Transformers.js — direct translation between all supported
// languages, from the app's local assets only. Carried over from
// the owner's voice translator, whose field lessons are baked in:
//
// - Translation is SENTENCE BY SENTENCE only, never merged chunks:
//   merging several sentences into one call makes NLLB silently
//   drop whole sentences from inside the chunk (reproduced and
//   proven in the voice translator's 1.1 fix).
// - Completeness guard per unit: an empty result is retried once;
//   if it stays empty, the SOURCE unit itself is passed through
//   visibly instead of being dropped. No silent drops, ever.
// - Greedy decoding (the pipeline default): num_beams=2 measured
//   ~3x slower with no quality gain and no drop prevention.
import { pipeline, env } from './transformers.min.js';

var BASE = new URL('./', self.location.href).href; // .../engines/
env.allowRemoteModels = false;
env.allowLocalModels = true;
env.localModelPath = BASE + 'models/';
if (env.backends && env.backends.onnx && env.backends.onnx.wasm) {
  env.backends.onnx.wasm.wasmPaths = BASE;
  env.backends.onnx.wasm.numThreads = Math.min(4, self.navigator.hardwareConcurrency || 2);
}

var pipePromise = null;
function load() {
  if (!pipePromise) {
    pipePromise = pipeline('translation', 'nllb-200-distilled-600M', {
      quantized: true,
      progress_callback: function (p) {
        self.postMessage({ type: 'progress', engine: 'mt', status: p.status, file: p.file, progress: p.progress });
      }
    });
  }
  return pipePromise;
}

// Split text into translation units: sentences; a sentence longer
// than 400 chars is split at an Arabic comma or a space. Every
// unit is translated and accounted for on its own.
function splitSentences(t) {
  var raw = String(t).split(/(?<=[.!?؟।])\s+|\n+/);
  var out = [];
  for (var i = 0; i < raw.length; i++) {
    var s = raw[i].trim();
    while (s.length > 400) {
      var cut = s.lastIndexOf('،', 400);
      if (cut < 150) cut = s.lastIndexOf(' ', 400);
      if (cut < 150) cut = 400;
      out.push(s.slice(0, cut).trim());
      s = s.slice(cut).trim();
    }
    if (s) out.push(s);
  }
  return out.length ? out : [String(t)];
}

// One raw attempt (0.9.2, TD-020): resolves to { text, error }.
// Until 0.9.1 an exception here was swallowed into '' — the
// engine could fail on every unit of a whole job and the job
// still reported success with the source text, and nobody
// could see why. Now the FIRST exception per request is
// recorded as "name: message"; a resolved-but-empty result
// stays a distinct EMPTY outcome (error null), so the two
// failure kinds are never confused again.
function translateOnce(pipe, unit, srcLang, tgtLang) {
  var attempt;
  try {
    attempt = pipe(unit, { src_lang: srcLang, tgt_lang: tgtLang });
  } catch (err) {
    attempt = Promise.reject(err);
  }
  return Promise.resolve(attempt).then(function (r) {
    var text = ((r && r[0] && r[0].translation_text) || '').trim();
    return { text: text, error: null };
  }).catch(function (err) {
    var name = (err && err.name) || 'Error';
    var msg = (err && err.message) || String(err);
    return { text: '', error: name + ': ' + msg };
  });
}

// One unit with the completeness guard — behaviour UNCHANGED
// (try, retry on empty or failure, then pass the original
// unit through visibly instead of dropping it silently).
// Resolves to { text, passed }: passed is true only when the
// final text is the passed-through source unit.
function translateUnit(pipe, unit, srcLang, tgtLang, diag) {
  return translateOnce(pipe, unit, srcLang, tgtLang).then(function (first) {
    if (first.text) return { text: first.text, passed: false };
    if (first.error && !diag.firstError) diag.firstError = first.error;
    return translateOnce(pipe, unit, srcLang, tgtLang).then(function (second) {
      if (second.text) return { text: second.text, passed: false };
      if (second.error && !diag.firstError) diag.firstError = second.error;
      return { text: unit, passed: true };
    });
  });
}

self.onmessage = function (e) {
  var d = e.data || {};
  if (d.type === 'load') {
    load().then(function () {
      self.postMessage({ type: 'ready', engine: 'mt' });
    }).catch(function (err) {
      pipePromise = null;
      self.postMessage({ type: 'load-error', engine: 'mt', message: String((err && err.message) || err) });
    });
    return;
  }
  if (d.type === 'mt') {
    load().then(function (pipe) {
      var units = splitSentences(d.text);
      var out = [];
      // TD-020 diagnostics for this request: how many units
      // really translated, how many passed through, the first
      // real exception (null when every failure was EMPTY-only),
      // and the language codes exactly as received — echoed so
      // the Kotlin side can show what the engine actually saw.
      var diag = { firstError: null };
      var unitsOk = 0;
      var unitsPassed = 0;
      var chain = Promise.resolve();
      units.forEach(function (u, idx) {
        chain = chain.then(function () {
          return translateUnit(pipe, u, d.srcLang, d.tgtLang, diag).then(function (r) {
            out.push(r.text);
            if (r.passed) unitsPassed++; else unitsOk++;
            self.postMessage({ type: 'mt-partial', id: d.id, index: idx, total: units.length, text: r.text });
          });
        });
      });
      return chain.then(function () {
        // Final guard: output unit count equals input unit count
        // by construction, and a wholly empty result for non-empty
        // input is replaced by the input itself — never a silent
        // empty result.
        var result = out.join(' ');
        if (!result.trim() && String(d.text).trim()) result = String(d.text);
        return {
          result: result,
          diagnostics: {
            unitsTotal: units.length,
            unitsOk: unitsOk,
            unitsPassed: unitsPassed,
            firstError: diag.firstError,
            srcLang: d.srcLang,
            tgtLang: d.tgtLang
          }
        };
      });
    }).then(function (done) {
      self.postMessage({ type: 'mt-done', id: d.id, result: done.result, diagnostics: done.diagnostics });
    }).catch(function (err) {
      self.postMessage({ type: 'mt-error', id: d.id, message: String((err && err.message) || err) });
    });
  }
};
