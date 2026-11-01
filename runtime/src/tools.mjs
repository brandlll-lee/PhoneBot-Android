import { Type } from "@earendil-works/pi-ai";

const text = Type.String({ minLength: 1, maxLength: 4000 });
const coordinate = Type.Integer({ minimum: 0, maximum: 20000 });
const target = { element: Type.Optional(text), text: Type.Optional(text), resourceId: Type.Optional(text), x: Type.Optional(coordinate), y: Type.Optional(coordinate) };
const condition = Type.Object({ element: Type.Optional(text), text: Type.Optional(text), resourceId: Type.Optional(text), package: Type.Optional(text),
  editable: Type.Optional(Type.Boolean()), enabled: Type.Optional(Type.Boolean()), selected: Type.Optional(Type.Boolean()), checked: Type.Optional(Type.Boolean()),
  withinResourceId: Type.Optional(text), absent: Type.Optional(Type.Boolean()) }, { additionalProperties: false, minProperties: 1 });
const settings = ["SETTINGS", "WIFI_SETTINGS", "BLUETOOTH_SETTINGS", "SOUND_SETTINGS", "DISPLAY_SETTINGS", "APPLICATION_DETAILS_SETTINGS", "APPLICATION_SETTINGS", "DATE_SETTINGS", "LOCALE_SETTINGS", "DEVICE_INFO_SETTINGS", "LOCATION_SOURCE_SETTINGS", "INTERNAL_STORAGE_SETTINGS", "ACCESSIBILITY_SETTINGS", "BATTERY_SAVER_SETTINGS"].map((s) => `android.settings.${s}`);
const intent = { action: text, data: Type.Optional(text), type: Type.Optional(text), package: Type.Optional(text),
  extras: Type.Optional(Type.Record(Type.String(), Type.Union([Type.String(), Type.Number(), Type.Boolean()]))) };
const actions = [
  ["app_launch", "打开应用", "Launch a known package.", { package: text }],
  ["app_send_intent", "打开系统入口", "Start an Android activity by action/data/type/package/extras. Opening an activity is not proof the requested setting changed. Prefer the matching settings action over launching the Settings package.", intent],
  ["phone_open_link", "打开页面", "Open a supported settings action or a URI. Application details uses uri package:the.package. An unresolved link needs UI navigation.", { action: Type.Optional(Type.Union(settings.map(Type.Literal))), uri: Type.Optional(text), package: Type.Optional(text) }],
  ["tap", "点击", "Tap a current element, exact text/resource ID, or screen coordinates.", target],
  ["swipe", "滑动", "Swipe by direction or screen coordinates.", { x: Type.Optional(coordinate), y: Type.Optional(coordinate), endX: Type.Optional(coordinate), endY: Type.Optional(coordinate),
    direction: Type.Optional(Type.Union(["up", "down", "left", "right"].map(Type.Literal))), duration: Type.Optional(Type.Integer({ minimum: 100, maximum: 2000 })) }],
  ["input_text", "输入文字", "Replace editable text and verify read-back. target selects the input's current text or description.", { element: Type.Optional(text), resourceId: Type.Optional(text), target: Type.Optional(text), text }],
  ["back", "返回", "Perform Android Back.", {}],
  ["home", "回到主页", "Go to Android Home.", {}],
].map(([name, label, description, schema]) => [name, label, `${description} ok/executed report dispatch, not task success. changed reports observed UI change. conditionBefore/conditionMet report the selector before/after the action. treeStable reports a quiet tree, not loaded content. stopReason explains why waiting or batch continuation ended. condition_unchanged means the selector already matched and no UI change was observed; review the screen. canContinue controls remaining batch steps. expect waits for a stable postcondition; select destination content or selected/checked state, not a navigation label already present. Use the direct outcome of this action, not a later task goal. Text defaults to non-editable content. Use editable:true for input checks and withinResourceId for a result region. Unplanned navigation or a dialog stops a batch for review.`,
  { ...schema, expect: Type.Optional(condition), timeoutMs: Type.Optional(Type.Integer({ minimum: 250, maximum: 15000 })) }]);
const batch = Type.Union(actions.map(([name, , , schema]) => Type.Object({ tool: Type.Literal(name),
  args: Type.Object(schema, { additionalProperties: false }) }, { additionalProperties: false })));
