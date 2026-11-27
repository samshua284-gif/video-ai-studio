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

function translateOnce(pipe, unit, srcLang, tgtLang) {
  return pipe(unit, { src_lang: srcLang, tgt_lang: tgtLang }).then(function (r) {
    return ((r && r[0] && r[0].translation_text) || '').trim();
  }).catch(function () { return ''; });
}

// One unit with the completeness guard: try, retry on empty or
// failure, then pass the original unit through (visible in the
// result) instead of dropping it silently.
function translateUnit(pipe, unit, srcLang, tgtLang) {
  return translateOnce(pipe, unit, srcLang, tgtLang).then(function (first) {
    if (first) return first;
    return translateOnce(pipe, unit, srcLang, tgtLang).then(function (second) {
      return second || unit;
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
      var chain = Promise.resolve();
      units.forEach(function (u, idx) {
        chain = chain.then(function () {
          return translateUnit(pipe, u, d.srcLang, d.tgtLang).then(function (r) {
            out.push(r);
            self.postMessage({ type: 'mt-partial', id: d.id, index: idx, total: units.length, text: r });
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
        return result;
      });
    }).then(function (result) {
      self.postMessage({ type: 'mt-done', id: d.id, result: result });
    }).catch(function (err) {
      self.postMessage({ type: 'mt-error', id: d.id, message: String((err && err.message) || err) });
    });
  }
};
