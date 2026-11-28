# Zero-Knowledge Anonymous Authentication

## Status

This document defines the proposed AirMesh anonymous-account authentication design. It is an architecture and security specification, not a claim that the current Android client or cloud service already implements the protocol.

The design collects no email address, phone number, legal name, or other real-world identity attribute. A user is represented by a unique AirMesh username and one or more cryptographic public keys.

> **Terminology note:** the challenge-response flow below keeps the master passphrase off the server and proves possession of a private key. That is not, by itself, a formal zero-knowledge proof protocol. AirMesh should describe it as password-private, anonymous public-key authentication unless a reviewed PAKE or zero-knowledge proof system is added.

## Security goals

The protocol is intended to provide these properties:

- The server never receives or stores the master passphrase or recovery phrase.
- Registration requires only a username and cryptographic account material.
- Authentication proves possession of the private key without transmitting it.
- A stolen server database does not directly reveal private keys or passphrases.
- Recovery is self-custodied; support staff cannot reset or reconstruct an account.
- Challenges are short-lived and single-use, preventing signature replay.
- Cryptographic versions and KDF parameters can be upgraded without changing a username.

This mechanism does not hide IP addresses, network metadata, device timing, or usage patterns. It is not a replacement for Tor, a VPN, or transport-layer security. All cloud traffic must still use HTTPS.

## Account material

Each account has the following material:

| Material | Created/stored | Purpose |
|---|---|---|
| Username | User and server | Unique pseudonymous account identifier |
| Master passphrase | User memory only | Unlocks the private account seed on a device |
| Account seed | Generated locally | Root secret used to reproduce the account signing key |
| Public key | Device and server | Verifies signed authentication challenges |
| Private key | Device only | Signs authentication challenges |
| Recovery phrase | User-controlled offline backup | Encodes the account seed for recovery |
| KDF salt and parameters | Device local storage | Derives a key-encryption key from the passphrase |
| Encrypted account seed | Device local storage | Allows normal login without exposing the seed |

The preferred signing algorithm is Ed25519. The preferred password KDF is Argon2id with versioned, device-appropriate memory and iteration parameters. PBKDF2-HMAC-SHA256 may be used only as a compatibility fallback with a high iteration count and explicit algorithm metadata.

## 1. Account creation (registration)

### 1.1 User input

The user chooses:

1. A unique username.
2. A strong master passphrase.

AirMesh does not request an email address, phone number, legal name, or social-login identity.

The username is normalized before registration. The server enforces uniqueness, but a username must not be treated as proof of a real-world identity.

### 1.2 Local key generation and protection

All secret processing happens on the device:

1. Generate a cryptographically random account seed using the operating system CSPRNG.
2. Derive the deterministic Ed25519 account key pair from that seed.
3. Generate a random KDF salt.
4. Run Argon2id over the master passphrase and salt to produce a key-encryption key.
5. Encrypt the account seed locally with an authenticated-encryption algorithm such as XChaCha20-Poly1305 or AES-256-GCM.
6. Store only the encrypted seed, KDF salt, KDF parameters, algorithm version, and AEAD nonce on the device.
7. Clear temporary passphrase, seed, and derived-key buffers as soon as practical.

Android Keystore should protect an additional device-bound wrapping key where available. The master passphrase remains necessary for portable account recovery and must never be written to logs, analytics, crash reports, saved UI state, or clipboard history.

### 1.3 Registration request

The app sends only non-secret account data to the server:

```json
{
  "username": "cipher-owl",
  "publicKey": "base64url-ed25519-public-key",
  "keyAlgorithm": "Ed25519",
  "protocolVersion": 1
}
```

The server stores:

- normalized username;
- public key and stable key identifier;
- algorithm and protocol version;
- creation timestamp and revocation state;
- rate-limit and abuse-prevention metadata that has been disclosed in the privacy policy.

The server does **not** store the master passphrase, account seed, private key, or recovery phrase.

### 1.4 Password-hash clarification

A conceptual version of this design may derive a salted password hash locally and send it with the public key. The normative AirMesh profile should not do that when public-key challenge authentication is sufficient. A server-side password verifier creates an additional offline-guessing target after a database breach and is unnecessary for proof of private-key possession.

If a local quick-check verifier is used to reject an incorrect passphrase before decrypting the account seed, it remains on the device and is domain-separated from every encryption or signing key.

## 2. Account recovery (backup)

### 2.1 Recovery phrase generation

During initial registration, the app encodes the random account seed as a checksummed recovery phrase using a reviewed word-list format. The phrase contains enough entropy to resist brute force; it is not generated from the username or master passphrase.

The app displays the phrase once and requires the user to confirm selected words before registration is considered complete. The user may save an encrypted backup file as an alternative, but the server never receives an unencrypted recovery secret.

### 2.2 Self-custody rules

The user is responsible for storing the recovery phrase offline. AirMesh support and server operators cannot:

- reveal the phrase;
- reset the master passphrase;
- reconstruct the private key;
- bypass signed challenge authentication.

