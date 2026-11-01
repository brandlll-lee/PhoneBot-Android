import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { once } from "node:events";
import { homedir } from "node:os";
import { mkdtempSync, readFileSync, rmSync, mkdirSync, writeFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { startFixture } from "../src/fixture.mjs";

function engine(t, entry = "src/main.mjs", baseUrl, savedHome) {
  const args = baseUrl ? ["--input-type=module", "--eval", `
    process.argv[2] = "3";
    process.argv[3] = "1";
    const transport = globalThis.fetch;
    globalThis.fetch = (url, options) => transport(String(url).replace("https://api.deepseek.com", ${JSON.stringify(baseUrl)}), options);
    await import(${JSON.stringify(new URL(`../${entry}`, import.meta.url).href)});
  `] : [entry];
  const home = savedHome ?? (baseUrl ? mkdtempSync(fileURLToPath(new URL("../../.tools/engine-test-", import.meta.url))) : process.env.HOME ?? homedir());
  const child = spawn(process.execPath, args, { stdio: baseUrl ? ["ignore", "pipe", "pipe", "pipe"] : ["pipe", "pipe", "pipe"], env: { ...process.env, HOME: home } });
  const commands = baseUrl ? child.stdio[3] : child.stdin;
  const events = [];
  const credentials = new Map(baseUrl ? [["deepseek", { type: "api_key", key: "fixture-key" }]] : []);
  const seen = [];
  const readers = [];
  let stderr = "";
  child.stderr.on("data", (data) => { stderr += data; });
  createInterface({ input: child.stdout }).on("line", (line) => {
    const event = JSON.parse(line);
    if (event.type === "tool" && event.name.startsWith("vault_")) {
      const provider = event.params.provider;
      let result;
      if (event.name === "vault_read") result = credentials.get(provider) ?? null;
      if (event.name === "vault_list") result = [...credentials.keys()].map((providerId) => ({ providerId }));
      if (event.name === "vault_write") { credentials.set(provider, event.params.credential); result = true; }
      if (event.name === "vault_delete") { credentials.delete(provider); result = true; }
      commands.write(`${JSON.stringify({ type: "tool_result", id: event.id, result, secret: true })}\n`);
      return;
    }
    events.push(event); seen.push(event);
    readers.splice(0).forEach((notify) => notify());
  });
  const exited = once(child, "exit");
  t.after(async () => { child.kill("SIGKILL"); await exited; if (baseUrl && !savedHome) rmSync(home, { recursive: true, force: true }); });
  return {
    send: (command) => {
      const line = `${JSON.stringify(command)}\n`;
      if (command.type === "stop") commands.end(line); else commands.write(line);
    },
    async next(type) {
      const deadline = AbortSignal.timeout(20000);
      while (!events.some((event) => event.type === type)) {
        if (child.exitCode !== null) throw new Error(`Engine exited: ${stderr}`);
        await new Promise((resolve, reject) => {
          const fail = () => reject(new Error(`Missing ${type}; ${JSON.stringify(events)}; ${stderr}`));
          deadline.addEventListener("abort", fail, { once: true });
          readers.push(() => { deadline.removeEventListener("abort", fail); resolve(); });
        });
      }
      return events.splice(events.findIndex((event) => event.type === type), 1)[0];
    },
    exited,
    kill: () => child.kill("SIGKILL"),
    seen,
    home,
    entries: () => readFileSync(`${home}/sessions/conversation.jsonl`, "utf8").trim().split("\n").map(JSON.parse),
    transcript: () => readFileSync(`${home}/sessions/conversation.jsonl`, "utf8").trim().split("\n").map(JSON.parse).filter((e) => e.type === "message").map((e) => e.message),
  };
}

for (const entry of ["src/main.mjs", "dist/engine.mjs"]) {
  test(`${entry}: official sessions migrate, compact, resume and keep phone tools`, { timeout: 20000 }, async (t) => {
    const payloads = [];
    const summary = "The previous music request ended without finding a ranking. The user stopped it. Continue only from the next user message.";
    let turn = 0;
    const turns = [
      { tool_calls: [{ index: 0, id: "read", type: "function", function: { name: "screen_read", arguments: "{}" } }] },
      { content: "已读取当前页面。" }, { content: "继续查看刚才读取的页面。" },
    ];
    const fixture = await startFixture({ onRequest: (payload) => payloads.push(payload),
      turns: (body) => body.tools?.length ? turns[turn++] : { content: summary } });
    t.after(fixture.close);
    const home = mkdtempSync(fileURLToPath(new URL("../../.tools/session-test-", import.meta.url)));
    t.after(() => rmSync(home, { recursive: true, force: true }));
    mkdirSync(`${home}/transcripts`);
    const history = Array.from({ length: 4 }, (_, i) => [
      { role: "user", content: `旧请求${i}：查听歌次数`, timestamp: i + 1 },
      { role: "assistant", api: "openai-completions", provider: "deepseek", model: "deepseek-flash", stopReason: "stop",
        content: [{ type: "text", text: `旧结束${i}：未找到，用户已停止。${"页面记录。".repeat(16000)}` }],
        usage: { input: 990000, output: 20, cacheRead: 0, cacheWrite: 0, totalTokens: 990020,
          cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } }, timestamp: i + 1 },
    ]).flat();
    writeFileSync(`${home}/transcripts/conversation.json`, JSON.stringify(history));
    const host = engine(t, entry, fixture.baseUrl, home);
    await host.next("ready");
    host.send({ type: "task", session: "conversation", provider: "deepseek", model: "deepseek-flash", prompt: "读取当前页面" });
    await host.next("compaction_start");
    const compacted = await host.next("compaction_end");
    assert.equal(compacted.reason, "threshold");
    assert.equal(compacted.aborted, false);
    assert.equal(compacted.error, undefined);
    const read = await host.next("tool");
    assert.equal(read.name, "screen_read");
    host.send({ type: "tool_result", id: read.id, result: { kind: "full", snapshot: 1, elements: [{ id: "e1", text: "当前页面" }] } });
    assert.equal((await host.next("result")).text, "已读取当前页面。");
    await host.next("idle");
    const entries = host.entries();
    assert.equal(entries.filter((e) => e.type === "message" && e.message.role !== "system").length, history.length + 4);
    assert.ok(entries.find((e) => e.type === "compaction").summary.includes(summary));
    assert.ok(!existsSync(`${home}/transcripts/conversation.json`));
    assert.ok(JSON.stringify(payloads[0]).includes("旧结束0：未找到，用户已停止"));
    const live = payloads.filter((p) => p.tools?.length);
    assert.ok(JSON.stringify(live[0]).includes(summary));
    assert.ok(!JSON.stringify(live[0]).includes("旧请求0"));
    assert.equal(live[0].tools.length, 21);
    assert.ok(live[0].messages.some((m) => m.role === "system"));
    assert.ok(JSON.stringify(live[1]).includes("当前页面"));
    host.send({ type: "stop" });
    await host.exited;
    const resumed = engine(t, entry, fixture.baseUrl, home);
    await resumed.next("ready");
    resumed.send({ type: "task", session: "conversation", provider: "deepseek", model: "deepseek-flash", prompt: "继续刚才的任务" });
    assert.equal((await resumed.next("result")).text, "继续查看刚才读取的页面。");
    const restored = payloads.at(-1);
    assert.equal(restored.tools.length, 21);
    assert.ok(JSON.stringify(restored).includes(summary));
    assert.ok(JSON.stringify(restored).includes("已读取当前页面"));
    assert.deepEqual(restored.messages.findLast((m) => m.role === "user").content, [{ type: "text", text: "继续刚才的任务" }]);
    assert.equal(resumed.entries().filter((e) => e.type === "compaction").length, 1);
  });

  test(`${entry}: a cancelled session resumes through Pi after process restart`, async (t) => {
    const fixture = await startFixture({ turns: [
      { tool_calls: [{ index: 0, id: "pending", type: "function", function: { name: "screen_read", arguments: "{}" } }] },
      { content: "收到新请求。" },
    ] });
    t.after(fixture.close);
    const host = engine(t, entry, fixture.baseUrl);
    await host.next("ready");
    host.send({ type: "task", session: "conversation", provider: "deepseek", model: "deepseek-flash", prompt: "读取页面" });
    await host.next("tool");
    host.send({ type: "cancel" });
    await host.next("cancelled");
    await host.next("idle");
    assert.ok(host.transcript().some((m) => m.role === "user" && JSON.stringify(m.content).includes("读取页面")));
    host.send({ type: "stop" });
    await host.exited;
    const resumed = engine(t, entry, fixture.baseUrl, host.home);
    await resumed.next("ready");
    resumed.send({ type: "task", session: "conversation", provider: "deepseek", model: "deepseek-flash", prompt: "换个任务，在吗" });
    assert.equal((await resumed.next("result")).text, "收到新请求。");
    assert.equal(fixture.payload.tools.length, 21);
    const messages = fixture.messages;
    for (let i = 0; i < messages.length; i++) if (messages[i].role === "tool")
      assert.ok(messages.slice(0, i).some((m) => m.tool_calls?.some((call) => call.id === messages[i].tool_call_id)));
    assert.deepEqual(messages.findLast((m) => m.role === "user").content, [{ type: "text", text: "换个任务，在吗" }]);
  });

  test(`${entry}: Pi restores an interrupted tool call after process loss`, async (t) => {
    const fixture = await startFixture({ turns: [
      { tool_calls: [{ index: 0, id: "interrupted", type: "function", function: { name: "screen_read", arguments: "{}" } }] },
      { content: "按当前请求继续。" },
    ] });
    t.after(fixture.close);
    const host = engine(t, entry, fixture.baseUrl);
    await host.next("ready");
    host.send({ type: "task", session: "conversation", provider: "deepseek", model: "deepseek-flash", prompt: "旧任务" });
    await host.next("tool");
    host.kill();
    await host.exited;
    const resumed = engine(t, entry, fixture.baseUrl, host.home);
    await resumed.next("ready");
    resumed.send({ type: "task", session: "conversation", provider: "deepseek", model: "deepseek-flash", prompt: "当前请求" });
    assert.equal((await resumed.next("result")).text, "按当前请求继续。");
    assert.equal(fixture.payload.tools.length, 21);
    const messages = fixture.messages;
    for (const message of messages) for (const call of message.tool_calls ?? [])
      assert.ok(messages.some((m) => m.role === "tool" && m.tool_call_id === call.id));
    assert.deepEqual(messages.findLast((m) => m.role === "user").content, [{ type: "text", text: "当前请求" }]);
    assert.equal(fixture.evidence().requests, 2);
  });

  test(`${entry}: official restored context uses archived screen references`, async (t) => {
    const fixture = await startFixture({ turns: [{ content: "已收到新任务。" }] });
    t.after(fixture.close);
    const host = engine(t, entry, fixture.baseUrl);
    mkdirSync(`${host.home}/transcripts`);
    const assistant = (content, stopReason) => ({ role: "assistant", content, stopReason, timestamp: 1,
      api: "openai-completions", provider: "deepseek", model: "deepseek-flash", usage: { totalTokens: 0 } });
    writeFileSync(`${host.home}/transcripts/conversation.json`, JSON.stringify([
      { role: "user", content: "旧截图", timestamp: 1 },
      assistant([{ type: "toolCall", id: "screen", name: "screenshot", arguments: {} }], "toolUse"),
      { role: "toolResult", toolCallId: "screen", toolName: "screenshot", timestamp: 1,
        content: [{ type: "image", mimeType: "image/png", data: Buffer.from("original screen").toString("base64") }] },
      assistant([{ type: "text", text: "截图任务结束。" }], "stop"),
    ]));
    await host.next("ready");
    host.send({ type: "task", session: "conversation", provider: "deepseek", model: "deepseek-flash", prompt: "在吗" });
    assert.equal((await host.next("result")).text, "已收到新任务。");
    assert.match(JSON.stringify(fixture.messages), /Archived screen image: [a-f0-9]{64}/);
    assert.ok(host.transcript().some((m) => m.content?.some?.((b) => b.type === "image" && b.mimeType === "image/png")));
  });

  test(`${entry}: tasks end with Pi's final reply and receive the user's original prompt`, async (t) => {
    const call = (id, name, args) => ({ tool_calls: [{ index: 0, id, type: "function", function: { name, arguments: JSON.stringify(args) } }] });
    const fixture = await startFixture({ turns: [
      { reasoning_content: "先读取工具返回的真实音量。", content: "我先调整媒体音量。", ...call("volume", "volume_set", { percent: 55 }) }, { content: "媒体音量已设为 8/15。" },
      { content: "在的。" },
      call("launch", "app_launch", { package: "missing.app" }), { content: "手机上没有找到该应用。" },
      { content: "我将在独立屏幕处理任务。" },
    ] });
    t.after(fixture.close);
    const host = engine(t, entry, fixture.baseUrl);
    await host.next("ready");
    const run = (prompt) => host.send({ type: "task", session: "conversation", provider: "deepseek", model: "deepseek-flash", prompt });
    run("将媒体音量设为适中");
    const volume = await host.next("tool");
    assert.equal(volume.name, "volume_set");
    host.send({ type: "tool_result", id: volume.id, result: { ok: true, verified: true, value: 8, max: 15, muted: false } });
    const result = await host.next("result");
    assert.equal(result.text, "媒体音量已设为 8/15。");
    assert.equal(result.request, 2);
    const thinking = host.seen.find((e) => e.type === "thinking_delta");
    assert.equal(thinking.request, 1);
    assert.equal(thinking.contentIndex, 0);
    assert.equal(thinking.delta, "先读取工具返回的真实音量。");
    const progress = host.seen.find((e) => e.type === "text_delta");
    assert.equal(progress.request, 1);
    assert.equal(progress.contentIndex, 1);
    assert.equal(progress.delta, "我先调整媒体音量。");
    assert.equal(typeof progress.toolCalls, "boolean");
    assert.equal(host.seen.find((e) => e.type === "toolcall_start").toolCalls, true);
    assert.equal(host.seen.find((e) => e.type === "text_delta" && e.request === result.request).toolCalls, false);
    assert.deepEqual(host.seen.find((e) => e.type === "model_response").content.map((b) => b.type), ["thinking", "text", "toolCall"]);
    assert.ok(host.seen.indexOf(thinking) < host.seen.indexOf(progress));
    assert.ok(host.seen.indexOf(progress) < host.seen.findIndex((e) => e.type === "step"));
    assert.equal(host.seen.find((e) => e.type === "step").label, "设置媒体音量");
    assert.equal(fixture.evidence().requests, 2);
    const tools = fixture.payload.tools;
    const system = fixture.messages.find((m) => m.role === "system");
    assert.equal(tools.length, 21);
    assert.ok(system.content.includes("unlocked Android"));
    assert.deepEqual(fixture.messages.findLast((m) => m.role === "user").content, [{ type: "text", text: "将媒体音量设为适中" }]);
    await host.next("idle");
    run("在吗");
    const chat = await host.next("result");
    assert.equal(chat.text, "在的。");
    assert.equal(fixture.evidence().requests, 3);
    assert.equal(fixture.payload.tools?.length, tools.length);
    assert.deepEqual(fixture.payload.tools, tools);
    assert.deepEqual(fixture.messages.find((m) => m.role === "system"), system);
    assert.ok(fixture.messages.some((m) => m.role === "assistant" && JSON.stringify(m.content).includes("媒体音量已设为 8/15。")));
    assert.ok(fixture.messages.some((m) => m.role === "tool"));
    assert.ok(host.transcript().some((m) => m.role === "toolResult" && m.toolName === "volume_set"));
    assert.deepEqual(fixture.messages.findLast((m) => m.role === "user").content, [{ type: "text", text: "在吗" }]);
    assert.equal(host.seen.filter((e) => e.taskId === chat.taskId && e.type === "tool").length, 0);
    await host.next("idle");
    run("打开应用");
    const launch = await host.next("tool");
    assert.equal(launch.name, "app_launch");
    host.send({ type: "tool_result", id: launch.id, error: "未安装该应用" });
    const unavailable = await host.next("result");
    assert.equal(unavailable.text, "手机上没有找到该应用。");
    assert.equal(fixture.evidence().requests, 5);
    assert.deepEqual(fixture.payload.tools, tools);
    assert.deepEqual(fixture.messages.find((m) => m.role === "system"), system);
    await host.next("idle");
    for (const task of [result, chat, unavailable]) {
      assert.equal(host.seen.filter((e) => e.taskId === task.taskId && ["result", "error", "cancelled"].includes(e.type)).length, 1);
    }
    host.send({ type: "task", session: "conversation", provider: "deepseek", model: "deepseek-flash", screen: "virtual", prompt: "使用独立屏幕" });
    assert.equal((await host.next("result")).text, "我将在独立屏幕处理任务。");
    assert.match(fixture.messages.find((m) => m.role === "system").content, /session's Android virtual display/);
    assert.deepEqual(fixture.payload.tools, tools);
    await host.next("idle");
  });

  test(`${entry}: Pi makes two HTTP requests and consumes the host tool result`, async (t) => {
    const host = engine(t, entry);
    assert.equal((await host.next("ready")).pi, "1.4.0");
    for (let run = 0; run < 2; run++) {
      host.send({ type: "probe" });
      const tool = await host.next("tool");
      assert.equal(tool.name, "runtime_info");
      host.send({ type: "tool_result", id: tool.id, result: { platform: "test", pid: 42, text: "中文" } });
      const result = await host.next("result");
      assert.equal(result.evidence.requests, 2);
      assert.equal(JSON.parse(result.evidence.toolResult).pid, 42);
      assert.match(result.text, /中文/);
      await host.next("idle");
      assert.equal(host.seen.filter((event) => event.taskId === result.taskId && ["result", "error", "cancelled"].includes(event.type)).length, 1);
    }
    host.send({ type: "stop" });
    await host.next("stopped");
    assert.equal((await host.exited)[0], 0);
  });
}

test("stop cancels a pending host tool and exits", async (t) => {
  const host = engine(t);
  await host.next("ready");
  host.send({ type: "probe" });
  await host.next("tool");
  host.send({ type: "stop" });
  await host.next("stopped");
  assert.equal((await host.exited)[0], 0);
});

test("host tool errors reach the caller", async (t) => {
  const host = engine(t);
  await host.next("ready");
  host.send({ type: "probe" });
  const tool = await host.next("tool");
  host.send({ type: "tool_result", id: tool.id, error: "Host unavailable" });
  assert.match((await host.next("error")).message, /Kotlin tool failed/);
});

test("verification rejects models outside the Pi catalog and accepts the next command", async (t) => {
  const host = engine(t);
  await host.next("ready");
  host.send({ type: "verify", provider: "unknown", model: "test" });
  assert.match((await host.next("error")).message, /Pi/);
  host.send({ type: "probe" });
  const tool = await host.next("tool");
  host.send({ type: "probe" });
  assert.match((await host.next("error")).message, /busy/);
  host.send({ type: "tool_result", id: tool.id, result: { pid: 43 } });
  await host.next("result");
});

test("bundled provider catalog and Pi login persist credentials through the host store", async (t) => {
  const host = engine(t, "dist/engine.mjs");
  await host.next("ready");
  host.send({ type: "catalog", provider: "openai" });
  const catalog = await host.next("catalog");
  assert.ok(catalog.providers.length >= 40);
  assert.ok(catalog.providers.find((p) => p.id === "anthropic").auth.some((a) => a.id === "oauth"));
  assert.ok(catalog.models.length > 0);
  assert.ok(catalog.total >= catalog.models.length);
  await host.next("idle");

  host.send({ type: "login", provider: "deepseek", auth: "api_key" });
  const call = await host.next("tool");
  assert.equal(call.name, "auth_prompt");
  assert.equal(call.params.type, "secret");
  host.send({ type: "tool_result", id: call.id, result: "test-encrypted-key", secret: true });
  assert.equal((await host.next("account")).configured, true);
  await host.next("idle");
  host.send({ type: "logout", provider: "deepseek" });
  assert.equal((await host.next("account")).configured, false);
});

test("task cancellation releases the tool wait and leaves the engine ready", async (t) => {
  const host = engine(t);
  await host.next("ready");
  host.send({ type: "probe", taskId: "cancel-test" });
  const call = await host.next("tool");
  host.send({ type: "cancel" });
  await host.next("cancelled");
  await host.next("idle");
  host.send({ type: "tool_result", id: call.id, result: { stale: true } });
  host.send({ type: "probe" });
  const next = await host.next("tool");
  host.send({ type: "tool_result", id: next.id, result: { recovered: true } });
  assert.match((await host.next("result")).text, /recovered/);
});
