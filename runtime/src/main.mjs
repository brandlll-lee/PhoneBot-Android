import { createReadStream, writeSync } from "node:fs";
import { createInterface } from "node:readline";
import { Agent } from "@earendil-works/pi-agent-core";
import { Type, createModels, createProvider } from "@earendil-works/pi-ai";
import { stream, streamSimple } from "@earendil-works/pi-ai/api/openai-completions";
import { startFixture } from "./fixture.mjs";

const input = process.argv[2] ? createReadStream(null, { fd: Number(process.argv[2]) }) : process.stdin;
const output = process.argv[3] ? Number(process.argv[3]) : 1;
const send = (event) => writeSync(output, `${JSON.stringify(event)}\n`);
const pendingTools = new Map();
let agent;
let task;
let stopping = false;

function callKotlin(id, signal) {
  return new Promise((resolve, reject) => {
    const finish = (result, error) => {
      pendingTools.delete(id);
      signal?.removeEventListener("abort", abort);
      error ? reject(error) : resolve(result);
    };
    const abort = () => finish(undefined, new Error("Tool cancelled"));
    if (signal?.aborted) { abort(); return; }
    signal?.addEventListener("abort", abort, { once: true });
    pendingTools.set(id, finish);
    send({ type: "tool", id, name: "runtime_info" });
  });
}

async function run(command) {
  let fixture;
  try {
    let config = command.config;
    if (command.type === "probe") {
      fixture = await startFixture();
      config = { baseUrl: fixture.baseUrl, model: "phonebot-probe", apiKey: "local-fixture" };
    }
    if (stopping) return;
    if (!config || !config.model?.trim() || !config.apiKey?.trim()) throw new Error("Model and API key are required");
    const url = new URL(config.baseUrl);
    if (command.type === "verify" && url.protocol !== "https:") throw new Error("Model endpoint must use HTTPS");
    const model = {
      id: config.model, name: config.model, provider: "phonebot", api: "openai-completions",
      baseUrl: url.href.replace(/\/$/, ""), reasoning: false, input: ["text"],
      contextWindow: 32768, maxTokens: 1024,
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    };
    const models = createModels();
    models.setProvider(createProvider({
      id: "phonebot", models: [model], api: { stream, streamSimple },
      auth: { apiKey: { name: "API key", resolve: async () => ({ auth: { apiKey: config.apiKey }, source: "verification" }) } },
    }));
    let calls = 0;
    agent = new Agent({
      initialState: {
        model, thinkingLevel: "off",
        systemPrompt: "Call runtime_info once. Then report its result in one sentence.",
        tools: [{
          name: "runtime_info", label: "Runtime information",
          description: "Get information from the Android engine host.", parameters: Type.Object({}),
          execute: async (id, _params, signal) => {
            if (calls++) throw new Error("Runtime information was already requested");
            const details = await callKotlin(id, signal);
            return { content: [{ type: "text", text: JSON.stringify(details) }], details };
          },
        }],
      },
      streamFn: models.streamSimple.bind(models), toolExecution: "sequential",
      prepareNextTurn: () => {
        if (agent.state.messages.filter((m) => m.role === "assistant").length >= 3) throw new Error("Verification exceeded three turns");
      },
    });
    send({ type: "running" });
    const deadline = setTimeout(() => agent?.abort(), 30000);
    try { await agent.prompt("Check the runtime information."); } finally { clearTimeout(deadline); }
    const last = agent.state.messages.findLast((m) => m.role === "assistant");
    if (last?.stopReason === "error" || last?.stopReason === "aborted") {
      throw new Error(last.stopReason === "aborted" ? "Verification cancelled or timed out" : (last.errorMessage || "Model request failed"));
    }
    if (calls !== 1) throw new Error("Model did not call runtime_info");
    const failedTool = agent.state.messages.find((m) => m.role === "toolResult" && m.isError);
    if (failedTool) throw new Error("Kotlin tool failed");
    const evidence = fixture?.evidence();
    if (evidence && (evidence.requests !== 2 || !evidence.toolResult)) throw new Error("Self-check evidence is incomplete");
    send({ type: "result", text: last.content.filter((b) => b.type === "text").map((b) => b.text).join(""), evidence });
  } catch (error) {
    if (!stopping) {
      const key = command.config?.apiKey;
      const message = String(error.message || error);
      send({ type: "error", message: key ? message.replaceAll(key, "[redacted]") : message });
    }
  } finally {
    fixture?.close();
    agent = undefined;
  }
}

const lines = createInterface({ input, crlfDelay: Infinity });
async function stop() {
  if (stopping) return;
  stopping = true;
  agent?.abort();
  await task;
  send({ type: "stopped" });
  lines.close();
  input.destroy();
  process.exitCode = 0;
}
lines.on("line", (line) => {
  try {
    if (line.length > 65536) throw new Error("Command exceeds 64 KiB");
    const command = JSON.parse(line);
    if (command.type === "stop") { void stop(); return; }
    if (stopping) return;
    if (command.type === "tool_result") {
      pendingTools.get(command.id)?.(command.result, command.error ? new Error(command.error) : undefined);
    } else if (command.type === "probe" || command.type === "verify") {
      if (task) throw new Error("Engine is busy");
      task = run(command).finally(() => { task = undefined; });
    } else throw new Error("Unknown command");
  } catch (error) { send({ type: "error", message: error.message }); }
});
lines.on("close", () => { if (!stopping) void stop(); });
send({ type: "ready", node: process.version, pi: "1.4.0", pid: process.pid });
