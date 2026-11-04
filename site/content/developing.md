# Developing

## Build and test

These commands match CI (`.github/workflows/ci.yml`), which runs on pull requests and on pushes to `main`:

```sh
./gradlew :composeApp:testDebugUnitTest :composeApp:assembleDebug
```

Use Java 21 and the Android SDK with platform 36, as in CI. iOS builds open `iosApp/iosApp.xcodeproj` in Xcode.

## Pull requests

Every pull request needs:

1. A new version section in `CHANGELOG.md`, with bullet points and a note that Android and iOS versioning was updated.
2. A version bump in these places:
   - `gradle.properties`: `app.version`
   - `composeApp/build.gradle.kts`: `versionCode`, incremented by 1
   - `iosApp/iosApp.xcodeproj/project.pbxproj`: `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION`, in both Debug and Release

Versions follow Semantic Versioning. See `AGENTS.md`.

## Updating these pages

The site is generated into `docs/` by `scripts/build_docs.py`. It needs Python 3 and [pandoc](https://pandoc.org/) on the `PATH`.

```sh
python3 scripts/build_docs.py
```

The script rebuilds `docs/` from scratch, copies the visual guides and design guides, renders Markdown, and fails if any local link is broken. Commit the regenerated `docs/` together with the source change.

Sources:

| Content | Source |
|---------|--------|
| App documentation pages | `site/content/*.md` |
| Stylesheet | `site/assets/style.css` |
| Feature pages | `features/<folder>/` |
| Design guides | `design/` |

### Adding a feature page

1. Create a folder under `features/`, for example `features/<feature-name>/`.
2. Add the design document as Markdown. Start it with a top-level heading (`# Title`), because the heading becomes the page title.
3. Optionally add visual guides as standalone `.html` files. Each needs a `<title>` element, which becomes the link text.
4. Run `python3 scripts/build_docs.py`. The folder appears on the [Features](features/index.html) page automatically, with one page per document.

Every folder under `features/` must contain at least one `.md` or `.html` file. The build fails otherwise.

### Adding an app documentation page

Add `site/content/<name>.md`, then add an entry to the `NAV` list in `scripts/build_docs.py`.

## GitHub Pages

The site is served by GitHub Pages from the `docs/` folder on `main`. Build it with the script above and commit `docs/`. Pages must be enabled once in the repository settings (Settings, Pages, Deploy from a branch, `main`, `/docs`).
