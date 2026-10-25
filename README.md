# LogonMC

A (mostly) vive-coded Login plugin for Velocity proxies (Minecraft 26.3, Java 25).

- **Premium linking**: everyone registers with a password; players who own their Mojang account can link it with `/premium` and log in automatically from then on.
- **Sessions**: after a password login, rejoining from the same IP within the session window skips the prompt.
- **Name validation**: names must match a pattern, and a name can't be used with different capitalization than its owner (`steve` is refused when `Steve` exists). Premium names can optionally be reserved for their Mojang owners.
- **Premium data migration**: linking keeps the account's data (optionally switching it to the Mojang UUID), and linked accounts follow Mojang name changes.
- **Limbo routing**: unauthenticated players wait on an auth server (e.g. NanoLimbo) and are sent on after login.
- **API** for other Velocity plugins.
- **Databases**: H2 (default), SQLite, MySQL/MariaDB.

## Building

```sh
./gradlew build
```

- Plugin: `plugin/build/libs/LogonMC-<version>.jar`
- API: `api/build/libs/logonmc-api-<version>.jar`

Gradle downloads a JDK 25 automatically if one isn't installed.

## Setup

1. Drop the jar into Velocity's `plugins/` folder and start the proxy once to generate `plugins/logonmc/config.yml` and `messages.yml`.
2. Add an auth server to `velocity.toml` (a NanoLimbo instance works well) and set `servers.auth` in `config.yml` to its name. Put your lobby servers in `servers.lobby`.
3. In `velocity.toml`:
   - `online-mode = true`. LogonMC switches each connection to online or offline mode itself.
   - `force-key-authentication = false`, so cracked clients can join.
   - `player-info-forwarding-mode = "modern"`, with the forwarding secret set on every backend.
