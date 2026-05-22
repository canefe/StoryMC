# Auto-Deploy: story-sim into the existing Watchtower pipeline — Design Spec

**Date:** 2026-05-22
**Status:** Approved (not yet implemented)

## Goal

Bring `story-sim` (Rust/Bevy 0.17) under the **already-running** Watchtower
auto-deploy pipeline that handles every other dockerized Story service. The MC
plugin (`Story`) deploy path stays untouched.

## Background — current state (verified against the live config)

The server-side prod compose is checked in at
**`story-deploy/docker-compose.yml`**. Reading it shows the deployment system is
**already built and working** for everything except story-sim:

- **Watchtower is already deployed** (`story-watchtower` container) with:
  - `--scope story` + `WATCHTOWER_SCOPE=story` — opt-in by scope, not by
    `enable=true` label. Only containers carrying
    `com.centurylinklabs.watchtower.scope=story` are updated. Infra
    (mongo/redis/nats/qdrant) has no scope label → never touched.
  - `WATCHTOWER_POLL_INTERVAL=300` (5 min), `WATCHTOWER_CLEANUP=true`.
  - Creds mounted from `/root/.docker/config.json:ro` (private GHCR pull).
- **Every service already auto-deploys**, watching the **`:main`** branch tag:
  `orchestrator` (story-go), `story-bot-api`, and from the story-mcp image:
  `story-mcp`, `story-watcher`, `story-identity`, `story-narrative`, plus
  `story-chargen` and `story-recognition`. All carry the `scope=story` label.
- Each service repo has a `docker.yml` that builds & pushes to GHCR on push.

**So the only gap is story-sim**: it has no Dockerfile, no image CI, and is not
in the prod compose. Everything in any earlier "set up Watchtower" plan is
already done — do not redo it.

## Decisions (locked)

- **Reuse the existing pipeline as-is.** `--scope story`, 300s poll, GHCR creds
  — all already correct. No Watchtower changes.
- **Image tag: `:latest`** for story-sim (user choice). NOTE: every other
  service watches `:main`. Watchtower watches the tag of the *running
  container* per-container, so `:latest` works fine — the compose entry and the
  CI push tag just both have to say `:latest`. It is intentionally inconsistent
  with the rest of the stack.
- **Headless via `default-features = false`** (drop `bevy_render` entirely) —
  not `backends: None`, not lavapipe. No wgpu in the binary → no GPU adapter
  panic possible, ~90–110 MB image, faster builds.
- **Decouple render from sim via Cargo feature gating** (a `render`/`gui`
  feature, ON by default for local dev). The container builds
  `--no-default-features`. Local 3D/inspector workflow stays exactly as-is.

## The real work: render/sim decoupling (NOT the Dockerfile)

story-sim already runs headless at *runtime* (`OPEN_WINDOW=false`,
`ENABLE_EGUI_INSPECTOR=false` in `main.rs`), but it is render-coupled at
*compile time*. Going `default-features = false` will not compile until render
symbols are gated. Verified blast radius — render symbols
(`Mesh3d`/`StandardMaterial`/`Camera*`/`Gizmos`/`bevy_egui`) appear in:

| File | Coupling |
|---|---|
| `src/main.rs` | DefaultPlugins setup, cameras, lighting, egui inspector block |
| `src/plugins/lua_world_api.rs` | **Core sim path spawns mesh+material** for entities/zones (lines ~509, 572, 1835, 2222) |
| `src/plugins/stream_handlers/mod.rs` + `player.rs` | Player spawn adds `Mesh3d`/`StandardMaterial` |
| `src/systems/action_label_system.rs` | Camera screen-projection for labels |
| `src/systems/chat_bubble_system.rs` | Camera screen-projection for bubbles |
| `src/plugins/perception/debug_gizmos.rs` | Gizmo debug drawing |
| GUI cluster (dev-only) | `camera_controller.rs`, `custom_entity_inspector.rs`, `screen_log.rs`, `plan_inspector.rs` |

**Strategy — feature gating:**
- Add Cargo features:
  ```toml
  [features]
  default = ["dev", "render"]
  dev = ["bevy/dynamic_linking"]   # fast linking, dev only
  render = []                       # gates all visualization code
  ```
- Container build: `cargo build --release --no-default-features`.
- Gate every render/camera/gizmo/egui usage behind `#[cfg(feature = "render")]`
  (systems, plugin registrations, the mesh/material spawn blocks). Where a sim
  spawn currently also adds a mesh, the mesh portion becomes a gated
  add-on (gated system or `#[cfg]` block) so the entity is still created
  headless without visuals.
- The `dynamic_linking` move into `dev` is part of this (dev-speed only; must be
  off in the container regardless).

This refactor is the bulk of the effort. The Dockerfile/CI/compose pieces are
mechanical by comparison.

## Cargo headless feature set (Bevy 0.17)

When building `--no-default-features`, the binary needs an explicit minimal
Bevy feature set (BRP on port 15702, no render). `ScheduleRunnerPlugin` is added
automatically by `DefaultPlugins` when `bevy_window` is absent (Bevy ≥0.15), so
no manual runner plugin:

```toml
bevy = { version = "0.17", default-features = false, features = [
    "std", "async_executor", "multi_threaded", "bevy_log",
    "reflect_auto_register",
    "bevy_asset", "bevy_scene",   # required by bevy_remote serialisation
    "bevy_state",
    "bevy_remote",
] }
```
Explicitly excluded: `bevy_render`, `bevy_winit`, `bevy_window`,
`bevy_core_pipeline`, `bevy_pbr`/`bevy_sprite`/`bevy_ui`, `bevy_audio`,
`bevy_gilrs`, `bevy_gizmos`, `bevy_gltf`, `x11`/`wayland`, render-asset LUTs,
`dynamic_linking`.

