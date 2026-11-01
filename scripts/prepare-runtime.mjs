import { createHash } from "node:crypto";
import { existsSync } from "node:fs";
import { mkdir, readFile, writeFile, cp } from "node:fs/promises";
import { resolve } from "node:path";
import { execFileSync } from "node:child_process";

const root = resolve(import.meta.dirname, "..");
await import("./prepare-brand.mjs");
const tools = resolve(root, ".tools");
const archive = resolve(tools, "nodejs-mobile-android-24.18.0-0.zip");
const extracted = resolve(tools, "node-mobile-24.18.0-0");
const sha256 = "ceb86b0b8130006195a60cd37393ebe0fd665b644ce8d5674dfba1da65d3be28";
await mkdir(tools, { recursive: true });
if (!existsSync(archive)) {
  const response = await fetch("https://github.com/gmaclennan/nodejs-mobile/releases/download/v24.18.0-0/nodejs-mobile-android-24.18.0-0.zip");
  if (!response.ok) throw new Error(`Node download: HTTP ${response.status}`);
  await writeFile(archive, new Uint8Array(await response.arrayBuffer()));
}
if (createHash("sha256").update(await readFile(archive)).digest("hex") !== sha256) {
  throw new Error("Node archive checksum does not match");
}
await mkdir(extracted, { recursive: true });
execFileSync("tar", ["-xf", archive, "-C", extracted], { stdio: "inherit" });
for (const abi of ["arm64-v8a", "x86_64"]) {
  const destination = resolve(root, "app/src/main/jniLibs", abi);
  await mkdir(destination, { recursive: true });
  await cp(resolve(extracted, "bin", abi, "libnode.so"), resolve(destination, "libnode.so"));
}
await cp(resolve(extracted, "include/node"), resolve(root, "app/src/main/cpp/include"), { recursive: true });
execFileSync(process.platform === "win32" ? "npm.cmd" : "npm", ["ci", "--no-audit", "--no-fund", "--registry=https://registry.npmjs.org"], {
  cwd: resolve(root, "runtime"), stdio: "inherit", shell: process.platform === "win32",
  env: { ...process.env, npm_config_cache: resolve(tools, "npm-cache") },
});
execFileSync(process.execPath, ["build.mjs"], { cwd: resolve(root, "runtime"), stdio: "inherit" });
const nodeLicense = resolve(tools, "node-LICENSE");
if (!existsSync(nodeLicense)) {
  const response = await fetch("https://raw.githubusercontent.com/gmaclennan/nodejs-mobile/v24.18.0-0/LICENSE");
  if (!response.ok) throw new Error(`Node license download: HTTP ${response.status}`);
  await writeFile(nodeLicense, new Uint8Array(await response.arrayBuffer()));
}
if (createHash("sha256").update(await readFile(nodeLicense)).digest("hex") !== "ef6288da287cabf89679ad3ae860f45a06c251fcf8636c4014c127186f1a6b96") {
  throw new Error("Node license checksum does not match");
}
await cp(nodeLicense, resolve(root, "app/src/main/assets/runtime/node-notices.txt"));
