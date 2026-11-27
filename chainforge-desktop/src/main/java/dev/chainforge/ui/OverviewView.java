package dev.chainforge.ui;

import dev.chainforge.data.Repository;
import java.util.Locale;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;

import static dev.chainforge.ui.ViewSupport.muted;
import static dev.chainforge.ui.ViewSupport.pageShell;
import static dev.chainforge.ui.ViewSupport.panel;
import static dev.chainforge.ui.ViewSupport.primary;
import static dev.chainforge.ui.ViewSupport.safe;
import static dev.chainforge.ui.ViewSupport.section;
import static dev.chainforge.ui.ViewSupport.shortHash;
import static dev.chainforge.ui.ViewSupport.stat;

final class OverviewView {
    private final MainView app;

    OverviewView(MainView app) {
        this.app = app;
    }

    VBox build() {
        VBox v = pageShell("Your testing workspace", "Live transaction overview for your local simulations.");
        int recipients = 0, confirmed = 0, pending = 0, failed = 0, total = 0;
        try {
            recipients = Repository.count("recipients");
            for (var tx : Repository.transactions()) {
                if ("IN".equals(tx.direction())) continue; // received transfers are not part of the simulation stats
                total++;
                String st = safe(tx.status()).toUpperCase(Locale.ROOT);
                switch (st) {
                    case "CONFIRMED", "SUCCESS" -> confirmed++;
                    case "PENDING", "SUBMITTED" -> pending++;
                    case "FAILED" -> failed++;
                }
            }
        } catch (Exception ignored) {
        }
        TilePane stats = new TilePane(12, 12);
        stats.setPrefColumns(6);
        stats.getChildren().addAll(stat("WALLETS", count("wallets")), stat("ACTIVE NETWORKS", activeNetworks()), stat("RECIPIENTS", String.valueOf(recipients)), stat("CONFIRMED", String.valueOf(confirmed)), stat("PENDING", String.valueOf(pending)), stat("FAILED", String.valueOf(failed)));
        VBox monitor = panel();
        HBox heading = new HBox(10);
        heading.setAlignment(Pos.CENTER_LEFT);
        VBox titleBox = new VBox(4);
        titleBox.getChildren().addAll(section("LIVE MONITOR"), new Label("Transaction activity"));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label state = new Label(pending > 0 ? "● IN PROGRESS" : "● COMPLETED");
        state.getStyleClass().add("monitor-pill");
        heading.getChildren().addAll(titleBox, spacer, state);
        int finished = confirmed + failed;
        double ratio = total == 0 ? 0 : (double) finished / total;
        ProgressBar bar = new ProgressBar(ratio);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.getStyleClass().add("monitor-progress");
        HBox labels = new HBox();
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        labels.getChildren().addAll(muted(confirmed + " confirmed · " + pending + " pending · " + failed + " failed"), gap, muted(total + " total"));
        VBox table = panel();
        table.getChildren().add(section("RECENT ACTIVITY"));
        try {
            var txs = Repository.transactions();
            if (txs.isEmpty())
                table.getChildren().add(muted("No transactions recorded yet. Start a simulation to see activity here."));
            else for (var tx : txs.stream().limit(8).toList()) {
                HBox row = new HBox(12);
                row.setAlignment(Pos.CENTER_LEFT);
                VBox details = new VBox(3);
                boolean incoming = "IN".equals(tx.direction());
                details.getChildren().addAll(new Label((incoming ? "↓ from " + shortHash(tx.from()) : "↑ " + shortHash(tx.to()))), muted((incoming ? "+" : "") + safe(tx.amount()) + " " + safe(tx.asset())));
                Region push = new Region();
                HBox.setHgrow(push, Priority.ALWAYS);
                Label status = new Label(safe(tx.status()));
                status.getStyleClass().add("status-pill");
                row.getChildren().addAll(details, push, status, muted(shortHash(tx.hash())));
                table.getChildren().addAll(row, new Separator());
            }
        } catch (Exception ex) {
            table.getChildren().add(muted(ex.getMessage()));
        }
        HBox actions = new HBox(10, primary("+ Create wallet", () -> app.showPage("Wallets")), primary("+ Add recipients", () -> app.showPage("Recipients")), primary("+ New simulation", () -> app.showPage("Simulations")));
        monitor.getChildren().addAll(heading, muted("Execution progress"), bar, labels);
        v.getChildren().addAll(stats, monitor, actions, table, muted("MAINNET DISABLED BY DEFAULT  •  LOCAL SQLITE  •  AES-256-GCM ENCRYPTED WALLET STORAGE"));
        return v;
    }

    private String count(String table) {
        try {
            return String.valueOf(Repository.count(table));
        } catch (Exception e) {
            return "—";
        }
    }

    private String activeNetworks() {
        try {
            return String.valueOf(Repository.networks().stream().filter(Repository.Network::enabled).count());
        } catch (Exception e) {
            return "—";
        }
    }
}
