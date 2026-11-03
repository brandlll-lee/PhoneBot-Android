import { test } from "node:test";
import assert from "node:assert/strict";
import { Agent } from "@earendil-works/pi-agent-core";
import { createModels, createProvider } from "@earendil-works/pi-ai";
import { stream, streamSimple } from "@earendil-works/pi-ai/api/openai-completions";
import { phoneTools, modelResult } from "../src/tools.mjs";
import { startFixture } from "../src/fixture.mjs";
import { imageStore } from "../src/images.mjs";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

test("compact JSON preserves every element, diff baseline and action outcome", async () => {
  const defaults = { resourceId: "", text: "", description: "", parent: null, enabled: true,
    clickable: false, editable: false, focused: false, selected: false, scrollable: false, checkable: false, checked: false };
  const elements = Array.from({ length: 160 }, (_, i) => ({ ...defaults, id: `e${i}`, bounds: [0, i, 1080, i + 1],
    label: `目标 ${i}`, class: "android.view.View", parent: i ? "e0" : null,
    ...(i % 2 ? { selected: true, enabled: false, checked: true, resourceId: "result" } : {}) }));
  const view = { ok: false, executed: true, conditionBefore: false, conditionMet: false, changed: false, durationMs: 0,
    stopReason: "timeout", error: "目标未出现", screen: { kind: "diff", snapshot: 2, baseSnapshot: 1,
      added: elements, updated: elements.slice(1, 2), removed: ["previous"], ocr: [] },
    unknown: { selected: false, enabled: null, text: "", bounds: [] } };
  const original = structuredClone(view);
  const compact = modelResult(view);
  for (const key of ["added", "updated"]) compact.screen[key] = compact.screen[key].map((element) => ({ ...defaults, ...element }));
  assert.deepEqual(compact, original);
  assert.deepEqual(view, original);
  assert.ok(Buffer.byteLength(JSON.stringify(modelResult(view))) < Buffer.byteLength(JSON.stringify(view)) * .7);
  const invalidDefaults = { id: "e", bounds: [], selected: null, checked: 0, enabled: false, text: null, parent: "" };
  assert.deepEqual(modelResult(invalidDefaults), invalidDefaults);
  const result = await phoneTools(async () => view, { input: ["text"] }).find((tool) => tool.name === "do_steps").execute("batch", {});
  assert.equal(result.isError, true);
  assert.deepEqual(result.details, original);
  assert.deepEqual(JSON.parse(result.content[0].text), modelResult(view));
});

test("Pi dispatches a phone tool and sends its screen result in the next request", async (t) => {
  const fixture = await startFixture({ tool: "screen_read", params: { ocr: true } });
  t.after(fixture.close);
  const model = { id: "phone", name: "phone", provider: "fixture", api: "openai-completions", baseUrl: fixture.baseUrl,
    reasoning: false, input: ["text"], contextWindow: 10000, maxTokens: 1000, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } };
  const models = createModels();
  models.setProvider(createProvider({ id: "fixture", models: [model], auth: { apiKey: { name: "fixture", resolve: async () => ({ auth: { apiKey: "fixture" } }) } }, api: { stream, streamSimple } }));
  const calls = [];
  const screen = { kind: "full", snapshot: 1, treeReadable: true, elements: [
    { id: "e1", parent: null, label: "中文输入", clickable: false, scrollable: true, bounds: [0, 0, 1080, 200] },
    { id: "e2", parent: "e1", label: "中文输入", text: "中文输入", clickable: true, scrollable: false, bounds: [0, 0, 1080, 200] },
  ], ocr: [{ text: "Hello" }] };
  const agent = new Agent({ initialState: { model, tools: phoneTools(async (name, params) => {
    calls.push({ name, params });
    return screen;
  }, model) }, streamFn: models.streamSimple.bind(models), toolExecution: "sequential" });
  await agent.prompt("读取页面");
  assert.deepEqual(calls, [{ name: "screen_read", params: { ocr: true } }]);
  assert.equal(fixture.evidence().requests, 2);
  assert.deepEqual(JSON.parse(fixture.evidence().toolResult), modelResult(screen));
  assert.deepEqual(agent.state.messages.find((message) => message.role === "toolResult").details, screen);
  assert.ok(fixture.payload.tools.find((tool) => tool.function.name === "screen_read").function.description.includes("enabled:true"));
});

