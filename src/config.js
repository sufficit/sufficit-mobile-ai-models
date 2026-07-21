export const config = {
  proxyPort: Number(process.env.PROXY_PORT ?? 4870),
  // 0.0.0.0 exposes the proxy to the whole LAN with no auth unless PROXY_API_KEY is set —
  // default to localhost-only.
  proxyHost: process.env.PROXY_HOST ?? "127.0.0.1",
  // When set, every /v1/* route requires `Authorization: Bearer <key>`. Empty = no auth.
  proxyApiKey: process.env.PROXY_API_KEY ?? "",

  phoneSerial: process.env.PHONE_SERIAL ?? "",
  phonePort: Number(process.env.PHONE_PORT ?? 8090),
  phoneRemoteDir: process.env.PHONE_REMOTE_DIR ?? "/data/local/tmp/sufficit-embed",

  modelFile: process.env.MODEL_FILE ?? "Qwen3-Embedding-4B-Q4_K_M.gguf",
  modelAlias: process.env.MODEL_ALIAS ?? "qwen3-embedding:4b",
  // Native output size of the model. Qwen3-Embedding is trained with Matryoshka
  // Representation Learning (MRL), so truncating to a smaller `dimensions` and
  // re-normalizing (done in server.js) yields a valid embedding at that size —
  // this is the technique the model card itself recommends. llama-server has
  // no built-in `dimensions` param, so we apply it ourselves in the proxy.
  embeddingNativeDims: Number(process.env.EMBEDDING_NATIVE_DIMS ?? 2560),

  threads: Number(process.env.LLAMA_THREADS ?? 6),
  ctxSize: Number(process.env.LLAMA_CTX ?? 2048),
  ubatch: Number(process.env.LLAMA_UBATCH ?? 512),
  batch: Number(process.env.LLAMA_BATCH ?? 512),

  healthTimeoutMs: Number(process.env.HEALTH_TIMEOUT_MS ?? 3000),
  startTimeoutMs: Number(process.env.START_TIMEOUT_MS ?? 60000),
};
