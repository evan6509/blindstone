# Blindstone

A server-side Fabric **private alpha** that conceals undiscovered Overworld blocks, air pockets, light sources, block entities, and non-player entities separately for each viewer. Ordinary clients can join without installing Blindstone. The server keeps its real world; concealed underground data is replaced only in outgoing views.

Normal startup enables filtering after required hooks and configuration validate. `-Dblindstone.development=true` is reserved for isolated automated tests and deliberately denies player admission. Ordinary offline admission and lifecycle now have a real handshake/configuration regression against the installed production JAR. Graphical gameplay, authenticated/proxy admission, adversarial-client acceptance, and representative SMP capacity remain unverified. See [test evidence](docs/TEST_REPORT.md).

## Install

1. Use a dedicated **Minecraft 26.3** server with **Java 25**, **Fabric Loader 0.19.5**, and **Fabric API 0.163.0+26.3**.
2. Download the installable JAR from [GitHub Releases](https://github.com/evan6509/blindstone/releases) when available. Put `blindstone-<version>+mc26.3.jar` and the pinned Fabric API JAR in the server's `mods` directory.
3. Start the server normally, without the development JVM flag. The first start creates `config/blindstone.json`.
4. Check `/blindstone status` and `/blindstone verify`. Operators do not receive a viewing bypass.

Use a separate test server or a copy of your world for the alpha's gameplay acceptance checks. Install it server-side only. The exact version pins are intentional; other game versions and optimization stacks require separate compatibility tests.

## Current behavior

- The default protected range is world minimum through **Y<64**, using stone at Y≥0 and deepslate below zero. Concealment includes air, fluids, ores, storage and other underground construction.
- Below the cutoff, surface viewers receive replacement terrain even in sky-open shafts, water, foliage and glass columns. Terrain at Y≥64 stays real. Low valleys and cave entrances below the cutoff intentionally appear filled until approached underground.
- Protected sight starts only when the accepted eye position is below the cutoff. Shape-aware sight runs in all directions from the accepted server position, including constrained third-person offsets. Authorization is per block, not per cave, section, or chunk.
- A small body and accepted-movement envelope reveals nearby cells immediately. This explicit safety exception can disclose cells just across a wall; it stays fixed under load.
- Full chunk data, later block/entity updates, bundles and direct vanilla send paths are filtered. Section palettes, counts, block entities and heightmaps describe the filtered representation.
- Filtered lighting is reconstructed incrementally and published with matching chunk views. Visible torches illuminate revealed air; concealed sources cannot supply protected light. Safe upper-world light is preserved using a documented attenuation bound.
- Non-player tracking and packet field adapters withhold concealed entities and references. Player entities and ordinary audible activity remain available.
- Work, queues, masks, light jobs and light caches are bounded. Unsafe or unsupported delivery disconnects the affected viewer instead of reverting to real hidden data.
- Unchanged accepted body/vehicle paths reuse bounded safety results. Surface eyes skip protected sight entirely; stationary completed sight jobs remain cached. Fully concealed sections use a uniform palette without scanning all 4096 blocks.

[Configuration and commands](docs/CONFIGURATION.md), [packet coverage](docs/PACKET_COVERAGE.md), and [implementation details](docs/IMPLEMENTATION.md) describe the current policies and limits. The [specification](docs/Blindstone-Spec.md) defines the current anti-base policy; acceptance limits remain explicit. See [measured performance](docs/PERFORMANCE.md).

## Build and test

| Component | Pin |
| --- | --- |
| Minecraft | 26.3, unobfuscated names |
| Java | 25 |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.163.0+26.3 |
| Fabric Loom | 1.17.14 |
| Gradle | 9.6.1, wrapper distribution SHA-256 verified |

With a Java 25 JDK on `JAVA_HOME`/`PATH`:

```sh
./gradlew --no-daemon --max-workers=2 clean build runGametest
python3 -m unittest discover -s tests -v
python3 scripts/verify_jar.py
```

The installable artifact is `build/libs/blindstone-0.1.0-alpha.1+mc26.3.jar`. GameTests use `build/gametest`, keep external player admission closed, and write `build/gametest-results.xml`.

The admission harness installs only the production JAR and pinned Fabric API into a fresh `build/admission/<uuid>` world. It binds an ephemeral loopback listener, completes offline login/configuration with two independent headless clients, decodes concealed chunks, exercises lifecycle/rejoin and observes normal keepalives for 20 seconds. It stops its own server and writes `result.json` and `server.log`. It requires no client mod or development flag. The separate `--development-denial` negative control verifies the isolation flag still rejects external logins. These ordinary-server checks require separate explicit Minecraft EULA authorization. They are disabled on automatic CI pushes; only a manual workflow with `admission_eula=true` enables them. They were not rerun for this redesign.

For a separate normal-mode development server, prepare `build/smoke/eula.txt` and server properties, then run:

```sh
./gradlew --no-daemon runSmoke --args nogui
```

This run uses `build/smoke` and **does not** set the development flag. Type `stop` in its console to shut down. The smoke run is separate from automated gameplay fixtures.

To reproduce the lexical packet inventory:

```sh
./gradlew --no-daemon genSources
python3 scripts/inventory_packets.py
```

## Limits

Previously downloaded terrain cannot be erased from a modified client. Player ESP, normal sounds, surface clues, public terrain/biomes, and information shared by other players remain discovery routes. Blindstone provides no seed secrecy, world-generation changes, claims, or anti-cheat.

The Overworld is the only protected dimension. Sampled sight can cause delayed reveals around small openings; supported movement remains bounded. Post-effect identifiers, resource-pack controls and audited server UI metadata have explicit policies. Protected-PLAY cookies, show-dialog schemas and arbitrary mod payloads remain unsupported. Non-transport PLAY packets sent off-thread disconnect even outside the Overworld. Direct Netty writes and replacement send paths need explicit compatibility work. See the [integration audit](docs/COMPATIBILITY.md). Automated admission does not prove graphical playability, profiler resistance, or SMP performance.

## Build and release automation

Development commits belong on `codex/development`; `main` requires explicit integration approval. One workflow validates development pushes, pull requests to `main` or `codex/development`, and manual runs. It builds the production JAR, runs release-helper tests and all GameTests, verifies packaging, and records measured in-process workloads. Ordinary admission checks require the manual EULA input. Verified downloads and test evidence are retained as Actions artifacts. These validation events never publish releases.

Only a successful **push to `main`**, including a merged PR, automatically publishes a GitHub Release. The workflow checks out the exact triggering SHA and holds a single release queue lock through version selection, build, verification, and publication. Pending releases are retained using GitHub's `queue: max` (up to 100 waiting runs).

The first release uses `mod_version` from `gradle.properties`, currently `0.1.0-alpha.1`. Subsequent releases advance the highest reserved version: `0.1.0-alpha.2`, `0.1.0-alpha.3`, and so on. Tags and all releases, including drafts and prereleases across every API page, reserve numbers. The supported scheme is `MAJOR.MINOR.PATCH`, optionally followed by `-alpha.N`, `-beta.N`, or `-rc.N`. Prerelease counters advance within their channel; stable releases advance the patch. Raising the checked-in baseline above all history starts that new version/channel. Old source versions never reset history.

Each release includes exactly:

- `blindstone-<version>+mc26.3.jar`: the platform-independent server mod for Java 25, with matching Fabric/manifest versions and the triggering commit in its manifest.
- `CHANGELOG.md`: changes since the previous lower-version published ancestor (or full history for the first release), commit/comparison links, installation requirements, and exact source SHA.
- `SHA256SUMS`: SHA-256 checksums for the JAR and changelog.

Fabric Loader and Fabric API remain separate installation requirements. Source/intermediate JARs, test code, server worlds, and credentials are excluded. Alpha/beta/rc releases are marked **Prerelease** and never marked Latest. A stable release can become Latest only when its SHA is still the head of `main` and no higher version is already published. Use the releases listing to find alpha downloads; GitHub's Latest link excludes prereleases.

Publication creates a tag once at the triggering SHA and stages a **draft** release. It uploads the verified assets, downloads them again, checks byte equality, checksums, JAR contents, embedded versions/SHA and notes, and only then publishes. A failed verification leaves the draft unpublished. Retry the failed Actions run to resume a draft or orphan tag for that exact commit. An already published release is downloaded and verified without replacing its assets; tags are never moved. API/authentication failures stop the run clearly.

No custom signing keys or repository secrets are needed. Enable GitHub Actions and allow the workflow's `GITHUB_TOKEN` to receive `contents: write` for version selection (draft visibility) and publication. All other jobs default to `contents: read`; PR validation uses no release secrets. Repository rules must permit that token to create `v*` tags and GitHub Releases. Private-repository downloads require repository access. Redistribution rights remain reserved pending a license decision.

To test a selected version locally without publishing, set `BLINDSTONE_VERSION` and `GITHUB_SHA` for **all** build/verification commands, for example:

```sh
export BLINDSTONE_VERSION=0.1.0-alpha.2
export GITHUB_SHA="$(git rev-parse HEAD)"
export GITHUB_REPOSITORY=evan6509/blindstone
./gradlew --no-daemon clean build runGametest prepareAdmission
python3 scripts/verify_jar.py
python3 scripts/test_admission.py
python3 scripts/test_admission.py --development-denial
python3 scripts/release_assets.py package
python3 scripts/release_assets.py verify build/release
```

`BLINDSTONE_VERSION` overrides only the build version; it does not edit source metadata. Packaging requires an empty output directory (`clean` removes it). Running validation locally does not create remote tags, drafts, or releases.

See [release automation validation](docs/RELEASE_VALIDATION.md) for local evidence, event gates, and the distinction from an actual GitHub Actions release.
