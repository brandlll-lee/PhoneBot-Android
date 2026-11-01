import { readFile, writeFile, mkdir } from "node:fs/promises";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const svg = await readFile(resolve(root, "docs/assets/phonebot-black.svg"), "utf8");
const rects = [...svg.matchAll(/<rect\s+([^>]+)\/>/g)].map(([, attributes]) => {
  const a = Object.fromEntries([...attributes.matchAll(/([\w-]+)="([^"]+)"/g)].map(([, key, value]) => [key, value]));
  return { id: a.id, ...Object.fromEntries(["x", "y", "width", "height", "rx"].map((key) => [key, Number(a[key])])) };
});
if (rects.map((r) => r.id).join() !== "body,screen,speaker,eye-left,eye-right,gesture" ||
    rects.some((r) => [r.x, r.y, r.width, r.height, r.rx].some((n) => !Number.isFinite(n)))) throw new Error("Invalid PhoneBot SVG geometry");
const [body, screen, speaker, left, right, gesture] = rects;
function path({ x, y, width: w, height: h, rx: r }) {
  return `M${x+r},${y}H${x+w-r}a${r},${r} 0 0 1 ${r},${r}V${y+h-r}a${r},${r} 0 0 1 -${r},${r}H${x+r}a${r},${r} 0 0 1 -${r},-${r}V${y+r}a${r},${r} 0 0 1 ${r},-${r}Z`;
}
async function save(file, content) {
  const target = resolve(root, file);
  await mkdir(resolve(target, ".."), { recursive: true });
  await writeFile(target, content);
}
const mark = rects.map(path).join("");
await save("docs/assets/phonebot-white.svg", svg.replace(/#000000|#FFFFFF/g, (color) => color === "#000000" ? "#FFFFFF" : "#000000"));
const animated = svg.replace(/fill="#000000"/g, 'fill="var(--ink)"').replace(/fill="#FFFFFF"/g, 'fill="var(--paper)"')
  .replace(/(<rect id="eye-(left|right)"[^>]+\/>)/g, '<g class="gaze">$1</g>')
  .replace("  <rect", `  <style>
    svg { --ink: #000; --paper: #fff; }
    #phone { transform-origin: 128px 128px; animation: breathe 7.2s ease-in-out infinite; }
    .gaze { animation: gaze 10.8s ease-in-out infinite; }
    #eye-left { transform-origin: 102px 124px; animation: blink 4.44s ease-in-out infinite; }
    #eye-right { transform-origin: 154px 124px; animation: blink 4.44s ease-in-out infinite; }
    @keyframes breathe { 0%,100% { transform: scale(1); } 50% { transform: scale(1.012); } }
    @keyframes gaze { 0%,100% { transform: translateX(0); } 25% { transform: translateX(4px); } 75% { transform: translateX(-4px); } }
    @keyframes blink { 0%,94%,100% { transform: scaleY(1); } 97% { transform: scaleY(.08); } }
    @media (prefers-color-scheme: dark) { svg { --ink: #fff; --paper: #000; } }
    @media (prefers-reduced-motion: reduce) { #phone,.gaze,#eye-left,#eye-right { animation: none; } }
  </style>
  <g id="phone">
  <rect`).replace("</svg>", "  </g>\n</svg>");
await save("docs/assets/phonebot-animated.svg", animated);
await save("app/src/main/res/drawable/ic_phonebot_mark.xml", `<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="256" android:viewportHeight="256">
    <path android:fillColor="#FFFFFF" android:fillType="evenOdd" android:pathData="${mark}" />
</vector>
`);
await save("app/src/main/res/drawable/ic_phonebot_foreground.xml", `<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <group android:scaleX="0.3" android:scaleY="0.3" android:translateX="15.6" android:translateY="15.6">
        <path android:fillColor="#000000" android:fillType="evenOdd" android:pathData="${mark}" />
    </group>
</vector>
`);
for (const themed of [false, true]) await save(`app/src/main/res/mipmap-anydpi${themed ? "-v33" : ""}/ic_launcher.xml`, `<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@android:color/white" />
    <foreground android:drawable="@drawable/ic_phonebot_foreground" />
${themed ? '    <monochrome android:drawable="@drawable/ic_phonebot_foreground" />\n' : ""}</adaptive-icon>
`);
const round = (r) => `RoundRect(${r.x}f, ${r.y}f, ${r.x+r.width}f, ${r.y+r.height}f, CornerRadius(${r.rx}f))`;
const draw = (r, eye = false) => `    drawRoundRect(ink, Offset(${r.x}f${eye ? " + gaze" : ""}, ${r.y}f${eye ? ` + ${r.height/2}f * (1 - blink)` : ""}), Size(${r.width}f, ${r.height}f${eye ? " * blink" : ""}), CornerRadius(${r.rx}f))`;
await save("app/src/main/java/dev/phonebot/android/PhoneBotMark.kt", `package dev.phonebot.android

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope

private val PhoneFrame = Path().apply {
    fillType = PathFillType.EvenOdd
    addRoundRect(${round(body)})
    addRoundRect(${round(screen)})
}

internal fun DrawScope.drawPhoneBot(ink: Color, blink: Float, gaze: Float, wink: Float) {
    drawPath(PhoneFrame, ink)
${draw(speaker)}
${draw(gesture)}
${draw(right, true)}
    val leftBlink = blink * (1 - .92f * wink)
    drawRoundRect(ink, Offset(${left.x}f + gaze, ${left.y}f + ${left.height/2}f * (1 - leftBlink)), Size(${left.width}f, ${left.height}f * leftBlink), CornerRadius(${left.rx}f))
}
`);
