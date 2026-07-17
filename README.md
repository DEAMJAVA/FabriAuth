# FabriAuth

A modern authentication mod for **offline-mode (cracked) Fabric servers**, built with security, flexibility, and compatibility in mind.

**FabriAuth** protects your server by requiring players to register and log in before they can interact with the world. It includes session management, premium account detection, a configurable limbo system, and integrations with popular server mods—all while remaining lightweight and easy to configure.

Whether you're running a public cracked server or a mixed premium/offline community, FabriAuth gives you the tools to keep accounts secure.

## Features

### 🔐 Secure Authentication

* Player registration and login
* Automatic login sessions with configurable timeout
* Optional IP-based session restoration

### 🌐 Premium & Cracked Support

Support both premium and offline-mode players.

* Automatic premium account detection
* Optional premium auto-login
* Players can switch between premium and cracked accounts (when enabled)

### 🌍 Limbo System

Keep unauthenticated players isolated until they log in.

* Send players to a dedicated limbo world
* Optional inventory clearing while in limbo
* Configurable limbo world

### 🚫 Prevent Unauthorized Actions

Before authentication, players can be prevented from:

* Moving
* Chatting
* Opening or interacting with inventories

This ensures players can't interact with the server until they've successfully authenticated.

### 💬 Fully Customizable Messages

Every player-facing message can be changed in the configuration, allowing you to match your server's style, language, or branding.

## Designed for Server Owners

FabriAuth aims to be a complete authentication solution without being complicated. Most features can be enabled, disabled, or fine-tuned through a single configuration file, letting you choose the level of security that fits your server.

Whether you need a simple `/register` and `/login` system or a more advanced setup with premium authentication, session restoration, and limbo support, FabriAuth has you covered.

## License

This project is licensed under the GNU General Public [License](LICENSE) v3.0 (GPL-3.0).
