import { test } from "node:test";
import assert from "node:assert/strict";
import { contextManager, repairHistory } from "../src/context.mjs";

const model = { contextWindow: 32000, maxTokens: 2000 };
const user = (text) => ({ role: "user", content: text, timestamp: 1 });
const call = (id) => ({ role: "assistant", content: [{ type: "toolCall", name: "tap", id, arguments: { text: "确定" } }], timestamp: 1 });
const result = (id, value) => ({ role: "toolResult", toolCallId: id, toolName: "tap", content: [{ type: "text", text: JSON.stringify(value) }], timestamp: 1 });

test("context keeps prefixes unchanged until a budget is reached", () => {
  const context = contextManager(model);
  const history = [user("打开相册"), call("a"), result("a", { kind: "full", snapshot: 1, elements: [] })];
  const first = context.project(history).messages;
  const second = context.project([...history, call("b"), result("b", { kind: "diff", baseSnapshot: 1, snapshot: 2 })]).messages;
  assert.deepEqual(second.slice(0, first.length), first);
  assert.deepEqual(history, first);
});

test("a new task keeps conversation facts and image references apart from live screen targets", () => {
  const reference = "b".repeat(64);
  const history = [user("打开音乐"), { ...call("old"), content: [{ ...call("old").content[0], arguments: { element: "e551", x: 80, y: 150 } }] },
    result("old", { kind: "full", snapshot: 99, package: "music", executed: true, conditionMet: false, stopReason: "timeout", elements: [{ id: "e551", text: "旧页面" }] }),
    { role: "assistant", content: [{ type: "text", text: "没有找到排行。" }], timestamp: 1 }];
  history[2].content.push({ type: "text", text: `Archived screen image: ${reference}. Use image_recall to inspect it.` });
  history.push(call("batch"), result("batch", { ok: true, canContinue: false, remaining: 1,
    steps: [{ tool: "tap", executed: true, conditionMet: false, stopReason: "timeout" }], screen: { kind: "diff", updated: [{ id: "e552" }] } }));
  const original = structuredClone(history);
  const context = contextManager(model, () => {}, history.length);
  const current = [user("继续查找"), call("now"), result("now", { kind: "full", snapshot: 100, elements: [{ id: "e700", text: "当前页面" }] })];
  const sent = context.project([...history, ...current]).messages;
  const prior = JSON.stringify(sent.slice(0, -current.length));
  assert.ok(!prior.includes("e551") && !prior.includes("旧页面"));
  assert.ok(prior.includes("没有找到排行") && prior.includes('conditionMet') && prior.includes("timeout") && prior.includes(reference));
  const facts = sent.find((m) => typeof m.content === "string" && m.content.startsWith("Historical execution facts")).content;
  assert.ok(facts.includes('"remaining":1') && facts.includes('"executed":true') && !prior.includes("e552"));
  assert.deepEqual(sent.slice(-current.length), current);
  assert.deepEqual(context.project([...history, ...current, user("再看看")]).messages.slice(0, sent.length), sent);
  assert.deepEqual(history, original);
});

test("image budget removes oldest tool images and preserves original transcript", () => {
  const context = contextManager({ ...model, inputLimits: { images: { maxPerRequest: 1 } } });
  const old = result("a", {}), latest = result("b", {});
  old.content.push({ type: "image", data: "OLD", mimeType: "image/jpeg" });
  latest.content.push({ type: "image", data: "NEW", mimeType: "image/jpeg" });
  const history = [user("看图片"), call("a"), old, call("b"), latest];
  const sent = context.project(history).messages;
  assert.equal(sent[2].content.some((b) => b.type === "image"), false);
  assert.equal(sent[4].content[1].data, "NEW");
  assert.equal(history[2].content[1].data, "OLD");
});

test("compaction preserves user goal, tool pairs and a full baseline for retained diffs", () => {
  const context = contextManager({ contextWindow: 12000, maxTokens: 1000 });
  const history = [user("找到中文设置")];
  for (let i = 0; i < 16; i++) history.push(call(`t${i}`), result(`t${i}`, { kind: i === 0 ? "full" : "diff", snapshot: i + 1, baseSnapshot: i, elements: [{ id: "e1", text: "初始" }], updated: [{ id: "e1", text: `页面${i}` }], text: "中文页面内容".repeat(240) }));
  for (const message of history.filter((m) => m.role === "toolResult")) message.content.push({ type: "text", text: `Archived screen image: ${"a".repeat(64)}. Use image_recall to inspect it.` });
  const sent = context.project(history).messages;
  assert.ok(sent.length < history.length);
  assert.equal(sent[0].content, "找到中文设置");
  assert.ok(sent.some((m) => typeof m.content === "string" && m.content.includes('"kind":"full"')));
  const baseline = JSON.parse(sent.find((m) => typeof m.content === "string" && m.content.startsWith("Screen baseline")).content.split("\n")[1]);
  assert.equal(baseline.elements[0].text, `页面${baseline.snapshot - 1}`);
  for (let i = 0; i < sent.length; i++) if (sent[i].role === "toolResult") assert.equal(sent[i - 1].content[0].id, sent[i].toolCallId);
  const again = context.project([...history, user("继续")]).messages;
  assert.deepEqual(again.slice(0, sent.length), sent);
});

test("context reduces recent rounds under pressure and rejects an irreducible input", () => {
  const context = contextManager({ contextWindow: 6000, maxTokens: 1000 });
  const history = [user("找到设置")];
  for (let i = 0; i < 10; i++) history.push(call(`t${i}`), result(`t${i}`, { text: "中文内容".repeat(300) }));
  const sent = context.project(history);
  assert.ok(sent.estimated < 3600);
  assert.ok(sent.messages.filter((m) => m.role === "toolResult").length <= 2);
  assert.throws(() => contextManager({ contextWindow: 6000, maxTokens: 1000 }).project([user("中文".repeat(4000))]), /上下文预算/);
});

test("image budgets check each message and decoded image size", () => {
  const context = contextManager({ ...model, inputLimits: { images: { maxPerMessage: 1, resize: { maxBytes: 3 } } } });
  const image = { type: "image", data: Buffer.from("large image").toString("base64"), mimeType: "image/jpeg" };
  const tool = result("a", {}); tool.content.push(image);
  assert.equal(context.project([user("读屏"), call("a"), tool]).messages[2].content.some((b) => b.type === "image"), false);
  assert.throws(() => context.project([{ role: "user", content: [image] }]), /图片.*预算/);
});

test("an interrupted tool batch gets explicit failed results before the next user task", () => {
  const history = [user("开始"), { ...call("a"), content: [...call("a").content, ...call("b").content] }, result("a", { ok: true }), user("继续")];
  const repaired = repairHistory(history);
  assert.equal(repaired[3].toolCallId, "b");
  assert.equal(repaired[3].isError, true);
  assert.equal(repaired[4].role, "user");
  assert.equal(history.length, 4);
});

test("compaction retains image recall references", () => {
  const reference = "a".repeat(64);
  const history = [user("继续分析刚才的图片")];
  for (let i = 0; i < 12; i++) history.push(call(`t${i}`), result(`t${i}`, { text: "中文内容".repeat(300) }));
  history[2].content.push({ type: "text", text: `Archived screen image: ${reference}. Use image_recall to inspect it.` });
  const sent = contextManager({ contextWindow: 6000, maxTokens: 1000 }).project(history).messages;
  assert.ok(sent.length < history.length);
  assert.ok(JSON.stringify(sent).includes(reference));
});
