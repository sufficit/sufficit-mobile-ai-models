import { execFile, spawn } from "node:child_process";
import { promisify } from "node:util";
import { config } from "./config.js";

const execFileP = promisify(execFile);

async function adb(serial, args, opts = {}) {
  const fullArgs = serial ? ["-s", serial, ...args] : args;
  return execFileP("adb", fullArgs, { timeout: 15000, ...opts });
}

let cachedSerial = null;

export async function resolveSerial() {
  if (config.phoneSerial) return config.phoneSerial;
  if (cachedSerial) return cachedSerial;

  const { stdout } = await adb(null, ["devices"]);
  const lines = stdout
    .split("\n")
    .slice(1)
    .map((l) => l.trim())
    .filter(Boolean);
  const authorized = lines.filter((l) => l.endsWith("\tdevice"));

  if (authorized.length === 0) {
    throw new Error("No authorized adb device found. Plug in the phone and accept the USB debugging prompt.");
  }
  if (authorized.length > 1) {
    throw new Error(
      `Multiple authorized adb devices found (${authorized.map((l) => l.split("\t")[0]).join(", ")}). ` +
        "Set PHONE_SERIAL to pick one."
    );
  }

  cachedSerial = authorized[0].split("\t")[0];
  return cachedSerial;
}

export async function ensureForward(serial) {
  await adb(serial, ["forward", `tcp:${config.phonePort}`, `tcp:${config.phonePort}`]);
}

export async function isPhoneServerUp() {
  try {
    const res = await fetch(`http://127.0.0.1:${config.phonePort}/health`, {
      signal: AbortSignal.timeout(config.healthTimeoutMs),
    });
    return res.ok;
  } catch {
    return false;
  }
}

export async function startPhoneServer(serial) {
  const dir = config.phoneRemoteDir;
  const modelPath = `${dir}/${config.modelFile}`;
  const cmd =
    `cd ${dir} && LD_LIBRARY_PATH=${dir} nohup ./llama-server ` +
    `-m ${modelPath} --embedding --pooling last ` +
    `-c ${config.ctxSize} -b ${config.batch} -ub ${config.ubatch} -t ${config.threads} ` +
    `--host 127.0.0.1 --port ${config.phonePort} --alias ${config.modelAlias} ` +
    `> ${dir}/server.log 2>&1 < /dev/null &`;

  // `adb shell "... &"` does not return once the remote command is launched —
  // the local adb client stays attached to the device-side shell session
  // until the backgrounded process itself exits, regardless of fd redirects.
  // So we fire-and-forget (detached + unref'd) instead of awaiting it, and
  // rely on the health poll below to know when the server is actually up.
  const args = serial ? ["-s", serial, "shell", cmd] : ["shell", cmd];
  const child = spawn("adb", args, { stdio: "ignore", detached: true });
  child.unref();

  const deadline = Date.now() + config.startTimeoutMs;
  while (Date.now() < deadline) {
    if (await isPhoneServerUp()) return;
    await new Promise((r) => setTimeout(r, 1500));
  }
  throw new Error(`llama-server did not come up on the phone within ${config.startTimeoutMs}ms. Check ${dir}/server.log on device.`);
}

export async function stopPhoneServer(serial) {
  await adb(serial, ["shell", "pkill -f llama-server"]).catch(() => {});
}

export async function ensurePhoneServer() {
  const serial = await resolveSerial();
  await ensureForward(serial);
  if (await isPhoneServerUp()) return;
  await startPhoneServer(serial);
}
