// WhisperAsh: cut a long recording into short pieces at pauses, so Whisper never sees
// long audio (where it skips stretches and loops on its prompt; see transcriptGuard.js).
// Pure functions on mono Float32 PCM; the Android app does the same in TranscriptGuard.kt.

export const TARGET_CHUNK_SECONDS = 25;
const MIN_CHUNK_SECONDS = 15; // a cut is searched between 15 s and 30 s into each piece
const MAX_CHUNK_SECONDS = 30;
const FRAME_SECONDS = 0.05;

/** [[startSample, endSample], ...] covering all of pcm, each cut at the quietest moment it can find. */
export function splitAtPauses(pcm, sampleRate) {
  const total = pcm.length;
  const maxLen = Math.floor(MAX_CHUNK_SECONDS * sampleRate);
  if (total <= maxLen) return [[0, total]];
  const frame = Math.max(1, Math.floor(FRAME_SECONDS * sampleRate));
  const chunks = [];
  let start = 0;
  while (total - start > maxLen) {
    const from = start + Math.floor(MIN_CHUNK_SECONDS * sampleRate);
    const to = Math.min(start + maxLen, total - frame);
    let bestAt = start + Math.floor(TARGET_CHUNK_SECONDS * sampleRate);
    let bestEnergy = Infinity;
    // Quietest 300 ms window (6 frames) in the search range, preferring cuts near 25 s.
    for (let at = from; at + frame * 6 <= to; at += frame) {
      let energy = 0;
      for (let i = at; i < at + frame * 6; i++) energy += pcm[i] * pcm[i];
      const distance = Math.abs(at - (start + TARGET_CHUNK_SECONDS * sampleRate)) / sampleRate;
      const score = energy * (1 + distance * 0.02);
      if (score < bestEnergy) {
        bestEnergy = score;
        bestAt = at + frame * 3; // middle of the quiet window
      }
    }
    chunks.push([start, bestAt]);
    start = bestAt;
  }
  chunks.push([start, total]);
  return chunks;
}

/** 16-bit PCM WAV bytes for mono Float32 samples. */
export function encodeWav(samples, sampleRate) {
  const buffer = new ArrayBuffer(44 + samples.length * 2);
  const view = new DataView(buffer);
  const str = (o, s) => [...s].forEach((c, i) => view.setUint8(o + i, c.charCodeAt(0)));
  str(0, "RIFF");
  view.setUint32(4, 36 + samples.length * 2, true);
  str(8, "WAVE");
  str(12, "fmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  str(36, "data");
  view.setUint32(40, samples.length * 2, true);
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]));
    view.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
  }
  return new Uint8Array(buffer);
}

/** Joins piece transcripts, dropping a word repeated across a cut. */
export function joinPieces(texts) {
  let out = "";
  for (const raw of texts) {
    const t = (raw || "").trim();
    if (!t) continue;
    if (!out) {
      out = t;
      continue;
    }
    const lastWord = out.split(/\s+/).pop().toLowerCase().replace(/[^\p{L}\p{N}']/gu, "");
    const firstWord = t.split(/\s+/)[0].toLowerCase().replace(/[^\p{L}\p{N}']/gu, "");
    out += " " + (lastWord && lastWord === firstWord ? t.split(/\s+/).slice(1).join(" ") : t);
  }
  return out.replace(/\s+/g, " ").trim();
}
