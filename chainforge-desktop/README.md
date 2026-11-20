# ChainForge Desktop — EVM ERC-20 build candidate

Java 21 + JavaFX desktop app with local SQLite storage.

## Enabled functionality in this source tree

- Ethereum Sepolia: native ETH balance and transfer.
- Ethereum Sepolia: generic ERC-20 metadata (`name`, `symbol`, `decimals`), `balanceOf`, and `transfer` using the contract address supplied in the simulation screen.
- Wallets page: asynchronously refreshed native ETH/SOL balance for each wallet; EVM token balance can be queried by entering a contract address.
- Solana Devnet: native SOL balance and transfer only.
- Network-scoped recipient groups, YAML/pasted recipient import, simulation progress, transaction persistence.
- One process-wide simulation lock; second batch is rejected until the current worker exits.
- Encrypted-at-rest private keys (AES-256-GCM / PBKDF2-HMAC-SHA256).

## Explicit limitations

- SPL token balances/transfers are **not implemented** in this build. Solana supports native SOL only.
- Bitcoin is not implemented.
- ERC-20 transfer requires the token contract to implement standard `name()`, `symbol()`, `decimals()`, `balanceOf(address)` and `transfer(address,uint256)` methods. Non-standard bytes32 symbol/name contracts may not decode.
- Token contract address is supplied by the operator and must be independently verified for the selected network. A valid contract can still be malicious or unrelated to a well-known token.
- The UI currently asks for a token contract each time; it does not yet persist a token catalogue.
- Only Ethereum Sepolia and Solana Devnet are configured as live adapters.

## Run

Requirements: JDK 21 and Maven 3.9+.

```bash
mvn clean test
mvn clean javafx:run
```

The authoring container did not have Maven or dependency artifacts, so the complete Maven compile and live RPC transfer were not run here. The dependency-free core smoke test can be run with:

```bash
./scripts/smoke-test-core.sh
```

Expected output:

```text
PASS simulation lock: concurrent batch rejected; released lock reusable
PASS Solana Base58: 1/32/64-byte round trips
```

## ERC-20 transfer test procedure

1. Create or import a disposable Sepolia-only EVM wallet.
2. Fund it with Sepolia ETH; ERC-20 transfers still require ETH for gas.
3. In Simulations, choose Ethereum Sepolia → ERC-20 Token.
4. Paste a contract address from a source you independently trust and click **Verify token**.
5. Verify the displayed name, symbol, decimals and contract address.
6. Use one recipient first and a tiny amount that fits the token decimals.
7. Confirm the transaction hash and receipt in Sepolia Etherscan.

Never assume a token named USDT/USDC is authentic solely from its symbol. Check the contract address on a trusted source and verify it is deployed on Sepolia. Testnet tokens have no mainnet value.

## Data and security

Database: `~/.chainforge/chainforge.db`

Never import mainnet keys into this test tool. Unlocked key strings remain in application memory until cleared or app exit. Do not share private keys, seed phrases or vault passwords.

## Portfolio / token catalogue update

- Wallets are rendered as balance cards with native ETH/SOL and balances for the locally tracked ERC-20 token catalogue.
- Use **Add EVM token** in Wallets to enter a contract once. The app reads on-chain `name`, `symbol`, and `decimals`, asks for confirmation, then saves the contract in SQLite. Simulations show saved token symbols in the asset selector; selecting one displays its contract and decimals.
- The initial catalogue includes the Sepolia USDC contract `0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238` as a convenience entry. Verify it independently before sending.
- Generic Ethereum JSON-RPC does not provide a standard method to enumerate every ERC-20 held by an address. The portfolio therefore displays native balance plus every token in the tracked catalogue; automatic discovery of arbitrary holdings requires an external indexer/API and is not claimed here.
- Solana SPL tokens are not yet included in the portfolio catalogue; this UI explicitly reports that SPL discovery is not enabled.

## UX update

- Networks now use responsive cards with active/disabled state, family, network key, chain ID, RPC endpoint, quick RPC copy, and enable/disable actions.
- Network summary metrics and adapter capability labels make supported, partial, and planned functionality explicit.
- Recipient entries use responsive cards; address creation/import controls wrap instead of forcing a single wide row.
- Overview surfaces wallet and active-network counts alongside recipient and transaction outcome metrics.
- Shared visual hierarchy, spacing, focus states, and card styling have been refined across the desktop UI.

The UX changes are presentation-layer changes; network adapters, transaction execution, wallet encryption, and SQLite persistence remain unchanged.
