export function l2Normalize(vec) {
  const norm = Math.sqrt(vec.reduce((sum, v) => sum + v * v, 0));
  if (norm === 0) return vec;
  return vec.map((v) => v / norm);
}

export function isValidDimensions(dimensions, nativeDims) {
  return Number.isInteger(dimensions) && dimensions >= 1 && dimensions <= nativeDims;
}

// Matryoshka truncation: slice to the requested prefix, then re-normalize (L2) — the
// technique the model card recommends for MRL-trained models. See README.md "dimensions".
export function truncateEmbedding(vec, dimensions) {
  return l2Normalize(vec.slice(0, dimensions));
}
