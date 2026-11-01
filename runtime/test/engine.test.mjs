import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { once } from "node:events";
import { homedir } from "node:os";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { startFixture } from "../src/fixture.mjs";

function engine(t, entry = "src/main.mjs", baseUrl) {
  const args = baseUrl ? ["--input-type=module", "--eval", `
    process.argv[2] = "0";
    process.argv[3] = "1";
    const transport = globalThis.fetch;
    globalThis.fetch = (url, options) => transport(String(url).replace("https://api.deepseek.com", ${JSON.stringify(baseUrl)}), options);
    await import(${JSON.stringify(new URL(`../${entry}`, import.meta.url).href)});
  `] : [entry];
  const home = baseUrl ? mkdtempSync(fileURLToPath(new URL("../../.tools/engine-test-", import.meta.url))) : process.env.HOME ?? homedir();
  const child = spawn(process.execPath, args, { stdio: ["pipe", "pipe", "pipe"], env: { ...process.env, HOME: home } });
  const events = [];
  const seen = [];
  const readers = [];
  let stderr = "";
  child.stderr.on("data", (data) => { stderr += data; });
  createInterface({ input: child.stdout }).on("line", (line) => {
    const event = JSON.parse(line);
    if (baseUrl && event.type === "tool" && event.name === "vault_read") {
      child.stdin.write(`${JSON.stringify({ type: "tool_result", id: event.id, result: { type: "api_key", key: "fixture-key" }, secret: true })}\n`);
      return;
    }
    events.push(event); seen.push(event);
    readers.splice(0).forEach((notify) => notify());
  });
  const exited = once(child, "exit");
  t.after(async () => { child.kill(); await exited; if (baseUrl) rmSync(home, { recursive: true, force: true }); });
  return {
    send: (command) => child.stdin.write(`${JSON.stringify(command)}\n`),
    async next(type) {
      const deadline = AbortSignal.timeout(10000);
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
    seen,
    transcript: () => JSON.parse(readFileSync(`${home}/transcripts/conversation.json`, "utf8")),
  };
}

for (const entry of ["src/main.mjs", "dist/engine.mjs"]) {
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
    assert.ok(fixture.messages.some((m) => m.role === "assistant" && JSON.stringify(m.content).includes("Previous reply (historical)")));
    assert.ok(!fixture.messages.some((m) => m.role === "tool"));
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
  let call = await host.next("tool");
  assert.equal(call.name, "vault_list");
  host.send({ type: "tool_result", id: call.id, result: [] });
  const catalog = await host.next("catalog");
  assert.ok(catalog.providers.length >= 40);
  assert.ok(catalog.providers.find((p) => p.id === "anthropic").auth.some((a) => a.id === "oauth"));
  assert.ok(catalog.models.length > 0);
  assert.ok(catalog.total >= catalog.models.length);
  await host.next("idle");

  host.send({ type: "login", provider: "deepseek", auth: "api_key" });
  call = await host.next("tool");
  assert.equal(call.name, "auth_prompt");
  assert.equal(call.params.type, "secret");
  host.send({ type: "tool_result", id: call.id, result: "test-encrypted-key", secret: true });
  call = await host.next("tool");
  assert.equal(call.name, "vault_read");
  host.send({ type: "tool_result", id: call.id, result: null });
  call = await host.next("tool");
  assert.equal(call.name, "vault_write");
  assert.equal(call.params.credential.key, "test-encrypted-key");
  host.send({ type: "tool_result", id: call.id, result: true });
  assert.equal((await host.next("account")).configured, true);
  await host.next("idle");
  host.send({ type: "logout", provider: "deepseek" });
  call = await host.next("tool");
  assert.equal(call.name, "vault_delete");
  host.send({ type: "tool_result", id: call.id, result: true });
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
