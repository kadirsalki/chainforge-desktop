package dev.chainforge.ui;

import dev.chainforge.core.AdapterFactory;
import dev.chainforge.core.ChainAdapter;
import dev.chainforge.data.Repository;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

import java.util.*;

public final class MainView {
    private final BorderPane root = new BorderPane();
    private static final String LIGHT_STYLES = Objects.requireNonNull(MainView.class.getResource("/theme-light.css")).toExternalForm();
    private final Appearance appearance = new Appearance(this::applyLightTheme);
    private final Map<String, Button> navigation = new LinkedHashMap<>();
    private String currentPage = "Overview";
    private final Map<Long, String> unlockedKeys = new HashMap<>();
    private final AdapterFactory adapters = new AdapterFactory();
    private Label statusLabel;

    private final OverviewView overview = new OverviewView(this);
    private final WalletsView wallets = new WalletsView(this);
    private final NetworksView networks = new NetworksView(this);
    private final RecipientsView recipients = new RecipientsView(this);
    private final TransactionsView transactions = new TransactionsView(this);
    private final SimulationsView simulations = new SimulationsView(this);
    private final SettingsView settings = new SettingsView(this);

    public Parent build() {
        root.getStyleClass().add("app");
        appearance.apply();
        root.setLeft(sidebar());
        root.setTop(topbar());
        showPage("Overview");
        return root;
    }

    private VBox sidebar() {
        VBox s = new VBox(18);
        s.getStyleClass().add("sidebar");
        s.setPadding(new Insets(24, 16, 20, 16));
        s.setPrefWidth(238);
        HBox brand = new HBox(11);
        brand.setAlignment(Pos.CENTER_LEFT);
        Label mark = new Label("C");
        mark.getStyleClass().add("mark");
        VBox bt = new VBox(2);
        Label n = new Label("CHAINFORGE");
        n.getStyleClass().add("brand");
        Label sub = new Label("BLOCKCHAIN TEST STUDIO");
        sub.getStyleClass().add("caption");
        bt.getChildren().addAll(n, sub);
        brand.getChildren().addAll(mark, bt);
        Label cap = new Label("WORKSPACE");
        cap.getStyleClass().add("caption");
        VBox nav = new VBox(5);
        for (String x : new String[]{"Overview", "Wallets", "Networks", "Recipients", "Transactions", "Simulations", "Settings"}) {
            Button b = new Button(icon(x) + "   " + x);
            b.getStyleClass().add("nav");
            b.setMaxWidth(Double.MAX_VALUE);
            navigation.put(x, b);
            b.setOnAction(e -> showPage(x));
            nav.getChildren().add(b);
        }
        s.getChildren().addAll(brand, new Separator(), cap, nav, simulations.sidebarMonitor());
        return s;
    }

    private String icon(String p) {
        return switch (p) {
            case "Overview" -> "◫";
            case "Wallets" -> "▣";
            case "Networks" -> "◎";
            case "Recipients" -> "⇢";
            case "Transactions" -> "≋";
            case "Simulations" -> "◷";
            default -> "⚙";
        };
    }

    private HBox topbar() {
        HBox h = new HBox(14);
        h.getStyleClass().add("topbar");
        h.setPadding(new Insets(17, 28, 17, 28));
        h.setAlignment(Pos.CENTER_LEFT);
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        Button lock = new Button("⌑  Clear unlocked keys");
        lock.getStyleClass().add("secondary");
        lock.setOnAction(e -> {
            unlockedKeys.clear();
            notifyStatus("In-memory wallet keys cleared.");
        });
        statusLabel = new Label("Ready");
        statusLabel.getStyleClass().add("muted");
        h.getChildren().addAll(statusLabel, r, lock);
        return h;
    }

    void showPage(String p) {
        if (!"Transactions".equals(p)) transactions.onExit();
        if (!"Simulations".equals(p)) simulations.onExit();
        else simulations.onEnter();
        currentPage = p;
        navigation.forEach((page, button) -> {
            button.getStyleClass().remove("active");
            if (page.equals(p)) button.getStyleClass().add("active");
        });
        Parent content = switch (p) {
            case "Overview" -> overview.build();
            case "Wallets" -> wallets.build();
            case "Networks" -> networks.build();
            case "Recipients" -> recipients.build();
            case "Transactions" -> transactions.build();
            case "Simulations" -> simulations.build();
            default -> settings.build();
        };
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("page-scroll");
        root.setCenter(scroll);
    }

    void notifyStatus(String s) {
        if (statusLabel != null) statusLabel.setText(s);
    }

    private void applyLightTheme(boolean light) {
        if (light && !root.getStylesheets().contains(LIGHT_STYLES)) root.getStylesheets().add(LIGHT_STYLES);
        if (!light) root.getStylesheets().remove(LIGHT_STYLES);
    }

    void styleDialog(Dialog<?> d) {
        String css = Objects.requireNonNull(getClass().getResource("/styles.css")).toExternalForm();
        if (!d.getDialogPane().getStylesheets().contains(css)) d.getDialogPane().getStylesheets().add(css);
        if (appearance.isLight()) d.getDialogPane().getStylesheets().add(LIGHT_STYLES);
        d.getDialogPane().getStyleClass().add("app-dialog");
    }

    void alert(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        styleDialog(a);
        a.setTitle(title);
        a.setHeaderText(null);
        a.setContentText(msg);
        a.showAndWait();
    }

    void error(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        styleDialog(a);
        a.setTitle("ChainForge");
        a.setHeaderText("Could not complete the operation");
        a.setContentText(msg);
        a.getButtonTypes().setAll(new ButtonType("OK", ButtonBar.ButtonData.OK_DONE));
        a.showAndWait();
    }

    Optional<String> ask(String title, String prompt, boolean password) {
        Dialog<String> d = new Dialog<>();
        styleDialog(d);
        d.setTitle(title);
        d.setHeaderText(prompt);
        ButtonType ok = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        Control field = password ? new PasswordField() : new TextField();
        field.setPrefWidth(340);
        d.getDialogPane().setContent(field);
        d.setResultConverter(bt -> bt == ok ? (password ? ((PasswordField) field).getText() : ((TextField) field).getText()) : null);
        return d.showAndWait();
    }

    boolean confirm(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, msg, ButtonType.YES, ButtonType.NO);
        styleDialog(a);
        a.setTitle(title);
        return a.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }

    ChainAdapter adapterFor(Repository.Network n) {
        return adapters.forNetwork(n);
    }

    void copyToClipboard(String value, String message) {
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(value);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        notifyStatus(message);
    }

    Appearance appearance() {
        return appearance;
    }

    boolean isCurrentPage(String page) {
        return page.equals(currentPage);
    }

    Node contentView() {
        return root.getCenter();
    }

    Window window() {
        return root.getScene().getWindow();
    }

    String unlockedKey(long walletId) {
        return unlockedKeys.get(walletId);
    }

    void rememberKey(long walletId, String key) {
        unlockedKeys.put(walletId, key);
    }

    void forgetKey(long walletId) {
        unlockedKeys.remove(walletId);
    }

    void clearKeys() {
        unlockedKeys.clear();
    }

    void pollPendingTransactions() {
        transactions.pollPendingTransactions(true);
    }

    void refreshSimulationSummary() {
        simulations.refreshSummary();
    }

    void openExplorer(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception e) {
            error("Could not open browser: " + e.getMessage());
        }
    }
}
