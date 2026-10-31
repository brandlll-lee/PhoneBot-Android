import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { once } from "node:events";

function engine(t, entry = "src/main.mjs") {
  const child = spawn(process.execPath, [entry], { stdio: ["pipe", "pipe", "pipe"] });
  const events = [];
  const seen = [];
  const readers = [];
  let stderr = "";
  child.stderr.on("data", (data) => { stderr += data; });
  createInterface({ input: child.stdout }).on("line", (line) => {
    const event = JSON.parse(line);
    events.push(event); seen.push(event);
    readers.splice(0).forEach((notify) => notify());
  });
  const exited = once(child, "exit");
  t.after(() => child.kill());
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
  };
}

for (const entry of ["src/main.mjs", "dist/engine.mjs"]) {
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
