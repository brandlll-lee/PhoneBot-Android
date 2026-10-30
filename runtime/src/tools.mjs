import { Type } from "@earendil-works/pi-ai";

const text = Type.String({ minLength: 1, maxLength: 4000 });
const coordinate = Type.Integer({ minimum: 0, maximum: 20000 });
const target = { element: Type.Optional(text), text: Type.Optional(text), x: Type.Optional(coordinate), y: Type.Optional(coordinate) };
const definitions = [
  ["screen_read", "读取页面", "Read visible accessibility elements with page-scoped IDs and bounds. Set ocr to supplement sparse text. Set image to attach a screenshot for a vision model.", { ocr: Type.Optional(Type.Boolean()), image: Type.Optional(Type.Boolean()) }],
  ["screenshot", "截取屏幕", "Capture the unlocked main display. Returns an image for vision models.", {}],
  ["app_list", "查看应用", "List launchable apps and package names.", {}],
  ["app_launch", "打开应用", "Launch a package from app_list. Returns the resulting screen.", { package: text }],
  ["tap", "点击", "Tap a current element ID, exact text, or screen coordinates. Returns a fresh screen. Prefer element IDs.", target],
  ["swipe", "滑动", "Swipe between screen coordinates. Returns a fresh screen.", { x: coordinate, y: coordinate, endX: coordinate, endY: coordinate, duration: Type.Optional(Type.Integer({ minimum: 100, maximum: 2000 })) }],
  ["input_text", "输入文字", "Replace text in an editable element or the focused input. Supports Chinese. Checks the read-back value and returns a fresh screen.", { element: Type.Optional(text), text }],
  ["back", "返回", "Perform Android Back and read the resulting screen.", {}],
  ["home", "回到主页", "Go to Android Home and read the resulting screen.", {}],
];

export function phoneTools(host, model, attachment = () => {}) {
  return definitions.map(([name, label, description, schema]) => ({
    name, label, description, parameters: Type.Object(schema, { additionalProperties: false }),
    async execute(id, params, signal) {
      const details = await host(name, params, signal, id);
      const { image, ...view } = details;
      const content = [{ type: "text", text: JSON.stringify(view) }];
      if (image) {
        if (!model.input.includes("image")) throw new Error("当前模型不支持图片。请选择视觉模型，或使用控件树和 OCR。");
        const { readFile } = await import("node:fs/promises");
        attachment(image);
        content.push({ type: "image", data: (await readFile(image)).toString("base64"), mimeType: "image/png" });
      }
      return { content, details: view };
    },
  }));
}
