# Changelog

All notable public changes to DialogCanvas are recorded here. Versions follow
[Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-10-16

First stable public release.

### Added

- Paper-native Dialog menus with notice, confirmation, and multi-action layouts.
- Embedded loopback-only visual web editor; no separate website or database is required.
- JSON storage plus YAML import/export workflows with revision conflict protection.
- Text, item, input, button, condition, command, economy, purchase-limit, and cooldown tools.
- Optional PlaceholderAPI and Vault-compatible economy integration.
- Chinese documentation, ten annotated examples generated safely on first startup, operational guidance, and compatibility evidence.
- Third-party attribution inventory embedded as `META-INF/THIRD_PARTY_NOTICES.md` in the release JAR.

### Compatibility

- Item, button-icon, and inventory-limit controls use registry-name inputs. The browser uses text placeholders; extracted client thumbnail PNGs are no longer bundled. Native in-game ItemStack/Sprite rendering remains available.

- Paper 26.1 through 26.3 series; specifically tested with 26.1.1 Build 29,
  26.1.2 Build 74, 26.2 Build 134, and 26.3 Build 194.
- Java 25 is required.
- Spigot, hybrid mod servers, and future Paper releases are not claimed as supported.

### Known limitations

- The editor listens only on `127.0.0.1` and is intended to be opened on the server computer.
- The browser preview is approximate; final rendering must be checked in a compatible client.
- A real economy provider is required in addition to a Vault-compatible bridge for transactions.
- Original-client visuals and real Vault/PlaceholderAPI transaction flows still require operator-side verification.
