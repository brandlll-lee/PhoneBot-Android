import { createServer } from "node:http";

export async function startFixture({ hang = false, fail = false, tool = "runtime_info", params = {}, turns } = {}) {
  let requests = 0;
  let toolResult;
  let messages;
  const server = createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString());
    messages = body.messages;
    requests++;
    if (hang) return;
    if (fail) { response.writeHead(401); response.end('{"error":{"message":"Fixture rejected request"}}'); return; }
    toolResult = body.messages.findLast((message) => message.role === "tool")?.content;
    response.writeHead(200, { "Content-Type": "text/event-stream" });
    const delta = turns?.[requests - 1] ?? (toolResult
      ? { content: `Tool result received: ${toolResult}` }
      : { tool_calls: [{ index: 0, id: "probe-tool", type: "function", function: { name: tool, arguments: JSON.stringify(params) } }] });
    for (const [part, reason] of [[delta, null], [{}, delta.tool_calls ? "tool_calls" : "stop"]]) {
      response.write(`data: ${JSON.stringify({ id: "probe", object: "chat.completion.chunk", created: 1, model: "phonebot-probe", choices: [{ index: 0, delta: part, finish_reason: reason }] })}\n\n`);
    }
    response.end("data: [DONE]\n\n");
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  return {
    baseUrl: `http://127.0.0.1:${server.address().port}/v1`,
    evidence: () => ({ requests, toolResult }),
    get messages() { return messages; },
    close: () => { server.closeAllConnections(); server.close(); },
  };
}
