import { test } from "node:test";
import assert from "node:assert/strict";
import { Agent } from "@earendil-works/pi-agent-core";
import { createModels, createProvider } from "@earendil-works/pi-ai";
import { stream, streamSimple } from "@earendil-works/pi-ai/api/openai-completions";
import { phoneTools } from "../src/tools.mjs";
import { startFixture } from "../src/fixture.mjs";
import { taskEvidence } from "../src/evidence.mjs";

test("Pi dispatches a phone tool and sends its screen result in the next request", async (t) => {
  const fixture = await startFixture({ tool: "screen_read", params: { ocr: true } });
  t.after(fixture.close);
  const model = { id: "phone", name: "phone", provider: "fixture", api: "openai-completions", baseUrl: fixture.baseUrl,
    reasoning: false, input: ["text"], contextWindow: 10000, maxTokens: 1000, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } };
  const models = createModels();
  models.setProvider(createProvider({ id: "fixture", models: [model], auth: { apiKey: { name: "fixture", resolve: async () => ({ auth: { apiKey: "fixture" } }) } }, api: { stream, streamSimple } }));
  const calls = [];
  const agent = new Agent({ initialState: { model, tools: phoneTools(async (name, params) => {
    calls.push({ name, params });
    return { page: "page1", elements: [{ id: "page1:0", text: "中文输入" }], ocr: [{ text: "Hello" }] };
  }, model) }, streamFn: models.streamSimple.bind(models), toolExecution: "sequential" });
  await agent.prompt("读取页面");
  assert.deepEqual(calls, [{ name: "screen_read", params: { ocr: true } }]);
  assert.equal(fixture.evidence().requests, 2);
  assert.match(fixture.evidence().toolResult, /中文输入/);
});

test("Pi validates phone arguments before dispatch", async (t) => {
  const fixture = await startFixture({ tool: "swipe", params: { x: -1 } });
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

test("Pi dispatches two locally checked actions in one tool round trip", async (t) => {
  const steps = [{ tool: "tap", args: { text: "设置", expect: { text: "语言" } } }, { tool: "tap", args: { text: "语言" } }];
  const fixture = await startFixture({ tool: "do_steps", params: { steps } });
  t.after(fixture.close);
  const model = { id: "phone", name: "phone", provider: "fixture", api: "openai-completions", baseUrl: fixture.baseUrl,
    reasoning: false, input: ["text"], contextWindow: 10000, maxTokens: 1000, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } };
  const models = createModels();
  models.setProvider(createProvider({ id: "fixture", models: [model], auth: { apiKey: { name: "fixture", resolve: async () => ({ auth: { apiKey: "fixture" } }) } }, api: { stream, streamSimple } }));
  const calls = [];
  const agent = new Agent({ initialState: { model, tools: phoneTools(async (name, args) => {
    calls.push({ name, args }); return { ok: true, steps: [{ ok: true }, { ok: true }], screen: { kind: "full", snapshot: 1, elements: [] } };
  }, model) }, streamFn: models.streamSimple.bind(models), toolExecution: "sequential" });
  await agent.prompt("打开设置中的语言页面");
  assert.deepEqual(calls, [{ name: "do_steps", args: { steps } }]);
  assert.equal(fixture.evidence().requests, 2);
});

test("Pi rejects a false completion, checks the current page, and ends with an honest result", async (t) => {
  const call = (id, name, args) => ({ tool_calls: [{ index: 0, id, type: "function", function: { name, arguments: JSON.stringify(args) } }] });
  const fixture = await startFixture({ turns: [
    call("read", "screen_read", {}), { content: "相册已打开" },
    call("finish", "task_finish", { outcome: "completed", summary: "相册已打开", evidence: [{ callId: "read", claim: "相册在前台", condition: { package: "gallery" } }] }),
    call("blocked", "task_finish", { outcome: "blocked", summary: "相册尚未打开", evidence: [] }),
  ] });
  t.after(fixture.close);
  const model = { id: "phone", name: "phone", provider: "fixture", api: "openai-completions", baseUrl: fixture.baseUrl,
    reasoning: false, input: ["text"], contextWindow: 10000, maxTokens: 1000, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } };
  const models = createModels();
  models.setProvider(createProvider({ id: "fixture", models: [model], auth: { apiKey: { name: "fixture", resolve: async () => ({ auth: { apiKey: "fixture" } }) } }, api: { stream, streamSimple } }));
  const calls = [];
  const host = async (name) => {
    calls.push(name);
    return { taskId: "task", snapshot: 1, package: "dev.phonebot.android", ok: name !== "screen_check" };
  };
  const evidence = taskEvidence("task", host);
  const agent = new Agent({ initialState: { model, tools: phoneTools(host, model, undefined, undefined, evidence) },
    streamFn: models.streamSimple.bind(models), finishTurn: evidence.finishTurn, toolExecution: "sequential" });
  await agent.prompt("打开相册");
  assert.deepEqual(calls, ["screen_read", "screen_check"]);
  assert.equal(fixture.evidence().requests, 4);
  assert.equal(evidence.finished.outcome, "blocked");
  assert.equal(evidence.finished.summary, "相册尚未打开");
  assert.ok(agent.state.messages.some((m) => m.role === "toolResult" && m.isError));
});
