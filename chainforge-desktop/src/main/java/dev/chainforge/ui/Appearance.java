package dev.chainforge.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

final class Appearance {
    enum Theme {
        SYSTEM("System"), DARK("Dark"), LIGHT("Light");

        private final String label;

        Theme(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final Preferences preferences = Preferences.userNodeForPackage(Appearance.class);
    private final Consumer<Boolean> applyLightTheme;
    private final Timeline systemWatcher;
    private Theme theme;
    private boolean systemLight;
    private boolean readingSystemTheme;

    Appearance(Consumer<Boolean> applyLightTheme) {
        this.applyLightTheme = applyLightTheme;
        theme = loadTheme();
        systemWatcher = new Timeline(new KeyFrame(Duration.seconds(5), e -> updateSystemTheme()));
        systemWatcher.setCycleCount(Timeline.INDEFINITE);
    }

    Theme theme() {
        return theme;
    }

    boolean isLight() {
        return theme == Theme.LIGHT || (theme == Theme.SYSTEM && systemLight);
    }

    void apply() {
        applyLightTheme.accept(isLight());
        if (theme == Theme.SYSTEM) {
            updateSystemTheme();
            systemWatcher.play();
        } else {
            systemWatcher.stop();
        }
    }

    void select(Theme selected) {
        preferences.put("theme", selected.name());
        theme = selected;
        apply();
    }

    private Theme loadTheme() {
        try {
            return Theme.valueOf(preferences.get("theme", Theme.DARK.name()));
        } catch (IllegalArgumentException e) {
            return Theme.DARK;
        }
    }

    private void updateSystemTheme() {
        if (readingSystemTheme) return;
        readingSystemTheme = true;
        CompletableFuture.supplyAsync(Appearance::systemPrefersLight).whenComplete((light, error) ->
                Platform.runLater(() -> {
                    readingSystemTheme = false;
                    if (error == null) systemLight = light;
                    if (theme == Theme.SYSTEM) applyLightTheme.accept(isLight());
                }));
    }

    private static boolean systemPrefersLight() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return !"dark".equalsIgnoreCase(readPreference("/usr/bin/defaults", "read", "-g", "AppleInterfaceStyle"));
        }
        if (os.contains("win")) {
            String value = readPreference("reg.exe", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme");
            return !value.endsWith("0x0");
        }
        if (os.contains("linux")) {
            String scheme = readPreference("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
            if (scheme.contains("prefer-dark")) return false;
            if (scheme.contains("prefer-light")) return true;
            String gtkTheme = System.getenv("GTK_THEME");
            if (gtkTheme == null || gtkTheme.isBlank())
                gtkTheme = readPreference("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme");
            return !gtkTheme.isBlank() && !gtkTheme.toLowerCase(Locale.ROOT).contains("dark");
        }
        return false;
    }

    private static String readPreference(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0) {
                try (var output = process.getInputStream()) {
                    return new String(output.readAllBytes(), StandardCharsets.UTF_8).strip();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Some Linux desktops do not expose GNOME settings.
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
        return "";
    }
}
