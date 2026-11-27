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
                // Migration: transactions carry a direction (OUT = sent by ChainForge, IN = received, read from the chain).
                boolean hasDirection = false;
                try (ResultSet cols = s.executeQuery("PRAGMA table_info(transactions)")) {
                    while (cols.next()) if ("direction".equalsIgnoreCase(cols.getString("name"))) hasDirection = true;
                }
                if (!hasDirection) s.execute("ALTER TABLE transactions ADD COLUMN direction TEXT DEFAULT 'OUT'");
                s.execute("UPDATE transactions SET direction='OUT' WHERE direction IS NULL OR direction=''");
                // Migration: outgoing transfers remember which simulation batch sent them (for the post-simulation summary).
                boolean hasJob = false, hasTokenSymbol = false, hasRetrySource = false;
                try (ResultSet cols = s.executeQuery("PRAGMA table_info(transactions)")) {
                    while (cols.next()) {
                        String column = cols.getString("name");
                        if ("job_id".equalsIgnoreCase(column)) hasJob = true;
                        if ("token_symbol".equalsIgnoreCase(column)) hasTokenSymbol = true;
                        if ("retry_of".equalsIgnoreCase(column)) hasRetrySource = true;
                    }
                }
                if (!hasJob) s.execute("ALTER TABLE transactions ADD COLUMN job_id INTEGER");
                if (!hasTokenSymbol) s.execute("ALTER TABLE transactions ADD COLUMN token_symbol TEXT");
                if (!hasRetrySource) s.execute("ALTER TABLE transactions ADD COLUMN retry_of INTEGER");
                s.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_tx_retry_source ON transactions(retry_of) WHERE retry_of IS NOT NULL");
                s.execute("CREATE INDEX IF NOT EXISTS idx_tx_job ON transactions(job_id)");
                // One row per received (tx, wallet, asset); makes repeated syncs idempotent.
                s.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_tx_incoming ON transactions(network_key,tx_hash,to_address,asset) WHERE direction='IN'");
                s.execute("CREATE TABLE IF NOT EXISTS simulation_jobs(id INTEGER PRIMARY KEY,network_key TEXT NOT NULL,status TEXT NOT NULL,recipient_count INTEGER DEFAULT 0,completed_count INTEGER DEFAULT 0,failed_count INTEGER DEFAULT 0,config_json TEXT,created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS simulation_templates(
                            name TEXT PRIMARY KEY COLLATE NOCASE, network_key TEXT NOT NULL, asset TEXT NOT NULL,
                            group_id INTEGER NOT NULL, min_amount TEXT NOT NULL, max_amount TEXT NOT NULL,
                            min_delay TEXT NOT NULL, min_unit TEXT NOT NULL, max_delay TEXT NOT NULL, max_unit TEXT NOT NULL)
                        """);
                s.execute("CREATE TABLE IF NOT EXISTS incoming_sync_history(network_key TEXT PRIMARY KEY, succeeded_at TEXT NOT NULL)");
                s.execute("INSERT OR IGNORE INTO networks(name,family,network_key,chain_id,rpc_url,explorer_url,is_testnet) VALUES('Ethereum Sepolia','EVM','ethereum-sepolia','11155111','https://ethereum-sepolia-rpc.publicnode.com','https://sepolia.etherscan.io',1)");
                s.execute("INSERT OR IGNORE INTO networks(name,family,network_key,chain_id,rpc_url,explorer_url,is_testnet) VALUES('Solana Devnet','SOLANA','solana-devnet',NULL,'https://api.devnet.solana.com','https://explorer.solana.com',1)");
                // Migration: each network carries the ticker of its native coin (ETH, AVAX, SOL, BTC, XRP, ...).
                boolean hasSymbol = false;
                try (ResultSet cols = s.executeQuery("PRAGMA table_info(networks)")) {
                    while (cols.next()) if ("native_symbol".equalsIgnoreCase(cols.getString("name"))) hasSymbol = true;
                }
                if (!hasSymbol) s.execute("ALTER TABLE networks ADD COLUMN native_symbol TEXT");
                // Migration: an earlier build registered Bitcoin as Signet. Wallet keys/addresses (tb1.../WIF) are
                // identical on Testnet, so move the network definition, groups and recipients over.
                s.execute("UPDATE OR IGNORE networks SET name='Bitcoin Testnet',network_key='bitcoin-testnet',rpc_url='https://mempool.space/testnet/api',explorer_url='https://mempool.space/testnet' WHERE network_key='bitcoin-signet'");
                s.execute("UPDATE OR IGNORE recipient_groups SET network_key='bitcoin-testnet' WHERE network_key='bitcoin-signet'");
                s.execute("UPDATE OR IGNORE recipients SET network_key='bitcoin-testnet' WHERE network_key='bitcoin-signet'");
                s.execute("INSERT OR IGNORE INTO networks(name,family,network_key,chain_id,rpc_url,explorer_url,is_testnet) VALUES('Bitcoin Testnet','BITCOIN','bitcoin-testnet',NULL,'https://mempool.space/testnet/api','https://mempool.space/testnet',1)");
                // Mainnet networks are real-money networks: they are created DISABLED and must be switched on
                // explicitly (with a typed confirmation) from the Networks page.
                s.execute("INSERT OR IGNORE INTO networks(name,family,network_key,chain_id,rpc_url,explorer_url,is_testnet,enabled) VALUES('Ethereum Mainnet','EVM','ethereum-mainnet','1','https://ethereum-rpc.publicnode.com','https://etherscan.io',0,0)");
                s.execute("INSERT OR IGNORE INTO networks(name,family,network_key,chain_id,rpc_url,explorer_url,is_testnet,enabled) VALUES('Solana Mainnet','SOLANA','solana-mainnet',NULL,'https://api.mainnet-beta.solana.com','https://explorer.solana.com',0,0)");
                s.execute("INSERT OR IGNORE INTO networks(name,family,network_key,chain_id,rpc_url,explorer_url,is_testnet,enabled) VALUES('Bitcoin Mainnet','BITCOIN','bitcoin-mainnet',NULL,'https://mempool.space/api','https://mempool.space',0,0)");
                s.execute("INSERT OR IGNORE INTO networks(name,family,network_key,chain_id,rpc_url,explorer_url,is_testnet) VALUES('XRP Ledger Testnet','XRP','xrp-testnet',NULL,'https://s.altnet.rippletest.net:51234','https://testnet.xrpl.org',1)");
                s.execute("INSERT OR IGNORE INTO networks(name,family,network_key,chain_id,rpc_url,explorer_url,is_testnet,enabled) VALUES('XRP Ledger Mainnet','XRP','xrp-mainnet',NULL,'https://s1.ripple.com:51234','https://livenet.xrpl.org',0,0)");
                // Avalanche C-Chain (EVM-compatible). Fuji is the testnet; its native coin is AVAX.
                s.execute("INSERT OR IGNORE INTO networks(name,family,network_key,chain_id,rpc_url,explorer_url,is_testnet,native_symbol) VALUES('Avalanche Fuji (C-Chain)','EVM','avalanche-fuji','43113','https://api.avax-test.network/ext/bc/C/rpc','https://testnet.snowtrace.io',1,'AVAX')");
                s.execute("UPDATE networks SET native_symbol=CASE family WHEN 'SOLANA' THEN 'SOL' WHEN 'BITCOIN' THEN 'BTC' WHEN 'XRP' THEN 'XRP' ELSE 'ETH' END WHERE native_symbol IS NULL OR native_symbol=''");
                s.execute("INSERT OR IGNORE INTO tokens(network_key,contract_address,symbol,name,decimals) VALUES('ethereum-sepolia','0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238','USDC','USD Coin (Sepolia)',6)");
                s.execute("INSERT OR IGNORE INTO recipient_groups(name,network_key) SELECT 'Default',network_key FROM networks");
                s.execute("UPDATE recipients SET group_id=(SELECT id FROM recipient_groups g WHERE g.network_key=recipients.network_key AND g.name='Default') WHERE group_id IS NULL");
            }
        } catch (Exception e) {
            throw new IllegalStateException("SQLite initialization failed", e);
        }
    }
}
