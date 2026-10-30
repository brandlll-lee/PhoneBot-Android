import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdir, mkdtemp, writeFile, rm } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { join } from "node:path";
import { phoneTools } from "../src/tools.mjs";

test("screenshots become Pi image content only for a vision model", async (t) => {
  const temporary = fileURLToPath(new URL("../../.tools/tests/", import.meta.url));
  await mkdir(temporary, { recursive: true });
  const directory = await mkdtemp(join(temporary, "phonebot-image-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const path = join(directory, "screen.png");
  const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+iEJ8AAAAASUVORK5CYII=", "base64");
  await writeFile(path, png);
  const host = async () => ({ page: "test-page", image: path });
  let attachment;
  const tool = phoneTools(host, { input: ["text", "image"] }, (value) => { attachment = value; }).find((item) => item.name === "screenshot");
  const result = await tool.execute("image", {});
  assert.equal(result.content[1].mimeType, "image/png");
  assert.deepEqual(Buffer.from(result.content[1].data, "base64"), png);
  assert.equal(attachment, path);
  assert.equal(result.details.image, undefined);
  const textTool = phoneTools(host, { input: ["text"] }).find((item) => item.name === "screenshot");
  await assert.rejects(textTool.execute("text", {}), /不支持图片/);
});
