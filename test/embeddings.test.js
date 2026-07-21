import { test } from "node:test";
import assert from "node:assert/strict";
import { l2Normalize, isValidDimensions, truncateEmbedding } from "../src/embeddings.js";

test("l2Normalize produces a unit vector", () => {
  const normalized = l2Normalize([3, 4]);
  const norm = Math.sqrt(normalized.reduce((sum, v) => sum + v * v, 0));
  assert.ok(Math.abs(norm - 1) < 1e-9);
});

test("l2Normalize leaves the zero vector unchanged", () => {
  assert.deepEqual(l2Normalize([0, 0, 0]), [0, 0, 0]);
});

test("isValidDimensions rejects out-of-range and non-integer values", () => {
  const nativeDims = 2560;
  assert.equal(isValidDimensions(0, nativeDims), false);
  assert.equal(isValidDimensions(-1, nativeDims), false);
  assert.equal(isValidDimensions(1.5, nativeDims), false);
  assert.equal(isValidDimensions(99999, nativeDims), false);
  assert.equal(isValidDimensions(1, nativeDims), true);
  assert.equal(isValidDimensions(nativeDims, nativeDims), true);
});

test("truncateEmbedding preserves the direction of the prefix", () => {
  const truncated = truncateEmbedding([3, 4, 0, 0], 2);
  assert.equal(truncated.length, 2);
  assert.ok(Math.abs(truncated[0] - 0.6) < 1e-9);
  assert.ok(Math.abs(truncated[1] - 0.8) < 1e-9);
});