The UI must clearly warn that losing every enrolled device and the recovery phrase permanently loses the account.

### 2.3 Recovery on a new device

To recover an account:

1. Enter the username and recovery phrase locally.
2. Validate the phrase checksum before making a network request.
3. Decode the account seed and regenerate the same Ed25519 key pair.
4. Compare the regenerated public-key identifier with the server's registered key.
5. Complete a signed challenge to prove possession of the recovered private key.
6. Choose a new local master passphrase.
7. Derive a new local key-encryption key and store a newly encrypted copy of the account seed.

No server-side password-reset link or recovery administrator is involved.

## 3. Authentication (login)

### 3.1 Local unlock

On a previously enrolled device:

1. The user enters the username and master passphrase.
2. The app loads the local KDF salt and versioned parameters.
3. The app derives the local key-encryption key.
4. The app decrypts the account seed and recreates the private/public key pair in memory.
5. AEAD authentication failure is reported as an invalid passphrase without revealing additional account state.

The app must rate-limit repeated local attempts and should use Android's secure lockout/biometric facilities only as an additional device-local control.

### 3.2 Challenge request

The client requests a challenge for the normalized username. A challenge contains at least:

```json
{
  "challengeId": "random-server-id",
  "nonce": "base64url-256-bit-random-value",
  "audience": "bitvoid-cloud-api",
  "issuedAt": 1795543200,
  "expiresAt": 1795543260,
  "protocolVersion": 1
}
```

The server stores the challenge as unused until it expires. Challenges must be generated with a CSPRNG and must not be reusable across usernames, services, or environments.

### 3.3 Signed response

The client signs a canonical, domain-separated transcript containing:

- protocol name and version;
- challenge ID and nonce;
- normalized username;
- server audience/origin;
- issued and expiry timestamps;
- optional device key identifier.

Example response:

```json
{
  "username": "cipher-owl",
  "challengeId": "random-server-id",
  "keyId": "sha256-public-key-id",
  "signature": "base64url-ed25519-signature",
  "protocolVersion": 1
}
```

The server atomically marks the challenge as consumed, loads the registered public key, verifies the signature and transcript, and rejects expired, altered, duplicated, or mismatched responses.

### 3.4 Session issuance

After successful verification, the server issues a short-lived access session. Refresh sessions, if supported, must be revocable, rotated on every use, and stored in Android encrypted storage. Session tokens must never be placed in URLs or application logs.

A successful login proves control of a registered key. It does not prove a legal identity, physical person, or unique human.

## 4. Key changes, additional devices, and revocation

Changing only the master passphrase re-encrypts the existing account seed locally and does not change the public identity key.

An additional device is enrolled by either:

- importing the recovery phrase and proving possession of the account key; or
- using an already authenticated device to authorize a new device public key through a signed enrollment statement.

If multiple device keys are allowed, the server stores each key separately with a key ID, enrollment timestamp, and revocation state. Removing a device revokes only that device key and its sessions. Rotating the root account key requires a signed transition statement from the current key or recovery through the self-custodied seed.

## 5. Server-side controls

The server must implement:

- username reservation and normalization rules;
- per-IP, per-username, and per-challenge rate limits;
- constant-time cryptographic comparisons where applicable;
- single-use, short-lived challenges;
- replay detection and atomic challenge consumption;
- TLS and strict origin/audience binding;
- session expiry, rotation, and revocation;
- cryptographic protocol versioning;
- generic authentication errors that do not disclose whether a username exists;
- audit events that omit passphrases, recovery phrases, private keys, signatures usable as bearer credentials, and full session tokens.

Abuse controls must not silently turn anonymous registration into mandatory real-world identity collection.

## 6. Client-side controls

The Android client must:

- use the operating system CSPRNG;
- keep secrets out of Compose saved state and process logs;
- use authenticated encryption for local key material;
- apply KDF domain separation and versioned parameters;
- avoid direct passphrase-to-signing-key derivation for normal device storage;
- redact secrets from crash and telemetry systems;
- clear sensitive buffers where the runtime permits;
- warn before screenshots, clipboard copies, or exports of recovery material;
- require explicit confirmation before replacing an existing local identity.

## 7. Threats and limitations

This design does not protect an unlocked device compromised by malware, accessibility abuse, keylogging, screen capture, or a hostile operating system. A weak master passphrase can still be guessed against a stolen encrypted local seed. A stolen recovery phrase grants account control.

Username availability, IP address, request timing, session metadata, and public-key records remain visible to the server. Anonymous account authentication is therefore pseudonymous authentication, not complete network anonymity.

## 8. Implementation and migration status

The current AirMesh Android app uses gateway-issued username identity tokens for local servers, while the deployed cloud service currently exposes a separate email/password authentication API. Neither path implements this document yet.

Adopting this protocol requires coordinated Android and cloud changes, including challenge endpoints, public-key account records, encrypted local seed storage, recovery UI, session-token handling, migration rules, and independent cryptographic review. Existing accounts must not be silently converted or have credentials discarded.
