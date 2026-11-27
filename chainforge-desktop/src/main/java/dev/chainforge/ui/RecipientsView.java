package dev.chainforge.ui;

import dev.chainforge.data.Repository;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import static dev.chainforge.ui.ViewSupport.alignedCardRows;
import static dev.chainforge.ui.ViewSupport.muted;
import static dev.chainforge.ui.ViewSupport.pageShell;
import static dev.chainforge.ui.ViewSupport.panel;
import static dev.chainforge.ui.ViewSupport.primary;
import static dev.chainforge.ui.ViewSupport.rootMessage;
import static dev.chainforge.ui.ViewSupport.safe;
import static dev.chainforge.ui.ViewSupport.section;
import static dev.chainforge.ui.NetworkSupport.recipientDisplay;
import static dev.chainforge.ui.NetworkSupport.storedRecipient;

final class RecipientsView {
    private final MainView app;

    RecipientsView(MainView app) {
        this.app = app;
    }

    VBox build() {
        VBox v = pageShell("Recipients", "Create separate address books for each network. A simulation can only use recipients from its selected network and group.");
        ComboBox<Repository.Network> network = new ComboBox<>();
        try {
            network.getItems().addAll(Repository.networks().stream().filter(Repository.Network::enabled).toList());
        } catch (Exception ignored) {
        }
        network.setCellFactory(lv -> new ListCell<>() {
            protected void updateItem(Repository.Network n, boolean empty) {
                super.updateItem(n, empty);
                setText(empty || n == null ? null : n.name());
            }
        });
        network.setButtonCell(new ListCell<>() {
            protected void updateItem(Repository.Network n, boolean empty) {
                super.updateItem(n, empty);
                setText(empty || n == null ? "Select network" : n.name());
            }
        });
        if (!network.getItems().isEmpty()) network.setValue(network.getItems().getFirst());
        ComboBox<Repository.RecipientGroup> group = new ComboBox<>();
        Runnable reload = () -> {
            try {
                group.getItems().setAll(Repository.recipientGroups(network.getValue().key()));
                if (!group.getItems().isEmpty()) group.setValue(group.getItems().getFirst());
            } catch (Exception ex) {
                app.error(rootMessage(ex));
            }
        };
        reload.run();
        network.setOnAction(e -> reload.run());
        Button newGroup = primary("+ New group", () -> {
            String n = app.ask("Create recipient group", "Group name:", false).orElse("").trim();
            if (n.isBlank()) return;
            try {
                Repository.createRecipientGroup(network.getValue().key(), n);
                reload.run();
            } catch (Exception ex) {
                app.error("Could not create group: " + rootMessage(ex));
            }
        });
        Button deleteGroup = new Button("Delete group");
        deleteGroup.getStyleClass().add("secondary");
        deleteGroup.setOnAction(e -> {
            Repository.RecipientGroup g = group.getValue();
            if (g == null) {
                app.error("Select a recipient group first.");
                return;
            }
            if ("Default".equalsIgnoreCase(g.name())) {
                app.error("The Default group cannot be deleted (it is recreated automatically). Remove its recipients instead.");
                return;
            }
            if (!app.confirm("Delete group \"" + g.name() + "\"?", "This permanently removes the group and its " + g.count()
                    + " recipient address(es) from the local address book. Past transactions are not affected."))
                return;
            try {
                int removed = Repository.deleteRecipientGroup(g.id());
                app.showPage("Recipients");
                app.alert("Group deleted", "\"" + g.name() + "\" and " + removed + " recipient(s) were removed.");
            } catch (Exception ex) {
                app.error("Could not delete group: " + rootMessage(ex));
            }
        });
        TextField label = new TextField();
        label.setPromptText("Label (optional)");
        TextField address = new TextField();
        address.setPromptText("Recipient address");
        address.setPrefWidth(360);
        TextField tag = new TextField();
        tag.setPromptText("Destination tag (XRP, optional)");
        tag.setPrefWidth(200);
        Button add = primary("Add address", () -> {
            try {
                String stored = storedRecipient(network.getValue(), address.getText().trim(), tag.getText());
                if (group.getValue() == null)
                    throw new IllegalArgumentException("Create or select a recipient group first.");
                Repository.addRecipient(network.getValue().key(), group.getValue().id(), label.getText().trim(), stored);
                app.showPage("Recipients");
            } catch (Exception ex) {
                app.error(rootMessage(ex));
            }
        });
        network.setPrefWidth(175);
        group.setPrefWidth(175);
        label.setPrefWidth(170);
        address.setPrefWidth(280);
        Runnable syncTag = () -> {
            boolean xrp = network.getValue() != null && "XRP".equalsIgnoreCase(network.getValue().family());
            tag.setVisible(xrp);
            tag.setManaged(xrp);
            if (!xrp) tag.clear();
        };
        syncTag.run();
        FlowPane groupControls = new FlowPane(10, 8, network, group, newGroup, deleteGroup);
        FlowPane addressControls = new FlowPane(10, 8, label, address, tag, add);
        groupControls.setAlignment(Pos.CENTER_LEFT);
        addressControls.setAlignment(Pos.CENTER_LEFT);
        VBox controls = panel();
        controls.getChildren().addAll(section("RECIPIENT GROUP"), groupControls, section("ADD AN ADDRESS"), addressControls);
        TextArea bulk = new TextArea();
        bulk.setPromptText("Paste addresses, one per line (optionally \"label,address\"). XRP: \"address,tag\" or \"label,address,tag\". YAML import accepts a list or { recipients: [...] } with optional destination_tag.");
        bulk.setPrefRowCount(5);
        HBox imports = new HBox(10, primary("Import pasted list", () -> importPastedToGroup(network.getValue(), group.getValue(), bulk.getText())), primary("Import YAML file…", () -> importYamlToGroup(network.getValue(), group.getValue())));
        VBox list = panel();
        populateRecipientList(list, network.getValue(), group.getValue());
        group.setOnAction(e -> populateRecipientList(list, network.getValue(), group.getValue()));
        network.setOnAction(e -> {
            reload.run();
            syncTag.run();
            populateRecipientList(list, network.getValue(), group.getValue());
        });
        VBox importPanel = panel();
        importPanel.getChildren().addAll(section("BULK IMPORT"), muted("Paste one address per line, or import a YAML recipient file."), bulk, imports);
        v.getChildren().addAll(controls, importPanel, list);
        return v;
    }