test("Pi can use an archived screenshot directly in its final Markdown answer", async (t) => {
  const root = fileURLToPath(new URL("../../.tools/tests/", import.meta.url));
  await mkdir(root, { recursive: true });
  const directory = await mkdtemp(join(root, "answer-image-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const path = join(directory, "screen.jpg"), bytes = Buffer.from([255, 216, 255, 217]);
  await writeFile(path, bytes);
  const fixture = await startFixture({ turns: (body) => {
    const result = body.messages.findLast((message) => message.role === "tool")?.content;
    if (!result) return { tool_calls: [{ index: 0, id: "screen", type: "function", function: { name: "screenshot", arguments: "{}" } }] };
    const text = Array.isArray(result) ? result.find((block) => block.type === "text").text : result;
    return { content: `当前屏幕：\n\n![手机截图](${JSON.parse(text).imageReference})` };
  } });
  t.after(fixture.close);
  const model = { id: "phone", name: "phone", provider: "fixture", api: "openai-completions", baseUrl: fixture.baseUrl,
    reasoning: false, input: ["text", "image"], contextWindow: 10000, maxTokens: 1000, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } };
  const models = createModels();
  models.setProvider(createProvider({ id: "fixture", models: [model], auth: { apiKey: { name: "fixture", resolve: async () => ({ auth: { apiKey: "fixture" } }) } }, api: { stream, streamSimple } }));
  const images = imageStore(directory, "image-bot", async () => ({}));
  const agent = new Agent({ initialState: { model, tools: phoneTools(async () => ({ image: path }), model, undefined, images) }, streamFn: models.streamSimple.bind(models) });
  await agent.prompt("截取当前屏幕并在回答里显示");
  const result = agent.state.messages.find((message) => message.role === "toolResult");
  const answer = agent.state.messages.at(-1).content.find((block) => block.type === "text").text;
  assert.equal(answer, `当前屏幕：\n\n![手机截图](${result.details.imageReference})`);
  assert.deepEqual(await readFile(join(directory, result.details.imageReference.split("/").at(-1))), bytes);
  assert.equal(fixture.evidence().requests, 2);
});

for (const [tool, params] of [["swipe", { x: -1 }], ["input_text", { element: "搜索", text: "粤语" }]])
test(`Pi validates ${tool} arguments before dispatch`, async (t) => {
  const fixture = await startFixture({ tool, params });
  t.after(fixture.close);
  const model = { id: "phone", name: "phone", provider: "fixture", api: "openai-completions", baseUrl: fixture.baseUrl,
    reasoning: false, input: ["text"], contextWindow: 10000, maxTokens: 1000, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } };
  const models = createModels();
  models.setProvider(createProvider({ id: "fixture", models: [model], auth: { apiKey: { name: "fixture", resolve: async () => ({ auth: { apiKey: "fixture" } }) } }, api: { stream, streamSimple } }));
  let calls = 0;
  const agent = new Agent({ initialState: { model, tools: phoneTools(async () => { calls++; return {}; }, model) }, streamFn: models.streamSimple.bind(models) });
  await agent.prompt("滑动");
  assert.equal(calls, 0);
  assert.ok(agent.state.messages.some((m) => m.role === "toolResult" && m.isError));
});

test("Pi preserves explicit substring conditions and uncertain input evidence", async (t) => {
  const steps = [{ tool: "input_text", args: { element: "e12", text: "粤语 歌单", expect: { textContains: "歌单", editable: true } } },
    { tool: "tap", args: { text: "搜索" } }];
  const fixture = await startFixture({ tool: "do_steps", params: { steps } });
  t.after(fixture.close);
  const model = { id: "phone", name: "phone", provider: "fixture", api: "openai-completions", baseUrl: fixture.baseUrl,
    reasoning: false, input: ["text"], contextWindow: 10000, maxTokens: 1000, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } };
  const models = createModels();
  models.setProvider(createProvider({ id: "fixture", models: [model], auth: { apiKey: { name: "fixture", resolve: async () => ({ auth: { apiKey: "fixture" } }) } }, api: { stream, streamSimple } }));
  const evidence = { ok: true, canContinue: false, remaining: 1, steps: [{ tool: "input_text", ok: true, executed: true,
    inputVerified: null, actualText: "搜索, 粤语 歌单", hintText: "搜索", showingHintText: false, stopReason: "input_unknown" }] };
  let calls = 0;
  const agent = new Agent({ initialState: { model, tools: phoneTools(async (name, params) => {
    assert.equal(name, "do_steps"); assert.deepEqual(params, { steps }); calls++; return evidence;
  }, model) }, streamFn: models.streamSimple.bind(models) });
  await agent.prompt("搜索粤语歌单");
  assert.equal(calls, 1);
  assert.equal(fixture.evidence().requests, 2);
  assert.deepEqual(JSON.parse(fixture.evidence().toolResult), evidence);
  assert.equal(agent.state.messages.find((message) => message.role === "toolResult").isError, false);
});

test("Pi forwards batch dispatch, wait outcomes and screen changes in one tool round trip", async (t) => {
  const steps = [{ tool: "tap", args: { text: "设置", expect: { text: "语言" } } }, { tool: "tap", args: { text: "语言" } }];
  const fixture = await startFixture({ tool: "do_steps", params: { steps } });
  t.after(fixture.close);
  const model = { id: "phone", name: "phone", provider: "fixture", api: "openai-completions", baseUrl: fixture.baseUrl,
    reasoning: false, input: ["text"], contextWindow: 10000, maxTokens: 1000, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } };
  const models = createModels();
  models.setProvider(createProvider({ id: "fixture", models: [model], auth: { apiKey: { name: "fixture", resolve: async () => ({ auth: { apiKey: "fixture" } }) } }, api: { stream, streamSimple } }));
  const calls = [];
  const result = { ok: true, canContinue: false, remaining: 1, steps: [{ tool: "tap", ok: true, executed: true,
    changed: true, treeStable: true, conditionBefore: false, conditionMet: false, stopReason: "timeout" }],
    screen: { kind: "diff", baseSnapshot: 1, snapshot: 2, added: [], updated: [{ id: "e2", parent: "e1", label: "加载中" }], removed: [] } };
  const agent = new Agent({ initialState: { model, tools: phoneTools(async (name, args) => {
    calls.push({ name, args }); return result;
  }, model) }, streamFn: models.streamSimple.bind(models), toolExecution: "sequential" });
  await agent.prompt("打开设置中的语言页面");
  assert.deepEqual(calls, [{ name: "do_steps", args: { steps } }]);
  assert.equal(fixture.evidence().requests, 2);
  assert.deepEqual(JSON.parse(fixture.evidence().toolResult), result);
});
