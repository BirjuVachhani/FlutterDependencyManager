# Flutter Dependency Manager

An IntelliJ IDEA and Android Studio plugin that highlights outdated packages in `pubspec.yaml` and upgrades them with a quick fix.

## Installing

The plugin is not on JetBrains Marketplace. It is served from its own plugin repository on GitHub Releases, so once the repository is added, the IDE installs and updates it like any other plugin.

**Requirements:** an IntelliJ-based IDE on build 262 or newer: IntelliJ IDEA 2026.2+, or an Android Studio release built on the 2026.2 platform.

### From the plugin repository (recommended)

1. Open **Settings** (**IntelliJ IDEA → Settings…** / **Android Studio → Settings…** on macOS, **File → Settings…** elsewhere).
2. Go to **Plugins**, click the gear icon (⚙) and choose **Manage Plugin Repositories…**.
3. Click **+**, paste this URL and click **OK**:
   ```
   https://github.com/BirjuVachhani/FlutterDependencyManager/releases/latest/download/updatePlugins.xml
   ```
4. Open the **Marketplace** tab, search for **Flutter Dependency Manager** and click **Install**. Restart the IDE if asked.

New versions then arrive through the IDE's regular plugin update notifications.

### From a ZIP

1. Download `FlutterDependencyManager-<version>.zip` from the [Releases page](https://github.com/BirjuVachhani/FlutterDependencyManager/releases). Do not unzip it.
2. In **Settings → Plugins**, click ⚙ and choose **Install Plugin from Disk…**, then pick the ZIP.
3. Restart the IDE if asked.

A plugin installed this way only gets updates if the repository above is also added.

### For teams

To add the repository for everyone without clicking through the settings, put this in the IDE's `idea.properties` (**Help → Edit Custom Properties…**). Separate multiple repositories with `;`.

```
idea.plugin.hosts=https://github.com/BirjuVachhani/FlutterDependencyManager/releases/latest/download/updatePlugins.xml
```

### From source

`make install` builds the plugin and installs it into your local IntelliJ IDEA and Android Studio. Restart the IDE afterwards.
