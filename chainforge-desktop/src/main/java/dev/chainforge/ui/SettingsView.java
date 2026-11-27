package dev.chainforge.ui;

import java.util.List;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import static dev.chainforge.ui.ViewSupport.fieldCard;
import static dev.chainforge.ui.ViewSupport.muted;
import static dev.chainforge.ui.ViewSupport.pageShell;
import static dev.chainforge.ui.ViewSupport.panel;
import static dev.chainforge.ui.ViewSupport.primary;
import static dev.chainforge.ui.ViewSupport.section;

final class SettingsView {
    private final MainView app;

    SettingsView(MainView app) {
        this.app = app;
    }

    VBox build() {
        VBox v = pageShell("Settings", "Local application settings and security controls.");
        VBox themePanel = panel();
        ComboBox<Appearance.Theme> theme = new ComboBox<>();
        theme.getItems().setAll(Appearance.Theme.values());
        theme.setValue(app.appearance().theme());
        theme.setOnAction(e -> app.appearance().select(theme.getValue()));
        theme.setPrefWidth(180);
        VBox themeField = fieldCard("Theme", theme);
        theme.setMaxWidth(180);
        themePanel.getChildren().addAll(section("APPEARANCE"), themeField);
        VBox vault = panel();
        vault.getChildren().addAll(section("WALLET SECURITY"), muted("Private keys are encrypted using AES-256-GCM. Passwords are derived with PBKDF2-HMAC-SHA256 (310,000 iterations). The master password is not stored."), primary("Clear all unlocked keys", () -> {
            app.clearKeys();
            app.notifyStatus("All in-memory keys cleared.");
            app.alert("Vault locked", "All decrypted private keys were cleared from memory.");
        }));
        VBox db = panel();
        db.getChildren().addAll(section("LOCAL DATA"), muted("SQLite database: " + System.getProperty("user.home") + "/.chainforge/chainforge.db"), muted("Wallets, recipients, network definitions and transaction history remain on this device."));
        VBox safety = panel();
        safety.getChildren().addAll(section("NETWORK SAFETY"), muted("Testnets are enabled by default. Mainnet networks (real funds) are disabled until you enable them in Networks and confirm by typing. Only import keys you control, and start with small amounts."), muted("Never share your vault password, private key or seed phrase."));
        for (VBox card : List.of(vault, db, safety)) {
            for (var node : card.getChildren()) {
                if (node instanceof Label label) label.setWrapText(true);
            }
        }
        v.getChildren().addAll(themePanel, vault, db, safety);
        return v;
    }
}
