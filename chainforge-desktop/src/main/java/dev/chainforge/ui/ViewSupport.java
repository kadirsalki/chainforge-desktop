package dev.chainforge.ui;

import java.util.List;
import java.util.Locale;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

final class ViewSupport {
    private ViewSupport() {
    }

    static VBox pageShell(String title, String subtitle) {
        VBox v = new VBox(18);
        v.setPadding(new Insets(28, 30, 30, 30));
        v.getStyleClass().add("content");
        Label h = new Label(title);
        h.getStyleClass().add("hero");
        Label d = new Label(subtitle);
        d.setWrapText(true);
        d.getStyleClass().add("muted");
        v.getChildren().addAll(h, d);
        return v;
    }

    static VBox panel() {
        VBox b = new VBox(12);
        b.getStyleClass().add("panel");
        b.setPadding(new Insets(18));
        return b;
    }

    static GridPane alignedCardRows(List<? extends Region> cards, double cardWidth, double gap) {
        GridPane grid = new GridPane();
        grid.setHgap(gap);
        grid.setVgap(gap);
        grid.setMinWidth(0);
        for (Region card : cards) card.setMaxHeight(Double.MAX_VALUE);
        int[] previousColumns = {0};
        Runnable arrange = () -> {
            int columns = Math.max(1, (int) ((grid.getWidth() + gap) / (cardWidth + gap)));
            if (previousColumns[0] == columns) return;
            previousColumns[0] = columns;
            grid.getChildren().clear();
            for (int index = 0; index < cards.size(); index++) {
                Region card = cards.get(index);
                GridPane.setFillHeight(card, true);
                grid.add(card, index % columns, index / columns);
            }
        };
        grid.widthProperty().addListener((observable, previous, width) -> arrange.run());
        arrange.run();
        return grid;
    }

    static Label section(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("section");
        return l;
    }

    static Label muted(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("muted");
        return l;
    }

    static Button primary(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("primary");
        b.setOnAction(e -> action.run());
        return b;
    }

    static VBox stat(String t, String value) {
        VBox b = new VBox(8);
        b.getStyleClass().add("stat");
        Label l = new Label(t);
        l.getStyleClass().add("panel-label");
        Label n = new Label(value);
        n.getStyleClass().add("stat-value");
        b.getChildren().addAll(l, n);
        return b;
    }

    static String safe(String s) {
        return s == null ? "—" : s;
    }

    static String shortHash(String s) {
        return s == null ? "Pending" : s.length() > 18 ? s.substring(0, 10) + "…" + s.substring(s.length() - 6) : s;
    }

    static VBox fieldCard(String title, Control... controls) {
        VBox box = new VBox(5);
        box.getChildren().add(muted(title));
        for (Control control : controls) {
            control.setMaxWidth(Double.MAX_VALUE);
            box.getChildren().add(control);
        }
        return box;
    }

    static Label metricValue(String value) {
        Label l = new Label(value);
        l.getStyleClass().add("metric-value");
        return l;
    }

    static VBox metricCard(String title, Label value) {
        VBox c = new VBox(5);
        c.getStyleClass().add("metric-card");
        Label t = new Label(title);
        t.getStyleClass().add("panel-label");
        c.getChildren().addAll(t, value);
        HBox.setHgrow(c, Priority.ALWAYS);
        return c;
    }

    static String statusStyle(String status) {
        String s = safe(status).toUpperCase(Locale.ROOT);
        return switch (s) {
            case "CONFIRMED", "SUCCESS" -> "tx-status-success";
            case "FAILED" -> "tx-status-failed";
            case "PENDING", "SUBMITTED", "UNKNOWN", "SUBMITTING" -> "tx-status-pending";
            default -> "status-pill";
        };
    }

    static String localTime(String utc) {
        try {
            return java.time.LocalDateTime.parse(utc.replace(' ', 'T')).atOffset(java.time.ZoneOffset.UTC)
                    .atZoneSameInstant(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (Exception e) {
            return utc == null ? "" : utc;
        }
    }

    static String csv(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\"";
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }
}
