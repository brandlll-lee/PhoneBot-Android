import { test } from "node:test";
import assert from "node:assert/strict";
import { taskEvidence } from "../src/evidence.mjs";

test("completion checks a current screen and rejects historical call IDs and a mismatched app", async () => {
  let screen = { taskId: "current", snapshot: 2, package: "dev.phonebot.android", ok: false };
  const evidence = taskEvidence("current", async () => screen);
  evidence.record("shot", "screenshot", {}, { taskId: "current", snapshot: 1 });
  const finish = { outcome: "completed", summary: "相册已打开", evidence: [{ callId: "shot", claim: "相册在前台", condition: { package: "gallery" } }] };
  await assert.rejects(evidence.finish({ ...finish, evidence: [{ ...finish.evidence[0], callId: "previous-task" }] }), /本次任务/);
  await assert.rejects(evidence.finish(finish), /当前页面/);
  assert.equal(evidence.finished, undefined);
  screen = { taskId: "current", snapshot: 3, package: "gallery", ok: true };
  assert.equal((await evidence.finish(finish)).outcome, "completed");
  assert.equal(evidence.finished.evidence[0].snapshot, 3);
});

test("opening a settings editor and a successful command need result evidence", async () => {
  const evidence = taskEvidence("task", async () => assert.fail("unexpected screen check"));
  for (const [id, result] of [["editor", { ok: true, verified: false }], ["command", { ok: true, exitCode: 0 }]]) {
    evidence.record(id, "system", {}, result);
    await assert.rejects(evidence.finish({ outcome: "completed", summary: "已设置", evidence: [{ callId: id, claim: "生效" }] }), /验证/);
  }
  await assert.rejects(evidence.finish({ outcome: "answered", summary: "已完成" }), /设备工具/);
  assert.equal((await evidence.finish({ outcome: "blocked", summary: "需要用户处理" })).outcome, "blocked");
});

test("native read-back evidence can complete a task", async () => {
  let value = 3;
  const evidence = taskEvidence("task", async (name) => {
    assert.equal(name, "device_status");
    return { ok: true, volume: { value, max: 15, muted: false }, observedAt: 42 };
  });
  evidence.record("volume", "volume_set", { percent: 50 }, { ok: true, verified: true, value: 7, max: 15, muted: false });
  await assert.rejects(evidence.finish({ outcome: "completed", summary: "音量已设置", evidence: [{ callId: "volume", claim: "实际音量已回读" }] }), /当前系统状态/);
  value = 7;
  await evidence.finish({ outcome: "completed", summary: "音量已设置", evidence: [{ callId: "volume", claim: "实际音量已回读" }] });
  assert.equal(evidence.finished.evidence[0].result.volume.value, 7);
  assert.equal(evidence.finished.evidence[0].observedAt, 42);
});

test("visual completion accepts only the last current-task image", async () => {
  const evidence = taskEvidence("current", async () => assert.fail("unexpected tree check"));
  const finish = { outcome: "completed", summary: "画面已确认", evidence: [{ callId: "image", claim: "视觉模型已检查图片中的目标" }] };
  evidence.record("image", "screenshot", {}, { taskId: "old", snapshot: 1, image: "frame.jpg" });
  await assert.rejects(evidence.finish(finish), /本次任务/);
  evidence.record("image", "screenshot", {}, { taskId: "current", snapshot: 2, image: "frame.jpg", observedAt: 42 });
  evidence.record("action", "home", {}, { ok: true });
  await assert.rejects(evidence.finish(finish), /最后一次/);
  evidence.record("fresh", "screenshot", {}, { taskId: "current", snapshot: 3, image: "fresh.jpg", observedAt: 43 });
  await evidence.finish({ ...finish, evidence: [{ ...finish.evidence[0], callId: "fresh" }] });
  assert.equal(evidence.finished.evidence[0].snapshot, 3);
});
