import { Type } from "@earendil-works/pi-ai";

const text = Type.String({ minLength: 1, maxLength: 4000 });
const coordinate = Type.Integer({ minimum: 0, maximum: 20000 });
const target = { element: Type.Optional(text), text: Type.Optional(text), resourceId: Type.Optional(text), x: Type.Optional(coordinate), y: Type.Optional(coordinate) };
const condition = Type.Object({ element: Type.Optional(text), text: Type.Optional(text), resourceId: Type.Optional(text), package: Type.Optional(text),
  editable: Type.Optional(Type.Boolean()), enabled: Type.Optional(Type.Boolean()), withinResourceId: Type.Optional(text), absent: Type.Optional(Type.Boolean()) }, { additionalProperties: false, minProperties: 1 });
const actions = [
  ["app_launch", "打开应用", "Launch a known package.", { package: text }],
  ["tap", "点击", "Tap a current element, exact text/resource ID, or screen coordinates.", target],
  ["swipe", "滑动", "Swipe by direction or screen coordinates.", { x: Type.Optional(coordinate), y: Type.Optional(coordinate), endX: Type.Optional(coordinate), endY: Type.Optional(coordinate),
    direction: Type.Optional(Type.Union(["up", "down", "left", "right"].map(Type.Literal))), duration: Type.Optional(Type.Integer({ minimum: 100, maximum: 2000 })) }],
  ["input_text", "输入文字", "Replace editable text and verify read-back. target selects the input's current text or description.", { element: Type.Optional(text), resourceId: Type.Optional(text), target: Type.Optional(text), text }],
  ["back", "返回", "Perform Android Back.", {}],
  ["home", "回到主页", "Go to Android Home.", {}],
].map(([name, label, description, schema]) => [name, label, `${description} Returns screen changes, treeStable, conditionMet and stopReason. expect waits for a postcondition; text defaults to non-editable content. Use editable:true for input checks and withinResourceId for a result region. Without expect, tree stability does not mean business readiness.`,
  { ...schema, expect: Type.Optional(condition), timeoutMs: Type.Optional(Type.Integer({ minimum: 250, maximum: 15000 })) }]);
const batch = Type.Union(actions.map(([name, , , schema]) => Type.Object({ tool: Type.Literal(name),
  args: Type.Object(schema, { additionalProperties: false }) }, { additionalProperties: false })));
const definitions = [
  ["screen_read", "读取页面", "Read full visible elements with stable IDs and screen-pixel bounds. Actions already return screen changes. Read again only for missing or ambiguous evidence. Prefer the tree, then OCR for sparse text, then image for visual content.", { ocr: Type.Optional(Type.Boolean()), image: Type.Optional(Type.Boolean()) }],
  ["screenshot", "截取屏幕", "Capture the unlocked main display. Returns an image for vision models.", {}],
  ["app_list", "查看应用", "List launchable apps and package names.", {}],
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
