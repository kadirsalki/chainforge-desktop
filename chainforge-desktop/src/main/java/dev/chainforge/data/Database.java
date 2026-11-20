package dev.chainforge.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;

public final class Database {
    private static final String DIR = System.getProperty("user.home") + "/.chainforge";
    private static final String URL = "jdbc:sqlite:" + DIR + "/chainforge.db";

    public static Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(URL);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA journal_mode=WAL");
        }
        return c;
    }

    public static void initialize() {
        try {
            Files.createDirectories(Path.of(DIR));
            try (Connection c = connect(); Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS wallets(id INTEGER PRIMARY KEY,name TEXT NOT NULL,chain_family TEXT NOT NULL,address TEXT NOT NULL,public_key TEXT,encrypted_private_key BLOB NOT NULL,salt BLOB NOT NULL,created_at TEXT DEFAULT CURRENT_TIMESTAMP,UNIQUE(chain_family,address))");
                s.execute("CREATE TABLE IF NOT EXISTS networks(id INTEGER PRIMARY KEY,name TEXT NOT NULL,family TEXT NOT NULL,network_key TEXT UNIQUE NOT NULL,chain_id TEXT,rpc_url TEXT,explorer_url TEXT,is_testnet INTEGER DEFAULT 1,enabled INTEGER DEFAULT 1)");
                s.execute("CREATE TABLE IF NOT EXISTS recipient_groups(id INTEGER PRIMARY KEY,name TEXT NOT NULL,network_key TEXT NOT NULL,created_at TEXT DEFAULT CURRENT_TIMESTAMP,UNIQUE(network_key,name))");
                s.execute("CREATE TABLE IF NOT EXISTS recipients(id INTEGER PRIMARY KEY,network_key TEXT NOT NULL,label TEXT,address TEXT NOT NULL,group_id INTEGER,created_at TEXT DEFAULT CURRENT_TIMESTAMP,UNIQUE(network_key,address),FOREIGN KEY(group_id) REFERENCES recipient_groups(id) ON DELETE SET NULL)");
                // Safe migration for databases created by earlier ChainForge builds.
                boolean hasGroup = false;
                try (ResultSet cols = s.executeQuery("PRAGMA table_info(recipients)")) {
                    while (cols.next()) if ("group_id".equalsIgnoreCase(cols.getString("name"))) hasGroup = true;
                }
                if (!hasGroup) s.execute("ALTER TABLE recipients ADD COLUMN group_id INTEGER");
                s.execute("CREATE INDEX IF NOT EXISTS idx_recipients_network_group ON recipients(network_key,group_id)");
                s.execute("CREATE TABLE IF NOT EXISTS tokens(id INTEGER PRIMARY KEY,network_key TEXT NOT NULL,contract_address TEXT NOT NULL,symbol TEXT NOT NULL,name TEXT,decimals INTEGER NOT NULL,created_at TEXT DEFAULT CURRENT_TIMESTAMP,UNIQUE(network_key,contract_address))");
                s.execute("CREATE TABLE IF NOT EXISTS transactions(id INTEGER PRIMARY KEY,network_key TEXT NOT NULL,tx_hash TEXT,from_address TEXT,to_address TEXT,amount TEXT,asset TEXT,fee TEXT,status TEXT NOT NULL,error TEXT,created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
                s.execute("CREATE TABLE IF NOT EXISTS simulation_jobs(id INTEGER PRIMARY KEY,network_key TEXT NOT NULL,status TEXT NOT NULL,recipient_count INTEGER DEFAULT 0,completed_count INTEGER DEFAULT 0,failed_count INTEGER DEFAULT 0,config_json TEXT,created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
                s.execute("INSERT OR IGNORE INTO networks(name,family,network_key,chain_id,rpc_url,explorer_url,is_testnet) VALUES('Ethereum Sepolia','EVM','ethereum-sepolia','11155111','https://ethereum-sepolia-rpc.publicnode.com','https://sepolia.etherscan.io',1)");
                s.execute("INSERT OR IGNORE INTO networks(name,family,network_key,chain_id,rpc_url,explorer_url,is_testnet) VALUES('Solana Devnet','SOLANA','solana-devnet',NULL,'https://api.devnet.solana.com','https://explorer.solana.com',1)");
                s.execute("INSERT OR IGNORE INTO tokens(network_key,contract_address,symbol,name,decimals) VALUES('ethereum-sepolia','0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238','USDC','USD Coin (Sepolia)',6)");
                s.execute("INSERT OR IGNORE INTO recipient_groups(name,network_key) SELECT 'Default',network_key FROM networks");
                s.execute("UPDATE recipients SET group_id=(SELECT id FROM recipient_groups g WHERE g.network_key=recipients.network_key AND g.name='Default') WHERE group_id IS NULL");
            }
        } catch (Exception e) {
            throw new IllegalStateException("SQLite initialization failed", e);
        }
    }
}