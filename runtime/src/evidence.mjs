export function taskEvidence(taskId, host) {
  const calls = new Map();
  let finished;
  let finishAttempts = 0;
  return {
    get finished() { return finished; },
    finishTurn(turn) {
      if (finished) return { action: "end" };
      if (turn.message.content.some((b) => b.type === "toolCall") || ["error", "aborted"].includes(turn.message.stopReason)) return;
      if (++finishAttempts > 2) throw new Error("任务结果未通过证据核对。请查看执行步骤。");
      turn.context.messages.push({ role: "user", content: "请调用 task_finish 收尾。仅使用本次任务的工具调用 ID。completed 需要当前条件核验或最后一次工具取得的视觉截图；目标未满足时用 partial 或 blocked。如仅为聊天且未操作设备，用 answered。", timestamp: Date.now() });
      return { action: "continue" };
    },
    record(id, name, params, result) {
      if (name !== "task_finish") calls.set(id, { name, params, result });
    },
    async finish({ outcome, summary, evidence = [] }, signal) {
      if (outcome === "answered" && calls.size) throw new Error("已调用设备工具。请用本次任务证据说明完成、部分完成或受阻。");
      if (outcome === "completed" && evidence.length === 0) throw new Error("完成任务需要本次任务的工具证据。");
      const verified = [];
      for (const item of evidence) {
        const call = calls.get(item.callId);
        if (!call || call.result.ok === false) throw new Error("证据必须来自本次任务中成功的工具调用。");
        const screen = call.result.screen ?? call.result;
        if (screen.snapshot != null || item.condition) {
          if (item.condition) {
            const current = await host("screen_check", { condition: item.condition }, signal);
            if (!current.ok || current.taskId !== taskId) throw new Error("当前页面不满足完成条件。请继续操作，或如实报告受阻。");
            verified.push({ ...item, taskId, snapshot: current.snapshot, observedAt: current.observedAt, package: current.package });
          } else {
            if (!call.result.image || call.result.taskId !== taskId || [...calls.values()].at(-1) !== call) throw new Error("屏幕完成声明需要当前页面条件，或本次任务最后一次工具调用取得的视觉截图。");
            verified.push({ ...item, taskId, snapshot: screen.snapshot, observedAt: screen.observedAt, image: call.result.image });
          }
        } else {
          if (call.name !== "device_status" && call.result.verified !== true) throw new Error("调用已执行不代表目标已生效。请读取并验证结果。");
          const field = { volume_set: "volume", brightness_set: "brightness", wifi_toggle: "wifiEnabled" }[call.name];
          let result = call.result;
          if (field) {
            const current = await host("device_status", {}, signal);
            const actual = current[field];
            const matches = field === "wifiEnabled" ? actual === result.wifiEnabled :
              actual?.value === result.value && (field === "volume" ? actual.muted === result.muted : actual.automatic === result.automatic);
            if (!current.ok || !matches) throw new Error("当前系统状态与设置结果不一致。请重新核对。");
            result = current;
          }
          verified.push({ ...item, taskId, observedAt: result.observedAt, result });
        }
      }
      finished = { outcome, summary, evidence: verified };
      return { ok: true, ...finished };
    },
  };
}
