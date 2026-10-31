import { test } from "node:test";
import assert from "node:assert/strict";
import { Agent } from "@earendil-works/pi-agent-core";
import { createModels, createProvider } from "@earendil-works/pi-ai";
import { stream, streamSimple } from "@earendil-works/pi-ai/api/openai-completions";
import { phoneTools } from "../src/tools.mjs";
import { startFixture } from "../src/fixture.mjs";
import { contextManager } from "../src/context.mjs";

test("Pi dispatches a phone tool and sends its screen result in the next request", async (t) => {
  const fixture = await startFixture({ tool: "screen_read", params: { ocr: true } });
  t.after(fixture.close);
  const model = { id: "phone", name: "phone", provider: "fixture", api: "openai-completions", baseUrl: fixture.baseUrl,
    reasoning: false, input: ["text"], contextWindow: 10000, maxTokens: 1000, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } };
  const models = createModels();
  models.setProvider(createProvider({ id: "fixture", models: [model], auth: { apiKey: { name: "fixture", resolve: async () => ({ auth: { apiKey: "fixture" } }) } }, api: { stream, streamSimple } }));
  const calls = [];
  const screen = { kind: "full", snapshot: 1, treeReadable: true, elements: [
    { id: "e1", parent: null, label: "中文输入", clickable: false, scrollable: true },
    { id: "e2", parent: "e1", label: "中文输入", text: "中文输入", clickable: true, scrollable: false },
  ], ocr: [{ text: "Hello" }] };
  const agent = new Agent({ initialState: { model, tools: phoneTools(async (name, params) => {
    calls.push({ name, params });
    return screen;
  }, model) }, streamFn: models.streamSimple.bind(models), toolExecution: "sequential" });
  await agent.prompt("读取页面");
  assert.deepEqual(calls, [{ name: "screen_read", params: { ocr: true } }]);
  assert.equal(fixture.evidence().requests, 2);
  assert.deepEqual(JSON.parse(fixture.evidence().toolResult), screen);
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

test("real Pi preserves its system message and tool declarations through restored history and compaction", async (t) => {
  const turns = Array.from({ length: 12 }, (_, i) => ({ tool_calls: [{ index: 0, id: `read-${i}`, type: "function",
    function: { name: "screen_read", arguments: "{}" } }] }));
  const fixture = await startFixture({ turns: [...turns, { content: "已检查当前页面，未找到目标。" }] });
  t.after(fixture.close);
  const model = { id: "phone", name: "phone", provider: "fixture", api: "openai-completions", baseUrl: fixture.baseUrl,
    reasoning: false, input: ["text"], contextWindow: 16000, maxTokens: 1000, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } };
  const models = createModels();
  models.setProvider(createProvider({ id: "fixture", models: [model], auth: { apiKey: { name: "fixture", resolve: async () => ({ auth: { apiKey: "fixture" } }) } }, api: { stream, streamSimple } }));
  const history = [{ role: "user", content: "上次任务", timestamp: 1 },
    { role: "assistant", api: model.api, provider: model.provider, model: model.id, usage: { totalTokens: 0 }, stopReason: "toolUse",
      content: [{ type: "toolCall", id: "old", name: "screen_read", arguments: {} }], timestamp: 1 },
    { role: "toolResult", toolCallId: "old", toolName: "screen_read", content: [{ type: "text", text: JSON.stringify({ kind: "full", snapshot: 99, elements: [{ id: "e-old", text: "旧目标" }] }) }], timestamp: 1 },
    { role: "assistant", api: model.api, provider: model.provider, model: model.id, usage: { totalTokens: 0 }, stopReason: "stop",
      content: [{ type: "text", text: "上次未找到。" }], timestamp: 1 }];
  const original = structuredClone(history), compacted = [], payloads = [];
  const context = contextManager(model, (event) => compacted.push(event), history.length);
  let reads = 0;
  const agent = new Agent({ initialState: { model, systemPrompt: "Observe the current phone. Report only observed results.", messages: history,
    tools: phoneTools(async () => {
      reads++;
      const element = { id: "e-current", parent: "e-list", label: `当前${reads}`, text: "界面内容".repeat(400), clickable: true, scrollable: false };
      return reads === 1 ? { kind: "full", snapshot: reads, elements: [{ id: "e-list", parent: null, label: "列表", scrollable: true }, element] } :
        { kind: "diff", baseSnapshot: reads - 1, snapshot: reads, added: [], removed: [], updated: [element] };
    }, model) }, streamFn: models.streamSimple.bind(models), toolExecution: "sequential",
    transformContext: async (messages) => {
      const projected = context.project(messages).messages;
      assert.deepEqual(projected.filter((m) => m.role === "system"), messages.filter((m) => m.role === "system"));
      return projected;
    },
    onPayload: (payload) => { payloads.push(JSON.parse(JSON.stringify(payload))); },
  });
  const system = structuredClone(agent.state.messages[0]);
  await agent.prompt("在当前界面查找目标");
  assert.notEqual(agent.state.messages.at(-1).stopReason, "error", agent.state.messages.at(-1).errorMessage);
  assert.equal(reads, 12);
  assert.equal(fixture.evidence().requests, 13);
  assert.ok(compacted.length > 0);
  assert.deepEqual(fixture.payload, payloads.at(-1));
  for (const payload of payloads) {
    assert.equal(payload.tools?.length, system.toolsAdded.length);
    assert.deepEqual(payload.tools, payloads[0].tools);
    assert.deepEqual(payload.messages.find((m) => m.role === "system"), payloads[0].messages.find((m) => m.role === "system"));
    const wire = JSON.stringify(payload.messages);
    assert.ok(wire.includes("在当前界面查找目标"));
    assert.ok(!wire.includes("e-old") && !wire.includes("旧目标"));
    for (let i = 0; i < payload.messages.length; i++) if (payload.messages[i].role === "tool")
      assert.ok(payload.messages[i - 1].tool_calls.some((call) => call.id === payload.messages[i].tool_call_id));
  }
  assert.ok(JSON.stringify(payloads[0].messages).includes("Previous reply (historical)"));
  assert.ok(JSON.stringify(fixture.messages).includes("Screen baseline retained"));
  assert.equal(JSON.parse(fixture.evidence().toolResult).snapshot, 12);
  assert.deepEqual(agent.state.messages[0], system);
  assert.deepEqual(history, original);
});
