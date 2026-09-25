# Security and Privacy Notes

- v0.1 never requests WhatsApp credentials.
- v0.1 never reads WhatsApp's private files or database.
- v0.1 only sees notification content after the user grants Android Notification Access.
- Telegram credentials are not implemented in v0.1.
- Cached content stays inside the app-private Android SQLite database.
- Android backup is disabled for the app in v0.1 to avoid copying cached message content into cloud/device backups by default.
- The future glasses protocol must authenticate the paired glasses session before allowing read or send actions.
- AIUI will receive constrained Social Hub capabilities rather than direct account credentials.
