package dev.chainforge.data;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class Repository {
    private Repository() {
    }

    public record Wallet(long id, String name, String family, String address, byte[] encrypted, byte[] salt) {
    }

    public record Recipient(long id, String network, String label, String address, long groupId) {
        public String toString() {
            return (label == null || label.isBlank() ? "Recipient" : label) + " · " + address;
        }
    }

    public record RecipientGroup(long id, String network, String name, int count) {
        public String toString() {
            return name + " · " + count + " addresses";
        }
    }

    public record Tx(long id, String network, String hash, String from, String to, String amount, String asset,
                     String fee, String status, String error, String created) {
    }

    public record Network(long id, String name, String family, String key, String chainId, String rpc, String explorer,
                          boolean testnet, boolean enabled) {
    }

    public record Token(long id, String network, String contract, String symbol, String name, int decimals) {
        public String toString() {
            return symbol + " · " + contract.substring(0, 8) + "…" + contract.substring(contract.length() - 6);
        }
    }

    public static List<Wallet> wallets() throws SQLException {
        var out = new ArrayList<Wallet>();
        try (var c = Database.connect(); var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM wallets ORDER BY id DESC")) {
            while (r.next())
                out.add(new Wallet(r.getLong("id"), r.getString("name"), r.getString("chain_family"), r.getString("address"), r.getBytes("encrypted_private_key"), r.getBytes("salt")));
        }
        return out;
    }

    public static void addWallet(String name, String family, String address, byte[] encrypted, byte[] salt) throws SQLException {
        try (var c = Database.connect(); var p = c.prepareStatement("INSERT INTO wallets(name,chain_family,address,encrypted_private_key,salt) VALUES(?,?,?,?,?)")) {
            p.setString(1, name);
            p.setString(2, family);
            p.setString(3, address);
            p.setBytes(4, encrypted);
            p.setBytes(5, salt);
            p.executeUpdate();
        }
    }

    public static void deleteWallet(long id) throws SQLException {
        try (var c = Database.connect(); var p = c.prepareStatement("DELETE FROM wallets WHERE id=?")) {
            p.setLong(1, id);
            p.executeUpdate();
        }
    }

    public static List<RecipientGroup> recipientGroups(String network) throws SQLException {
        var out = new ArrayList<RecipientGroup>();
        try (var c = Database.connect(); var p = c.prepareStatement("SELECT g.id,g.network_key,g.name,COUNT(r.id) AS n FROM recipient_groups g LEFT JOIN recipients r ON r.group_id=g.id WHERE g.network_key=? GROUP BY g.id ORDER BY g.name")) {
            p.setString(1, network);
            try (var r = p.executeQuery()) {
                while (r.next()) out.add(new RecipientGroup(r.getLong(1), r.getString(2), r.getString(3), r.getInt(4)));
            }
        }
        return out;
    }

    public static long createRecipientGroup(String network, String name) throws SQLException {
        try (var c = Database.connect(); var p = c.prepareStatement("INSERT INTO recipient_groups(name,network_key) VALUES(?,?)", Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1, name);
            p.setString(2, network);
            p.executeUpdate();
            try (var k = p.getGeneratedKeys()) {
                return k.next() ? k.getLong(1) : -1;
            }
        }
    }

    public static List<Recipient> recipients(String network) throws SQLException {
        return recipients(network, null);
    }

    public static List<Recipient> recipients(String network, Long groupId) throws SQLException {
        var out = new ArrayList<Recipient>();
        String q = "SELECT * FROM recipients WHERE network_key=?" + (groupId == null ? "" : " AND group_id=?") + " ORDER BY id DESC";
        try (var c = Database.connect(); var p = c.prepareStatement(q)) {
            p.setString(1, network);
            if (groupId != null) p.setLong(2, groupId);
            try (var r = p.executeQuery()) {
                while (r.next())
                    out.add(new Recipient(r.getLong("id"), r.getString("network_key"), r.getString("label"), r.getString("address"), r.getLong("group_id")));
            }
        }
        return out;
    }

    public static void addRecipient(String network, String label, String address) throws SQLException {
        long group;
        try (var c = Database.connect(); var p = c.prepareStatement("SELECT id FROM recipient_groups WHERE network_key=? AND name='Default'")) {
            p.setString(1, network);
            try (var r = p.executeQuery()) {
                if (r.next()) group = r.getLong(1);
                else group = createRecipientGroup(network, "Default");
            }
        }
        addRecipient(network, group, label, address);
    }

    public static void addRecipient(String network, long groupId, String label, String address) throws SQLException {
        try (var c = Database.connect(); var p = c.prepareStatement("INSERT OR IGNORE INTO recipients(network_key,label,address,group_id) VALUES(?,?,?,?)")) {
            p.setString(1, network);
            p.setString(2, label);
            p.setString(3, address);
            p.setLong(4, groupId);
            p.executeUpdate();
        }
    }

    public static void deleteRecipient(long id) throws SQLException {
        try (var c = Database.connect(); var p = c.prepareStatement("DELETE FROM recipients WHERE id=?")) {
            p.setLong(1, id);
            p.executeUpdate();
        }
    }

    public static List<Tx> transactions() throws SQLException {
        var out = new ArrayList<Tx>();
        try (var c = Database.connect(); var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM transactions ORDER BY id DESC")) {
            while (r.next())
                out.add(new Tx(r.getLong("id"), r.getString("network_key"), r.getString("tx_hash"), r.getString("from_address"), r.getString("to_address"), r.getString("amount"), r.getString("asset"), r.getString("fee"), r.getString("status"), r.getString("error"), r.getString("created_at")));
        }
        return out;
    }

    public static long addTx(String network, String hash, String from, String to, String amount, String asset, String fee, String status, String error) throws SQLException {
        try (var c = Database.connect(); var p = c.prepareStatement("INSERT INTO transactions(network_key,tx_hash,from_address,to_address,amount,asset,fee,status,error) VALUES(?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1, network);
            p.setString(2, hash);
            p.setString(3, from);
            p.setString(4, to);
            p.setString(5, amount);
            p.setString(6, asset);
            p.setString(7, fee);
            p.setString(8, status);
            p.setString(9, error);
            p.executeUpdate();
            try (var k = p.getGeneratedKeys()) {
                return k.next() ? k.getLong(1) : -1;
            }
        }
    }

    public static void updateTx(long id, String hash, String fee, String status, String error) throws SQLException {
        try (var c = Database.connect(); var p = c.prepareStatement("UPDATE transactions SET tx_hash=?,fee=?,status=?,error=? WHERE id=?")) {
            p.setString(1, hash);
            p.setString(2, fee);
            p.setString(3, status);
            p.setString(4, error);
            p.setLong(5, id);
            p.executeUpdate();
        }
    }

    public static void deleteToken(String contractAddress) throws SQLException {
        try (var c = Database.connect(); var p = c.prepareStatement("DELETE FROM tokens WHERE contract_address=?")) {
            p.setString(1, contractAddress);
            p.executeUpdate();
        }
    }

    public static List<Token> tokens(String network) throws SQLException {
        deleteToken("");
        var out = new ArrayList<Token>();
        try (var c = Database.connect(); var p = c.prepareStatement("SELECT * FROM tokens WHERE network_key=? ORDER BY symbol")) {
            p.setString(1, network);
            try (var r = p.executeQuery()) {
                while (r.next())
                    out.add(new Token(r.getLong("id"), r.getString("network_key"), r.getString("contract_address"), r.getString("symbol"), r.getString("name"), r.getInt("decimals")));
            }
        }
        System.out.println(out);
        return out;
    }

    public static void saveToken(String network, String contract, String symbol, String name, int decimals) throws SQLException {
        try (var c = Database.connect(); var p = c.prepareStatement("INSERT INTO tokens(network_key,contract_address,symbol,name,decimals) VALUES(?,?,?,?,?) ON CONFLICT(network_key,contract_address) DO UPDATE SET symbol=excluded.symbol,name=excluded.name,decimals=excluded.decimals")) {
            p.setString(1, network);
            p.setString(2, contract);
            p.setString(3, symbol);
            p.setString(4, name);
            p.setInt(5, decimals);
            p.executeUpdate();
        }
    }

    public static List<Network> networks() throws SQLException {
        var out = new ArrayList<Network>();
        try (var c = Database.connect(); var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM networks ORDER BY id")) {
            while (r.next())
                out.add(new Network(r.getLong("id"), r.getString("name"), r.getString("family"), r.getString("network_key"), r.getString("chain_id"), r.getString("rpc_url"), r.getString("explorer_url"), r.getInt("is_testnet") == 1, r.getInt("enabled") == 1));
        }
        return out;
    }

    public static void saveNetwork(String name, String family, String key, String chainId, String rpc, String explorer, boolean testnet) throws SQLException {
        try (var c = Database.connect(); var p = c.prepareStatement("INSERT INTO networks(name,family,network_key,chain_id,rpc_url,explorer_url,is_testnet,enabled) VALUES(?,?,?,?,?,?,?,1) ON CONFLICT(network_key) DO UPDATE SET name=excluded.name,family=excluded.family,chain_id=excluded.chain_id,rpc_url=excluded.rpc_url,explorer_url=excluded.explorer_url,is_testnet=excluded.is_testnet")) {
            p.setString(1, name);
            p.setString(2, family);
            p.setString(3, key);
            p.setString(4, chainId);
            p.setString(5, rpc);
            p.setString(6, explorer);
            p.setInt(7, testnet ? 1 : 0);
            p.executeUpdate();
        }
    }

    public static void setNetworkEnabled(String key, boolean enabled) throws SQLException {
        try (var c = Database.connect(); var p = c.prepareStatement("UPDATE networks SET enabled=? WHERE network_key=?")) {
            p.setInt(1, enabled ? 1 : 0);
            p.setString(2, key);
            p.executeUpdate();
        }
    }

    public static long createSimulationJob(String network, int recipientCount, String config) throws SQLException {
        try (var c = Database.connect(); var p = c.prepareStatement("INSERT INTO simulation_jobs(network_key,status,recipient_count,config_json) VALUES(?,'RUNNING',?,?)", Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1, network);
            p.setInt(2, recipientCount);
            p.setString(3, config);
            p.executeUpdate();
            try (var k = p.getGeneratedKeys()) {
                return k.next() ? k.getLong(1) : -1;
            }
        }
    }

    public static void updateSimulationJob(long id, String status, int completed, int failed) throws SQLException {
        try (var c = Database.connect(); var p = c.prepareStatement("UPDATE simulation_jobs SET status=?,completed_count=?,failed_count=? WHERE id=?")) {
            p.setString(1, status);
            p.setInt(2, completed);
            p.setInt(3, failed);
            p.setLong(4, id);
            p.executeUpdate();
        }
    }

    public static int count(String table) throws SQLException {
        try (var c = Database.connect(); var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return r.next() ? r.getInt(1) : 0;
        }
    }
}