4. Backends: `online-mode=false`, with Velocity forwarding enabled. Leave the auth server out of the `try` list so nobody lands there after logging in.
5. Grant `logonmc.admin` to staff, or only the subcommands they need (see [Permissions](#permissions)).

If LogonMC can't start (for example, a bad database config), it refuses every login instead of letting players in unauthenticated.

## How logins work

Every account starts as a **cracked** account: created with `/register`, opened with its password. A cracked account **owns its name**: nobody else, premium or not, can join with it.

A player who owns the Mojang account of their name can **link** their account with `/premium <password>`. A linked account joins through Mojang authentication, logs in without a password, and keeps working when its owner changes their Mojang name. It's identified by its Mojang UUID and doesn't own a name, so a name it used before can be registered by someone else.

LogonMC never renames an account to settle a conflict. The joining player is refused instead.

| Join | What happens |
|---|---|
| Name fails `names.pattern` | Refused |
| A cracked account owns the name | Password login (`/login`), or session. Wrong capitalization is refused with the correct one. A premium player joining with that name is treated the same way: they need that account's password. |
| The name is the Mojang name of a linked account | Mojang authentication, logged in automatically. This also covers a linked player who changed their Mojang name. Cracked clients fail and are kicked. |
| A premium name no account here has claimed | `protect-premium-names: true` (default): Mojang authentication, then `/register`, so only the Mojang owner can claim the name. `false`: joins cracked; the first player to `/register` gets it. |
| Any other name | `/register` |

### Linking

- **`/premium <password>`**: the player is disconnected and their next join must pass Mojang authentication. If it does, the account is linked and keeps its profile UUID, so all data on backend servers stays attached. If the client isn't premium, that join fails and the account stays cracked.
  - It's refused if no Mojang account has exactly that name, or if that Mojang account is already linked to another account here.
- **`premium.switch-uuid-on-premium: true`**: on linking, the profile UUID also switches to the Mojang UUID, and `PremiumMigrationEvent` fires so plugins can move their data. A plugin can cancel the event to keep the old UUID.
- **Name changes**: a linked player who joins with a new Mojang name keeps their account and UUID, and `PremiumNameChangeEvent` fires. If a cracked account here owns the new name, they're refused until they pick another name.
- **`premium.suggest-premium: true`**: after a cracked account logs in, if its name belongs to a Mojang account that isn't linked yet, the player is told that `/premium <password>` turns on automatic login.
- **`/cracked`**: unlinks the account so it goes back to password login. It's refused if a cracked account has registered that name in the meantime.
- Premium players who never link are ordinary cracked accounts, even if their name is premium.

## Commands

| Command | Description |
|---|---|
| `/register <password> <password>` (`/reg`) | Create an account |
| `/login <password>` (`/l`, `/log`) | Log in |
| `/changepassword <old> <new>` | Change your password |
| `/logout` | End your session and go back to the auth server |
| `/premium <password>` | Link your account to your Mojang account for automatic login |
| `/cracked` | Unlink and go back to password login |
| `/unregister <password>` | Delete your account |
| `/logonmc reload\|info\|forcelogin\|unregister\|setpassword\|premium\|cracked` | Admin (see [Permissions](#permissions)) |

Admin commands that take an account accept a name or a UUID (profile or Mojang UUID, with or without dashes). Several accounts can share a name: a linked player keeps the name they last joined with until they rejoin, even after someone else has registered it. When a name matches more than one account, the cracked owner is picked, and the reply lists the other accounts' UUIDs so you can pick one of those instead.

Unauthenticated players can only run the commands in `auth.allowed-commands`, and can't leave the auth server.

### Permissions

Player commands need no permission. Each admin subcommand has its own:

| Permission | Allows |
|---|---|
| `logonmc.admin` | Every subcommand below |
| `logonmc.admin.reload` | `/logonmc reload` |
| `logonmc.admin.info` | `/logonmc info <name\|uuid>` |
| `logonmc.admin.forcelogin` | `/logonmc forcelogin <player>` |
| `logonmc.admin.unregister` | `/logonmc unregister <name\|uuid>` |
| `logonmc.admin.setpassword` | `/logonmc setpassword <name\|uuid> <password>` |
| `logonmc.admin.premium` | `/logonmc premium <name\|uuid>` |
| `logonmc.admin.cracked` | `/logonmc cracked <name\|uuid>` |

`/logonmc` is visible to anyone with at least one of these, and tab completion only offers the subcommands they're allowed to use. Like every command, they only work after the staff member has logged in.

## API

```kotlin
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") // velocity-api
}
dependencies { compileOnly("dev.lndf:logonmc-api:1.0.0") }
```

```java
@Plugin(id = "myplugin", dependencies = @Dependency(id = "logonmc"))
public class MyPlugin {

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        LogonMC logon = LogonMCProvider.get();
    }

    @Subscribe
    public void onAuth(PlayerAuthenticatedEvent event) {
        // event.getReason(): LOGIN, REGISTER, PREMIUM, SESSION or API
    }

    @Subscribe
    public void onMigrate(PremiumMigrationEvent event) {
        // Move data from event.getOldUuid() to event.getNewUuid().
    }
}
```

`LogonMC` methods: `isAuthenticated`, `isLinked`, `isRegistered` (in memory, for online players); `getUser` by UUID/name/premium UUID, `getUsers` (every account last seen with a name), `forceLogin`, `forceLogout`, `checkPassword`, `setPassword`, `unregister`, `invalidateSession` (async, return `CompletableFuture`).

Events: `PlayerAuthenticatedEvent`, `PlayerRegisterEvent` (cancellable), `PlayerLogoutEvent`, `PreAuthServerConnectEvent` (change where the player goes after login), `PremiumMigrationEvent` (cancellable), `PremiumNameChangeEvent`.

## Passwords

New passwords are hashed with Argon2id (OWASP parameters) by default, or BCrypt (`auth.hash-algorithm`). Hashes made with the other algorithm, or with weaker parameters, are upgraded on the next successful login.

### Unsafe passwords

`/register` and `/changepassword` refuse passwords listed in the `.txt` files of `plugins/logonmc/unsafe_lists/`, one password per line. On first start the folder is created with two lists: `common.txt`, about 100 common and Minecraft-themed passwords, and `seclists-10k-most-common.txt`, the 10,000 most common passwords from [SecLists](https://github.com/danielmiessler/SecLists) (MIT License, see [`SECLISTS-LICENSE`](SECLISTS-LICENSE)). Delete either one, or add your own files and leaked-password lists (any encoding works). By default the check ignores capitalization (`auth.unsafe-passwords.case-sensitive`). `/logonmc reload` rereads the folder.

Each entry takes about 8 bytes of memory once loaded, so a list of 10 million passwords needs about 80 MB, plus about as much again briefly while loading. Existing passwords aren't affected until they're changed.

## License

LogonMC and its API are licensed under the [GNU LGPL v3.0](COPYING.LESSER), which builds on the [GNU GPL v3.0](COPYING). The bundled `seclists-10k-most-common.txt` comes from SecLists under the MIT License ([notice](SECLISTS-LICENSE)). Plugins can use the API under any license. Modified versions of LogonMC or the API must be released under the LGPL.
