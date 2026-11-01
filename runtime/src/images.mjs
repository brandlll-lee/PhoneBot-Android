import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile, access } from "node:fs/promises";
import { join } from "node:path";

export function imageStore(directory, session, host, historical = []) {
  const previous = new Set(historical.filter((m) => m.role === "toolResult").map((m) => m.toolCallId));
  const messages = new WeakMap(), encoded = new Map();
  async function archive(image) {
    const bytes = Buffer.from(image.data, "base64");
    const reference = createHash("sha256").update(bytes).digest("hex");
    await mkdir(directory, { recursive: true });
    const path = join(directory, `${reference}.source`);
    try { await access(path); } catch { await writeFile(path, bytes); }
    return reference;
  }
  async function recall(reference) {
    if (!/^[a-f0-9]{64}$/.test(reference)) throw new Error("图片引用无效");
    if (!encoded.has(reference)) encoded.set(reference, (async () => {
      const info = await host("image_prepare", { reference, session });
      if (info.mimeType !== "image/jpeg" || !(info.width > 0 && info.height > 0 && Math.max(info.width, info.height) <= 1280)) throw new Error("图片编码尺寸无效");
      return { type: "image", data: (await readFile(info.image)).toString("base64"), mimeType: "image/jpeg" };
    })());
    return encoded.get(reference);
  }
  async function project(history) {
    return Promise.all(history.map(async (message) => {
      if (!Array.isArray(message.content) || !message.content.some((b) => b.type === "image")) return message;
      if (!messages.has(message)) messages.set(message, (async () => ({ ...message, content: (await Promise.all(message.content.map(async (block) => {
        if (block.type !== "image") return [block];
        const reference = await archive(block);
        const note = { type: "text", text: `Archived ${message.role === "user" ? "reference" : "screen"} image: ${reference}. Use image_recall to inspect it. Historical images do not describe the current screen.` };
        return previous.has(message.toolCallId) ? [note] : [note, await recall(reference)];
      }))).flat() }))());
      return messages.get(message);
    }));
  }
  return { project, recall };
}