const definitions = [
  ["screen_read", "读取页面", "Read all visible elements in a full snapshot. Each element has id, parent, text, description, label, clickable, scrollable, editable, enabled, selected, checked and screen-pixel bounds. parent identifies its containing element; inspect children to interpret a container's label. scrollable marks regions to explore with swipe. ocr:true adds recognized text and positions; image:true adds a screenshot for vision models. Actions return these elements as a full snapshot or a diff with baseSnapshot, added, updated and removed.", { ocr: Type.Optional(Type.Boolean()), image: Type.Optional(Type.Boolean()) }],
  ["screenshot", "截取屏幕", "Capture the session's selected display. Returns an image for vision models.", {}],
  ["app_list", "查看应用", "List launchable apps and package names.", {}],
  ["device_status", "读取设备设置", "Read actual media volume/mute, brightness, Wi-Fi and system-command availability.", {}],
  ["volume_set", "设置媒体音量", "Set media volume to 0–100 percent and read the actual level and mute state back.", { percent: Type.Integer({ minimum: 0, maximum: 100 }) }],
  ["brightness_set", "设置亮度", "Set brightness 0–100 percent or adaptive brightness with auto:true. Requires modify-system-settings authorization. Returns the actual stored value and mode.", { percent: Type.Optional(Type.Integer({ minimum: 0, maximum: 100 })), auto: Type.Optional(Type.Boolean()) }],
  ["wifi_toggle", "设置无线网络", "Set Wi-Fi using an authorized system shell. Otherwise open the Wi-Fi panel. verified:false means user/UI action is still required. Verify actual state with device_status.", { on: Type.Boolean() }],
  ["phone_set_alarm", "设置闹钟", "Open the system clock with an alarm time. Read the resulting page and verify whether the alarm was saved.", { hour: Type.Integer({ minimum: 0, maximum: 23 }), minute: Type.Integer({ minimum: 0, maximum: 59 }), label: Type.Optional(text) }],
  ["phone_set_timer", "设置计时器", "Open the system clock with a duration. Verify the timer's actual running state.", { seconds: Type.Integer({ minimum: 1, maximum: 86400 }), label: Type.Optional(text) }],
  ["phone_add_calendar_event", "添加日程", "Open the calendar editor. Times are Unix epoch milliseconds. Read the page and verify saving; opening the editor alone is not completion.", { title: text, beginTime: Type.Integer({ minimum: 1 }), endTime: Type.Integer({ minimum: 1 }), location: Type.Optional(text), description: Type.Optional(text) }],
  ["phone_system_command", "执行系统命令", "Run a device-wide command through the session's authorized helper with its shell/root permissions. Timeout 10 seconds, output at most 32768 characters. Command success is not proof of a setting change; read back the target state. Use phone UI tools for tapping, input and navigation; they target the selected display. System volume, brightness and network are device-wide.", { command: text }],
  ...actions,
  ["do_steps", "连续操作", 'Execute 1–12 known actions using the same arguments, waits and checks as single actions. Current element IDs are allowed; use semantic selectors on later pages. Stop at unknown UI. Example: input_text {resourceId:"known input",text:"query"}, tap {text:"Search",expect:{resourceId:"known result",text:"query"}}. Do not batch payments, sending, deletion, login or confirmations.', { steps: Type.Array(batch, { minItems: 1, maxItems: 12 }) }],
];

export function phoneTools(host, model, attachment = () => {}, images) {
  const tools = definitions.map(([name, label, description, schema]) => ({
    name, label, description, parameters: Type.Object(schema, { additionalProperties: false }),
    async execute(id, params, signal) {
      const details = await host(name, params, signal, id);
      const { image, ...view } = details;
      const content = [{ type: "text", text: JSON.stringify(view) }];
      if (image) {
        if (!model.input.includes("image")) throw new Error("当前模型不支持图片。请选择视觉模型，或使用控件树和 OCR。");
        const { readFile } = await import("node:fs/promises");
        attachment(image);
        content.push({ type: "image", data: (await readFile(image)).toString("base64"), mimeType: details.imageInfo?.mimeType || "image/jpeg" });
      }
      return { content, details: view, isError: view.ok === false };
    },
  }));
  if (images) tools.push({ name: "image_recall", label: "回看图片", description: "Retrieve an archived image by its reference when the user refers to an earlier screenshot. It is historical evidence, not the current screen.",
    parameters: Type.Object({ reference: Type.String({ pattern: "^[a-f0-9]{64}$" }) }, { additionalProperties: false }),
    execute: async (_id, { reference }) => {
      if (!model.input.includes("image")) throw new Error("当前模型不支持图片。请选择视觉模型。");
      return { content: [await images.recall(reference)], details: { reference } };
    } });
  return tools;
}
