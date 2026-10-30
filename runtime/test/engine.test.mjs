import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { once } from "node:events";

function engine(t, entry = "src/main.mjs") {
  const child = spawn(process.execPath, [entry], { stdio: ["pipe", "pipe", "pipe"] });
  const events = [];
  const readers = [];
  let stderr = "";
  child.stderr.on("data", (data) => { stderr += data; });
  createInterface({ input: child.stdout }).on("line", (line) => {
    events.push(JSON.parse(line));
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

test("verification validates HTTPS and accepts the next command after failure", async (t) => {
  const host = engine(t);
  await host.next("ready");
  host.send({ type: "verify", config: { baseUrl: "http://example.com", model: "test", apiKey: "secret" } });
  assert.match((await host.next("error")).message, /HTTPS/);
  host.send({ type: "probe" });
  const tool = await host.next("tool");
  host.send({ type: "probe" });
  assert.match((await host.next("error")).message, /busy/);
  host.send({ type: "tool_result", id: tool.id, result: { pid: 43 } });
  await host.next("result");
});