    private void populateRecipientList(VBox list, Repository.Network network, Repository.RecipientGroup group) {
        list.getChildren().clear();
        list.getChildren().add(section("SAVED ADDRESSES"));
        try {
            if (group == null) {
                list.getChildren().add(muted("Create or select a group."));
                return;
            }
            var rows = Repository.recipients(network.key(), group.id());
            if (rows.isEmpty()) list.getChildren().add(muted("This group is empty."));
            List<Region> recipientCards = new ArrayList<>();
            for (var r : rows) {
                VBox row = new VBox(8);
                row.getStyleClass().add("recipient-card");
                row.setPrefWidth(320);
                Label title = new Label(safe(r.label()).isBlank() ? "Unnamed recipient" : r.label());
                title.getStyleClass().add("network-card-title");
                Label address = new Label(recipientDisplay(r.address()));
                address.getStyleClass().add("network-detail-value");
                address.setWrapText(true);
                address.setMaxHeight(Double.MAX_VALUE);
                address.setAlignment(Pos.TOP_LEFT);
                VBox.setVgrow(address, Priority.ALWAYS);
                address.setTooltip(new Tooltip(recipientDisplay(r.address())));
                Button del = new Button("Remove recipient");
                del.getStyleClass().add("secondary");
                del.setOnAction(e -> {
                    try {
                        Repository.deleteRecipient(r.id());
                        populateRecipientList(list, network, group);
                    } catch (Exception ex) {
                        app.error(ex.getMessage());
                    }
                });
                row.getChildren().addAll(title, address, del);
                recipientCards.add(row);
            }
            if (!recipientCards.isEmpty()) list.getChildren().add(alignedCardRows(recipientCards, 320, 10));
        } catch (Exception ex) {
            list.getChildren().add(muted(rootMessage(ex)));
        }
    }

    private void importPastedToGroup(Repository.Network n, Repository.RecipientGroup g, String raw) {
        try {
            if (n == null || g == null) throw new IllegalArgumentException("Select network and recipient group.");
            int added = 0;
            for (String line : raw.split("\\R")) {
                String a = line.trim();
                if (a.isEmpty() || a.startsWith("#")) continue;
                if (a.startsWith("- ")) a = a.substring(2).trim();
                String label = "Imported " + (added + 1);
                String tagPart = null;
                if (a.contains(",")) {
                    if ("XRP".equalsIgnoreCase(n.family())) {
                        String[] parts = a.split(",", -1);
                        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
                        if (parts.length == 3) {
                            label = parts[0];
                            a = parts[1];
                            tagPart = parts[2];
                        } else if (parts.length == 2 && parts[1].matches("\\d+")) {
                            a = parts[0];
                            tagPart = parts[1];
                        } else if (parts.length == 2) {
                            label = parts[0];
                            a = parts[1];
                        } else {
                            throw new IllegalArgumentException("Unrecognised line: " + a);
                        }
                    } else {
                        String[] pair = a.split(",", 2);
                        label = pair[0].trim();
                        a = pair[1].trim();
                    }
                }
                Repository.addRecipient(n.key(), g.id(), label, storedRecipient(n, a, tagPart));
                added++;
            }
            app.showPage("Recipients");
            app.alert("Import complete", added + " address(es) processed into " + g.name() + ".");
        } catch (Exception ex) {
            app.error(rootMessage(ex));
        }
    }

    private void importYamlToGroup(Repository.Network n, Repository.RecipientGroup g) {
        if (n == null || g == null) {
            app.error("Select network and recipient group.");
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Select recipients YAML");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("YAML files", "*.yaml", "*.yml"));
        File f = fc.showOpenDialog(app.window());
        if (f == null) return;
        try {
            Object data = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(f.toPath()));
            if (data instanceof Map<?, ?> m && m.get("recipients") instanceof List<?> list) data = list;
            if (!(data instanceof List<?> items))
                throw new IllegalArgumentException("YAML root must be a list or contain a recipients list.");
            int added = 0;
            for (Object item : items) {
                String a = null, l = "Imported " + (added + 1), tagValue = null;
                if (item instanceof Map<?, ?> m) {
                    a = Objects.toString(m.get("address"), null);
                    l = Objects.toString(m.get("label"), l);
                    Object t = m.containsKey("destination_tag") ? m.get("destination_tag") : m.get("tag");
                    tagValue = t == null ? null : t.toString();
                } else if (item != null) a = item.toString();
                if (a != null) {
                    Repository.addRecipient(n.key(), g.id(), l, storedRecipient(n, a, tagValue));
                    added++;
                }
            }
            app.showPage("Recipients");
            app.alert("YAML imported", added + " address(es) added to " + g.name() + ".");
        } catch (Exception ex) {
            app.error("YAML import failed: " + rootMessage(ex));
        }
    }
}
