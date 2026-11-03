# Provider marks

SVG source: [models.dev](https://github.com/anomalyco/models.dev/tree/dfe565a4814968b7fb4832c4938cfa7d6f051533/providers), revision `dfe565a4814968b7fb4832c4938cfa7d6f051533`.

The included MIT license applies to the source collection. Brand marks remain the property of their owners.

`scripts/prepare-runtime.mjs` calls `prepare-vector-icons.mjs`. It uses the Android SDK's `Svg2Vector` converter from the existing Gradle cache. It converts `currentColor` to black. Paths longer than 3,000 characters use the SDK's `VdPreview` to produce a 96 px bitmap at xxxhdpi. Compose applies the active theme tint. Generated resources use the `ic_provider_` prefix.

`ProviderIcon.kt` maps Pi provider IDs to these marks. Providers with no bundled mark use the shared fallback.
