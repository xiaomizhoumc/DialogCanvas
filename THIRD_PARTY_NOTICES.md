# Third-party notices

DialogCanvas packages third-party runtime components in its production JAR and compiles third-party frontend components into the embedded web editor. Those components remain subject to their own licenses; the DialogCanvas project license does not replace them.

The production build uses Shadow relocation to isolate selected packages. In particular, Javalin is relocated from `io.javalin` to `dialogcanvas.libs.javalin`, and Jetty is relocated from `org.eclipse.jetty` to `dialogcanvas.libs.jetty`. These namespace changes are made by the DialogCanvas build and are not upstream Javalin or Jetty releases. No endorsement by those projects is implied.

## Java runtime components

| Component family | Version used | Upstream license |
| --- | --- | --- |
| Javalin | 7.2.2 | Apache-2.0; bundled and namespace-relocated |
| Eclipse Jetty / Jetty WebSocket | 12.1.14 | EPL-2.0 or Apache-2.0 |
| Jakarta APIs pulled by Jetty | dependency-locked in Gradle | See each Jakarta artifact's license |
| Jackson | 3.2.3; annotations 2.22 | Apache-2.0 |
| SnakeYAML | 2.2 | Apache-2.0 |
| favre.lib bcrypt / bytes | 0.10.2 / 1.5.0 | Apache-2.0 |
| Kotlin standard library | 2.2.20 | Apache-2.0 |
| ASM | 9.10.1 | BSD-3-Clause |

Paper API and PlaceholderAPI are compile-time/server-provided dependencies and are not intentionally bundled into the production JAR.

## Minecraft compatibility data

The current JAR does not embed Minecraft item/block thumbnail PNGs. Registry identifiers, verified Sprite atlas mappings and Chinese game names are used for compatibility; the Minecraft client supplies game textures. The browser shows text placeholders. Historical release packages may still contain thumbnails and are not the current release. Minecraft names and game content are **not** licensed under DialogCanvas's MIT License. Rights in Minecraft names, game assets, and derivatives remain with Mojang and Microsoft.

DialogCanvas is not an official Minecraft product, is not approved by or associated with Mojang or Microsoft, and must be distributed and presented subject to the [Minecraft Usage Guidelines](https://www.minecraft.net/usage-guidelines).

## Embedded frontend

Direct production dependencies are declared and locked in `frontend/package.json` and `frontend/pnpm-lock.yaml`. They include React, TipTap/ProseMirror, dnd-kit, Lucide, react-colorful, Zustand, and Zundo. Their package metadata identifies MIT or ISC licenses; transitive packages remain subject to their individual package licenses.

The shaded JAR retains upstream `META-INF` license and notice files where supplied and includes this inventory as `META-INF/THIRD_PARTY_NOTICES.md`. Before every public release, review the resolved dependency tree and the JAR's `META-INF` entries, especially after dependency upgrades:

```powershell
./gradlew.bat :plugin:dependencies --configuration runtimeClasspath
jar tf plugin/build/libs/DialogCanvas-1.0.0-Paper-26.1-26.3.jar | Select-String 'META-INF/(LICENSE|NOTICE)'
```

This notice is an inventory aid, not legal advice or a substitute for the complete upstream license texts or platform/game usage terms.
