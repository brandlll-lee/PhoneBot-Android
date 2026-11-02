import { readdir, readFile, writeFile, mkdir } from "node:fs/promises";
import { resolve, delimiter } from "node:path";
import { execFileSync } from "node:child_process";

const root = resolve(import.meta.dirname, "..");
const sources = resolve(root, "docs/assets/providers");
const work = resolve(root, ".tools/provider-icons");
const cache = resolve(process.env.GRADLE_USER_HOME ?? resolve(root, ".tools/gradle-home"), "caches/modules-2/files-2.1");
async function jars(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  return (await Promise.all(entries.map((entry) => entry.isDirectory() ? jars(resolve(directory, entry.name))
    : entry.name.endsWith(".jar") ? [resolve(directory, entry.name)] : []))).flat();
}
const classpath = (await Promise.all(["com.android.tools/sdk-common", "com.android.tools/common", "com.google.guava/guava",
  "com.google.guava/failureaccess", "org.jetbrains.kotlin/kotlin-stdlib", "org.jetbrains/annotations"].map((name) => jars(resolve(cache, name))))).flat();
await mkdir(work, { recursive: true });
for (const name of (await readdir(sources)).filter((name) => name.endsWith(".svg"))) {
  await writeFile(resolve(work, name), (await readFile(resolve(sources, name), "utf8")).replaceAll("currentColor", "#000000"));
}
const java = String.raw`import java.nio.file.*;
import java.io.*;
import com.android.ide.common.vectordrawable.Svg2Vector;
import com.android.ide.common.vectordrawable.VdPreview;
import javax.imageio.ImageIO;
class ProviderVectors {
  public static void main(String[] args) throws Exception {
    try (var paths = Files.list(Path.of(args[0]))) {
      for (var path : paths.filter(p -> p.toString().endsWith(".svg")).sorted().toList()) {
        var output = new ByteArrayOutputStream();
        var error = Svg2Vector.parseSvgToXml(path, output);
        if (!error.isBlank()) throw new IOException(path + ": " + error);
        var name = "ic_provider_" + path.getFileName().toString().replace("-", "_").replace(".svg", "");
        var xml = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        var vector = Path.of(args[1], "drawable", name + ".xml");
        var raster = Path.of(args[1], "drawable-xxxhdpi", name + ".png");
        var pathsData = java.util.regex.Pattern.compile("android:pathData=\"([^\"]+)\"").matcher(xml);
        boolean detailed = false;
        while (pathsData.find()) detailed |= pathsData.group(1).length() > 3000;
        if (detailed) {
          var errors = new StringBuilder();
          var image = VdPreview.getPreviewFromVectorXml(VdPreview.TargetSize.createFromMaxDimension(96), xml, errors);
          if (image == null || !errors.isEmpty()) throw new IOException(errors.toString());
          Files.createDirectories(raster.getParent());
          ImageIO.write(image, "png", raster.toFile());
          Files.deleteIfExists(vector);
        } else {
          Files.write(vector, output.toByteArray());
          Files.deleteIfExists(raster);
        }
      }
    }
  }
}`;
await writeFile(resolve(work, "ProviderVectors.java"), java);
const javaPath = process.env.JAVA_HOME ? resolve(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java") : "java";
execFileSync(javaPath, ["--class-path", classpath.join(delimiter), resolve(work, "ProviderVectors.java"), work,
  resolve(root, "app/src/main/res")], { stdio: "inherit" });
