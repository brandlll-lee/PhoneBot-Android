import { ModelRuntime } from "@earendil-works/pi-coding-agent";
import { registerBunOAuthFlows } from "@earendil-works/pi-ai/bun-oauth";
import { setBedrockProviderModule } from "@earendil-works/pi-ai/api/bedrock-converse-stream.lazy";
import { bedrockProviderModule } from "@earendil-works/pi-ai/bedrock-provider";
import { mkdir, readFile, writeFile, rename, rm } from "node:fs/promises";
import { join } from "node:path";

// Pi supplies static loaders for bundles that include its Node-only implementations.
registerBunOAuthFlows();
setBedrockProviderModule(bedrockProviderModule);

export async function providerModels(host, directory) {
  const locks = new Map();
  const credentials = {
    read: (provider, options) => host("vault_read", { provider }, options?.signal),
    list: (options) => host("vault_list", {}, options?.signal),
    modify(provider, fn) {
      const pending = (locks.get(provider) ?? Promise.resolve()).catch(() => {}).then(async () => {
        const current = await credentials.read(provider);
        const next = await fn(current);
        if (next !== undefined) await host("vault_write", { provider, credential: next });
        return next ?? current;
      });
      locks.set(provider, pending);
      return pending;
    },
    delete(provider) {
      const pending = (locks.get(provider) ?? Promise.resolve()).catch(() => {}).then(() => host("vault_delete", { provider }));
      locks.set(provider, pending);
      return pending;
    },
  };
  const path = (provider) => {
    if (!/^[a-z0-9-]{1,80}$/.test(provider)) throw new Error("Invalid provider ID");
    return join(directory, `${provider}.json`);
  };
  const modelsStore = directory ? {
    async read(provider) {
      try { return JSON.parse(await readFile(path(provider), "utf8")); }
      catch (error) { if (error.code !== "ENOENT") throw error; }
    },
    async write(provider, entry) {
      await mkdir(directory, { recursive: true });
      const file = path(provider);
      await writeFile(`${file}.tmp`, JSON.stringify(entry));
      await rename(`${file}.tmp`, file);
    },
    async delete(provider) { await rm(path(provider), { force: true }); },
  } : undefined;
  return ModelRuntime.create({ credentials, modelsStore, modelsPath: null, refreshOnCreate: false });
}