(The `render`-feature path keeps `bevy`'s normal default features for local dev.
The two-config split is the reason for the feature gate.)

## Dockerfile (multi-stage, cargo-chef)

Runtime base: **`debian:bookworm-slim`** (glibc; mlua vendored lua54 compiles
Lua C in but still links libc/libm; Alpine/musl is extra work for no gain;
distroless kills debuggability).

```dockerfile
# syntax=docker/dockerfile:1.7
FROM lukemathwalker/cargo-chef:latest-rust-1-bookworm AS chef
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends \
        pkg-config libssl-dev \
    && rm -rf /var/lib/apt/lists/*
# (drop pkg-config/libssl-dev if async-nats & redis use rustls, not openssl)

FROM chef AS planner
COPY . .
RUN cargo chef prepare --recipe-path recipe.json

FROM chef AS builder
COPY --from=planner /app/recipe.json recipe.json
RUN --mount=type=cache,target=/usr/local/cargo/registry,sharing=locked \
    --mount=type=cache,target=/usr/local/cargo/git,sharing=locked \
    --mount=type=cache,target=/app/target,sharing=locked \
    cargo chef cook --release --no-default-features --recipe-path recipe.json
COPY . .
RUN --mount=type=cache,target=/usr/local/cargo/registry,sharing=locked \
    --mount=type=cache,target=/usr/local/cargo/git,sharing=locked \
    --mount=type=cache,target=/app/target,sharing=locked \
    cargo build --release --no-default-features --bin story-sim \
    && cp /app/target/release/story-sim /story-sim

FROM debian:bookworm-slim AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends \
        libgcc-s1 ca-certificates \
    && rm -rf /var/lib/apt/lists/*
# add libssl3 here too if using openssl feature
RUN useradd --no-create-home --shell /bin/false appuser
USER appuser
WORKDIR /app
COPY --from=builder /story-sim /app/story-sim
# Copy runtime assets it loads from disk (Lua packs / definitions):
COPY --from=builder /app/packs /app/packs
EXPOSE 15702
ENTRYPOINT ["/app/story-sim"]
```

Notes:
- The binary must be `cp`'d out of the `target` cache mount inside the same
  `RUN` (the mount is gone afterward), then `COPY --from=builder /story-sim`.
- `cargo chef cook` must use the **same** `--no-default-features` as the real
  build, or the dep layer won't match and won't cache.
- Confirm `packs/` (and any other runtime-loaded asset dir) is what story-sim
  reads at startup; copy whatever it actually needs.

## Release profile

```toml
[profile.release]
opt-level = 3
lto = "thin"
codegen-units = 1
strip = true
panic = "abort"
```

## CI — story-sim `docker.yml`

Mirror story-go's workflow with GHCR push + GHA layer cache (so cargo-chef's dep
layer survives across runs). Push tag **`:latest`** (per decision):

```yaml
name: Docker
on:
  push:
    branches: [main]
    tags: ["v*"]
jobs:
  build:
    runs-on: ubuntu-latest
    permissions: { contents: read, packages: write }
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - uses: docker/build-push-action@v6
        with:
          context: .
          push: true
          tags: ghcr.io/canefe/story-sim:latest
          cache-from: type=gha
          cache-to: type=gha,mode=max
```

## Prod compose entry (`story-deploy/docker-compose.yml`)

Add alongside the other services, on `story-network`, with the **scope label**
so Watchtower manages it. Watch `:latest`:

```yaml
  story-sim:
    image: ghcr.io/canefe/story-sim:latest
    container_name: story-sim
    restart: unless-stopped
    labels:
      - com.centurylinklabs.watchtower.scope=story
    environment:
      - REDIS_ADDR=redis:6379
      - NATS_URL=nats://nats:4222
      # match story-sim's expected env (subjects, etc.)
    depends_on:
      - redis
      - nats
    networks:
      - story-network
```
No port publish needed unless BRP (15702) must be reachable from the host.

## Rollout order

1. **Decouple + headless build (local).** Add features, gate render code,
   `cargo build --release --no-default-features` green locally. This is the
   real work and the gate for everything after.
2. **Dockerfile + local image boot test.** Build the image, run it pointed at
   local redis/nats, confirm it boots headless and serves BRP. Check final
   image size (~90–110 MB expected).
3. **CI.** Add `docker.yml`; confirm GHCR push of `:latest`.
4. **Compose.** Add the `story-sim` service with the scope label; deploy. On the
   next 300s Watchtower poll (or a manual `docker compose up -d story-sim`) it's
   live and auto-deploying like everything else.

## Safety properties

- Watchtower already scoped; adding one labeled service can't affect infra or MC.
- Local dev workflow unchanged (default features still build the GUI/3D app).
- Rollback: revert + `docker compose up -d --force-recreate story-sim`, or pin a
  prior version tag (CI also pushes on `v*`).

## Out of scope / explicitly NOT doing

- Any Watchtower setup — it already exists and is correct.
- Touching go/bot/mcp/chargen/recognition — already auto-deploying.
- The MC plugin deploy path.
- `backends: None` or lavapipe (render is dropped entirely instead).
- Build optimization beyond cargo-chef + GHA cache (sccache/fat-LTO deferred
  unless build time is a real problem).

## Open items to confirm during implementation

- Exact runtime-asset dirs story-sim reads at startup (for the `COPY` in
  Dockerfile) — `packs/` plus possibly Lua/definition paths.
- Whether async-nats/redis use openssl (keep `libssl3`/`libssl-dev`) or rustls
  (drop them).
- story-sim's expected env var names/subjects for the compose entry.
