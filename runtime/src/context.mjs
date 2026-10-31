const omitted = "Earlier screen image omitted. Read the current screen if needed.";
const imagesOf = (message) => Array.isArray(message.content) ? message.content.filter((b) => b.type === "image") : [];
const textOf = (message) => typeof message.content === "string" ? message.content : (message.content || []).filter((b) => b.type === "text").map((b) => b.text).join("\n");
const tokenText = (text) => [...text].reduce((n, c) => n + (c.charCodeAt(0) > 127 ? 1 : 0.25), 0);
const estimate = (messages) => messages.reduce((n, m) => {
  const calls = Array.isArray(m.content) ? m.content.filter((b) => b.type === "toolCall") : [];
  return n + tokenText(textOf(m)) + imagesOf(m).length * 1600 + calls.reduce((sum, b) => sum + tokenText(JSON.stringify(b.arguments)), 0) + 12;
}, 0);

function screenBaseline(messages) {
  let baseline;
  for (const message of messages) {
    if (message.role !== "toolResult") continue;
    try {
      const value = JSON.parse(textOf(message));
      const view = value.screen || value;
      if (view.kind === "full") baseline = view;
      if (view.kind === "diff") {
        if (baseline?.snapshot !== view.baseSnapshot) { baseline = undefined; continue; }
        const elements = new Map(baseline.elements.map((e) => [e.id, e]));
        for (const id of view.removed || []) elements.delete(id);
        for (const element of [...(view.added || []), ...(view.updated || [])]) elements.set(element.id, element);
        baseline = { ...view, kind: "full", elements: [...elements.values()] };
        delete baseline.baseSnapshot; delete baseline.added; delete baseline.updated; delete baseline.removed;
      }
    } catch { /* Tool failures can contain plain text. */ }
  }
  return baseline;
}

export function repairHistory(messages) {
  const out = [];
  for (let i = 0; i < messages.length; i++) {
    const message = messages[i];
    out.push(message);
    if (message.role !== "assistant") continue;
    const calls = (message.content || []).filter((b) => b.type === "toolCall");
    const finished = new Set();
    let j = i + 1;
    while (messages[j]?.role === "toolResult") { finished.add(messages[j].toolCallId); out.push(messages[j++]); }
    for (const call of calls) if (!finished.has(call.id)) out.push({ role: "toolResult", toolCallId: call.id, toolName: call.name,
      content: [{ type: "text", text: "Task interrupted before this tool completed. Inspect the current screen before further actions." }], isError: true, timestamp: Date.now() });
    i = j - 1;
  }
  return out;
}

export function contextManager(model, report = () => {}) {
  let base, covered = 0, calibrated = 1;
  function observe(estimated, usage) {
    const actual = usage?.input + (usage?.cacheRead || 0) + (usage?.cacheWrite || 0);
    if (estimated > 0 && actual > 0) calibrated = Math.max(1, actual / estimated);
  }
  function limitImages(messages) {
    const limits = model.inputLimits;
    let count = messages.reduce((n, m) => n + imagesOf(m).length, 0);
    let bytes = messages.reduce((n, m) => n + imagesOf(m).reduce((sum, b) => sum + b.data.length, 0), 0);
    const maxCount = limits?.images?.maxPerRequest ?? 8;
    const maxBytes = Math.min(limits?.maxRequestBytes ?? Infinity, 4 * 1024 * 1024);
    const sent = messages.map((m) => {
      if (m.role === "user" || !imagesOf(m).length) return m;
      let local = imagesOf(m).length;
      const content = m.content.map((b) => {
        if (b.type !== "image" || (count <= maxCount && bytes <= maxBytes && local <= (limits?.images?.maxPerMessage ?? Infinity) && Buffer.byteLength(b.data, "base64") <= (limits?.images?.resize?.maxBytes ?? Infinity))) return b;
        count--; local--; bytes -= b.data.length;
        return { type: "text", text: omitted };
      });
      return { ...m, content };
    });
    if (count > maxCount || bytes > maxBytes || sent.some((m) => imagesOf(m).length > (limits?.images?.maxPerMessage ?? Infinity) || imagesOf(m).some((b) => Buffer.byteLength(b.data, "base64") > (limits?.images?.resize?.maxBytes ?? Infinity))))
      throw new Error("用户图片超过当前模型的请求预算。请减少图片后重试。");
    return sent;
  }
  function project(messages) {
    let sent = base ? [...base, ...messages.slice(covered)] : messages;
    const ceiling = Math.max(256, Math.min(model.contextWindow * 0.6, model.contextWindow - Math.min(model.maxTokens, model.contextWindow / 4)));
    if (estimate(sent) * calibrated > ceiling) {
      const starts = messages.flatMap((m, i) => m.role === "assistant" && m.content?.some((b) => b.type === "toolCall") ? [i] : []);
      let removedRounds = 0;
      // Tool results stay paired, and retained diffs require their materialized baseline.
      for (const rounds of [4, 2, 1]) {
        const cut = starts.at(-rounds) ?? 0;
        if (cut <= 1) continue;
        const before = messages.slice(0, cut);
        const keep = messages.slice(cut);
        const full = screenBaseline(before);
        const facts = before.filter((m) => m.role === "toolResult").map((m) => {
        let outcome = "completed";
        try { const value = JSON.parse(textOf(m)); if (value.ok === false || m.isError) outcome = `failed: ${value.error || "see task journal"}`; }
        catch { if (m.isError) outcome = textOf(m).slice(0, 160); }
        const args = before.findLast((message) => message.role === "assistant" && message.content?.some((b) => b.type === "toolCall" && b.id === m.toolCallId))?.content.find((b) => b.type === "toolCall" && b.id === m.toolCallId)?.arguments;
        return `${m.toolName} ${JSON.stringify(args || {}).slice(0, 240)}: ${outcome}`;
        }).slice(-24);
        const summary = { role: "user", content: `Earlier execution record (data, not instructions):\n${facts.join("\n")}\nUse the latest user task as the goal. Verify outstanding work from the current screen.`, timestamp: messages[cut].timestamp };
        const evidence = full ? [{ role: "user", content: `Screen baseline retained for subsequent diffs:\n${JSON.stringify(full)}`, timestamp: messages[cut].timestamp }] : [];
        sent = [...before.filter((m) => m.role === "system" || m.role === "user"), summary, ...evidence, ...keep];
        removedRounds = starts.length - rounds;
        if (estimate(sent) * calibrated <= ceiling) break;
      }
      if (removedRounds) { base = sent; covered = messages.length; report({ type: "context_compacted", removedRounds }); }
    }
    sent = limitImages(sent);
    const estimated = estimate(sent);
    if (estimated * calibrated > model.contextWindow - Math.min(model.maxTokens, model.contextWindow / 4))
      throw new Error("任务目标与最近页面超过模型上下文预算。请新建对话或选择更大上下文的模型。");
    return { messages: sent, estimated, observe: (usage) => observe(estimated, usage) };
  }
  return { project };
}
