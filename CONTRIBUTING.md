# Contributing to DialogCanvas

Thanks for helping improve DialogCanvas. Before opening a pull request, search existing issues and keep each change focused.

## Development environment

- JDK 25
- Gradle Wrapper 9.4.0
- Node.js 24 or a compatible current release
- pnpm 11.19.0 as declared by `frontend/package.json`

Install frontend dependencies from the repository root:

```powershell
Set-Location frontend
corepack enable
pnpm install --frozen-lockfile
Set-Location ..
```

Run the release-relevant checks:

```powershell
pnpm --dir frontend run typecheck
pnpm --dir frontend run test
./gradlew.bat clean build
```

The production JAR is generated in `plugin/build/libs/`. More detail is available in `DEVELOPER.md`.

## Pull requests

- Explain the user-facing problem and the chosen behavior.
- Add or update tests for behavior changes.
- Update the Chinese Wiki and `CHANGELOG.md` when a public workflow or compatibility claim changes.
- Do not commit `.references/`, local logs, Gradle/pnpm caches, `dist/`, passwords, tokens, server data, or signing keys.
- Do not claim support for an untested Paper or Java version.

By contributing, you agree that your contribution is provided under the project license selected by the repository owner. Do not submit third-party code or assets that you are not allowed to redistribute.
