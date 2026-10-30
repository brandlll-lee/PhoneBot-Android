# PhoneBot

Use short sentences and active voice. Keep each change within the requested scope.

- Build or dependency changes: read [README.md](README.md).
- Engine, IPC, native code, or Pi changes: read [docs/runtime.md](docs/runtime.md).
- Validation or delivery: read [docs/verification.md](docs/verification.md). Run the checks that cover the change.
- Keep SDK files, downloads, caches, and virtual devices under `.tools/` on the project drive.
- Use `apply_patch` for source edits. Keep generated assets reproducible through `scripts/prepare-runtime.mjs`.
