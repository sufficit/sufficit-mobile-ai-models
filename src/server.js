import { createServer } from "node:http";
import { config } from "./config.js";
import { ensurePhoneServer, isPhoneServerUp, resolveSerial } from "./adb.js";
import { isValidDimensions, truncateEmbedding } from "./embeddings.js";

const upstream = (path) => `http://127.0.0.1:${config.phonePort}${path}`;

const MAX_BODY_BYTES = 10 * 1024 * 1024;

class PayloadTooLargeError extends Error {}

async function readBody(req, maxBytes = MAX_BODY_BYTES) {
  const chunks = [];
  let total = 0;
  for await (const chunk of req) {
    total += chunk.length;
    if (total > maxBytes) {
      req.destroy();
      throw new PayloadTooLargeError();
    }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

function json(res, status, body) {
  res.writeHead(status, { "content-type": "application/json" });
  res.end(JSON.stringify(body));
}

async function handleEmbeddings(req, res) {
  try {
    await ensurePhoneServer();
  } catch (err) {
    json(res, 503, { error: "phone_unavailable", detail: err.message });
    return;
  }

  let raw;
  try {
    raw = await readBody(req);
  } catch (err) {
    if (err instanceof PayloadTooLargeError) {
      json(res, 413, { error: "payload_too_large" });
      return;
    }
    throw err;
  }

  let payload;
  try {
    payload = JSON.parse(raw);
  } catch {
    json(res, 400, { error: "invalid_json" });
    return;
  }

  const { dimensions, ...upstreamPayload } = payload;
  if (dimensions !== undefined && !isValidDimensions(dimensions, config.embeddingNativeDims)) {
    json(res, 400, {
      error: "invalid_dimensions",
      detail: `dimensions must be an integer between 1 and ${config.embeddingNativeDims}`,
    });
    return;
  }

  // encoding_format=base64 would return a base64 string, not an array — the dimensions
  // truncation above does array.slice() and would silently corrupt it.
  if (payload.encoding_format !== undefined && payload.encoding_format !== "float") {
    json(res, 400, { error: "unsupported_encoding_format" });
    return;
  }

  try {
    const upstreamRes = await fetch(upstream("/v1/embeddings"), {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(upstreamPayload),
    });
    const data = await upstreamRes.json();

    if (dimensions !== undefined && Array.isArray(data.data)) {
      for (const item of data.data) {
        item.embedding = truncateEmbedding(item.embedding, dimensions);
      }
    }

    res.writeHead(upstreamRes.status, { "content-type": "application/json" });
    res.end(JSON.stringify(data));
  } catch (err) {
    json(res, 502, { error: "upstream_error", detail: err.message });
  }
}

function isAuthorized(req) {
  if (!config.proxyApiKey) return true;
  const header = req.headers["authorization"] ?? "";
  const [scheme, token] = header.split(" ");
  return scheme === "Bearer" && token === config.proxyApiKey;
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, "http://localhost");

  if (url.pathname === "/health" && req.method === "GET") {
    const serial = await resolveSerial().catch((err) => ({ error: err.message }));
    const phoneUp = await isPhoneServerUp();
    json(res, 200, {
      proxy: "ok",
      phoneSerial: typeof serial === "string" ? serial : null,
      phoneSerialError: typeof serial === "string" ? null : serial.error,
      phoneServerUp: phoneUp,
      model: config.modelAlias,
    });
    return;
  }

  if (url.pathname.startsWith("/v1/") && !isAuthorized(req)) {
    json(res, 401, { error: "unauthorized" });
    return;
  }

  if (url.pathname === "/v1/models" && req.method === "GET") {
    json(res, 200, {
      object: "list",
      data: [{ id: config.modelAlias, object: "model", owned_by: "sufficit-mobile-ai-models" }],
    });
    return;
  }

  if (url.pathname === "/v1/embeddings" && req.method === "POST") {
    await handleEmbeddings(req, res);
    return;
  }

  json(res, 404, { error: "not_found" });
});

server.listen(config.proxyPort, config.proxyHost, () => {
  console.log(`sufficit-mobile-ai-models proxy listening on ${config.proxyHost}:${config.proxyPort}`);
  console.log(`forwarding /v1/embeddings -> phone llama-server (port ${config.phonePort}) as "${config.modelAlias}"`);
});
