# Security policy

## Supported versions

Only the latest public release receives security fixes. The initial supported release is 1.0.0.

## Reporting a vulnerability

Do not open a public issue for a vulnerability, leaked credential, authentication bypass, unsafe command execution, or data-loss scenario. Use GitHub's private vulnerability reporting feature after it has been enabled under **Settings → Security → Code security and analysis**.

Include the affected version, deployment assumptions, reproduction steps, impact, and any suggested mitigation. Remove real passwords, player data, server addresses, and tokens from logs before attaching them.

## Deployment boundary

The embedded editor is designed to bind to `127.0.0.1`. Do not expose it directly to the public internet. Keep the generated administrator password private, retain the BCrypt hash only in configuration, and back up plugin data before upgrades.
