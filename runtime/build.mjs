import { build } from "esbuild";
import { mkdir, copyFile, writeFile, readdir, readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

const output = new URL("../app/src/main/assets/runtime/", import.meta.url);
await mkdir(output, { recursive: true });
const result = await build({
  entryPoints: ["src/main.mjs"], outfile: "dist/engine.mjs",
  bundle: true, platform: "node", format: "esm", target: "node24",
  banner: { js: "import {createRequire} from 'node:module'; const require=createRequire(import.meta.url);" },
  legalComments: "eof", metafile: true,
});
await copyFile("dist/engine.mjs", new URL("engine.mjs", output));
const hash = createHash("sha256").update(readFileSync("dist/engine.mjs")).digest("hex");
await writeFile(new URL("version.txt", output), hash);

const packages = new Set();
for (const input of Object.keys(result.metafile.inputs).filter((path) => path.startsWith("node_modules/"))) {
  let directory = dirname(resolve(input));
  while (!existsSync(resolve(directory, "package.json"))) directory = dirname(directory);
  packages.add(directory);
}
const notices = [await readFile("licenses/pi.txt", "utf8")];
for (const directory of [...packages].sort()) {
  const info = JSON.parse(await readFile(resolve(directory, "package.json"), "utf8"));
  const licenses = (await readdir(directory)).filter((name) => /^(licen[sc]e|notice)(\.|$)/i.test(name));
  notices.push(`${info.name}@${info.version} (${info.license})`);
  for (const license of licenses) notices.push(await readFile(resolve(directory, license), "utf8"));
}
await writeFile(new URL("npm-notices.txt", output), notices.join("\n\n"));
