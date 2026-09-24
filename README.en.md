# Trade Master

**[简体中文](README.md) ｜ [English](README.en.md)**

An automated **librarian enchanted-book trade rerolling pipeline** for Minecraft **26.2** (Fabric, **client-side only**): it summons a villager, waits for it to take the lectern, rerolls its profession until the trade you want shows up, locks the trade, (optionally) names the villager with an anvil, sends it away, and stores or recycles the books — every step configurable.

> **Development disclosure**: **all of this mod's code, config, language files and this document were produced and iteratively maintained by AI.** The human author defined the requirements, ruled on each design trade-off, and tested in-game. See [Development disclosure](#development-disclosure) at the end.

---

## Contents

[Overview](#overview) · [Requirements](#requirements) · [Installation](#installation) · [Quick start](#quick-start) · [The rig and coordinates](#the-rig-and-coordinates) · [Pipeline (12 phases)](#pipeline-12-phases) · [Reroll mechanisms](#reroll-mechanisms) · [Trade detection](#trade-detection) · [Matching and budget](#matching-and-budget) · [Locking](#locking) · [Naming](#naming) · [Storage](#storage) · [Arrival check and staging area](#arrival-check-and-staging-area) · [Special villagers](#special-villagers) · [Anti-stall and self-healing](#anti-stall-and-self-healing) · [Anti-cheat / legit mode](#anti-cheat--legit-mode) · [Settings reference](#settings-reference) · [Commands and hotkeys](#commands-and-hotkeys) · [Config entry points](#config-entry-points) · [Compatibility and risks](#compatibility-and-risks) · [Building](#building) · [License and attribution](#license-and-attribution) · [Development disclosure](#development-disclosure)

---

## Overview

Set up a lectern and two buttons (summon / send-away) and the mod will:

1. detect the enchanted-book trades a villager offers, using the **detection mode** you pick;
2. **lock** the trade when it matches your targets, optionally **name** the villager with an anvil to record the price, then send the villager away;
3. if nothing matches, **break and replace the lectern** over and over to reroll the profession until your enchantment shows up;
4. when the inventory fills up, **store the books in chests** or **grind them back into plain books**, with cursed books handled separately.

## Requirements

| Item | Value |
|---|---|
| Game version | Minecraft **26.2** |
| Loader | Fabric Loader **≥ 0.19.0** (built against 0.19.3) |
| Java | **≥ 25** |
| Side | **Client only** (`"environment": "client"`, no server installation) |
| Required | Fabric API (0.154.2+26.2), **maLiLib** (0.29.3) |
| Build | Gradle 9.6.1 + Fabric Loom **1.17.21** |
| Optional | Mod Menu (config screen entry), clientcommands (coexistence probe only, **no API coupling**) |
| License | **GPL-3.0** |

## Installation

1. Install Fabric Loader and put **Fabric API** and **maLiLib** into `mods/`;
2. Put `trade-master-1.0.jar` into `mods/`;
3. Launch the game.

## Quick start

1. **Build the rig**: lectern + summon button + send-away button (optional: chests / grindstone + anvil + name tags);
2. **Fill in the coordinates**: press `V` to open the main menu → "Settings" tab → click the row's `[pick in game]` suffix to arm the picker, then right-click the target block (or use maLiLib's `P` key);
3. **Pick targets**: on the "Enchanted books" tab, tick the enchantments you want and set **level** and **max price**;
4. **Start**: hotkey `R`, or `/trademaster start`.

## The rig and coordinates

| Coordinate | Purpose | Required | What clearing the field does |
|---|---|---|---|
| Lectern | The block broken/placed; also the anchor of the arrival check | Yes | **Not** clearable (guarded on purpose) |
| Summon button | Pressed to let the minecart grab a villager | Yes | Not clearable |
| Send-away button | Dispatches the current villager | Yes | Not clearable |
| Grindstone | Target of sand-wheel (grindstone) mode | No | **Clearing it disables sand-wheel mode** |
| Staging origin + extent | Two coordinates forming a box-shaped staging area (origin + signed extent, in blocks) | No | Clearing either one counts as "incomplete" |
| Staging sphere radius | Fallback when the region coordinates are incomplete: a sphere of this radius around the lectern | No | Default **8**, range 1–64 |
| Chests (multiple) | Tried in order when the inventory is full | No | Empty text does not clear; remove a row with the trailing `[remove]` |
| Curse drop yaw/pitch | Direction used to drop cursed books that no chest would take | No | Clearing it disables the direction (the live value is used at drop time) |

**Direction convention**: `yaw` −180 = north, −90 = east, 0 = south, 90 = west; `pitch` positive looks down (max ±90).
**Input behaviour**: coordinates accept relative `~` syntax (same semantics as target selectors); **every** text field commits only on focus loss or when the screen closes, so half-typed values never reach the config.

## Pipeline (12 phases)

| Phase | What it does |
|---|---|
| `IDLE` | Not running |
| `PRECHECK` | Global preconditions (coordinates / enabled targets / emeralds / books / lectern / axe); switches to storage when the inventory is full; enters waiting states as needed |
| `SUMMON` | Presses the summon button to bring a villager in |
| `AWAIT_VILLAGER` | Waits for the villager to arrive at the ray detection point; handles staging waits, baby freeze, nitwit/unusable dispatch |
| `ENSURE_LECTERN` | Verifies the lectern is present and re-places it from the offhand if missing (self-heal) |
| `WAIT_PROFESSION` | Waits for the villager to become a librarian (optional heal cap) |
| `CHECK_TRADE` | Interacts with the villager and arms the detection channel |
| `REROLL` | Miss: breaks the lectern to reroll the profession |
| `LOCK` | Buys the matched trade to lock it (including the unaffordable fallback) |
| `NAME` | Optional: anvil naming that records the price |
| `SEND_AWAY` | Presses the send-away button to dispatch the villager |
| `DEPOSIT` | Stores / recycles the enchanted books (chest mode or sand-wheel mode) |

## Reroll mechanisms

| Mechanism | How |
|---|---|
| **AXE** (default) | Repeatedly breaks the lectern with the main-hand axe and re-places it from the offhand; `preventAxeBreaking` refuses to keep breaking on low durability |
| **TASK_CHAIN** | Break lectern → wait until the villager is unemployed → place lectern → wait for it to become a librarian (strictly waits for the profession state changes) |

Both mechanisms **force the axe into the main hand** before breaking; placement and interaction are separated by configurable delays (`placeDelay` / `interactDelay`).

## Trade detection

| Mode | How it works |
|---|---|
| **PACKET** | Intercepts `ClientboundMerchantOffersPacket` and suppresses the merchant screen flash |
| **EVENT** | Container events (`initializeContents` + restock); delivers only once both packets arrived (mask == 3) |
| **BOTH** | Requires both channels and **cross-validates** the offer lists; a mismatch is treated as a miss and retried |

All three modes use a **3-second wall clock** and **containerId correlation** to reject offer packets that do not belong to the current interaction (a previous villager's late packet, a merchant screen someone else opened, …).

## Matching and budget

- Every **enabled** enchantment keeps a max-heap of acceptable prices (its "price slot").
- A single pass scans **every** offer — datapacks/mods can give one villager several enchanted-book trades, and one book can carry several enchantments.
- **Single-enchantment book**: price ≤ that enchantment's price slot.
- **One book with several enchantments, or several qualifying books on one villager**: the budget is the sum of the matched enchantments' price slots (capped at **64**), and only the **cheapest qualifying book** is bought — one purchase is enough to lock the villager, and the villager is what matters.
- **Reserve cheaper** (`reserveCheaperTraders`): leaves a "cheaper only" reservation slot behind after a match.
- **Hunt cheaper** (`huntCheaper`): after a lock, that enchantment only accepts strictly cheaper copies; on a multi-book villager **each qualifying book records its own price**. The configured `maxPrice` **caps** the hunt (lowering it mid-run applies at once, raising it never loosens the hunt) and the mark only ever moves down.
- **Mutual exclusion**: `huntCheaper` and `autoDisableAfterLock` are mutually exclusive — when both would be on, **auto-disable wins**; while running you may only **turn hunt off**, never on.
- **Emerald floor**: `emeraldFloor` = the largest `maxPrice` among enabled targets (at least `minLockEmeralds`); raised to `maxHuntBudget` when hunt is on; forced to ≥ **64** when "Multi-enchantment book safeguard" is on.

## Locking

- **Materials are re-checked** right before buying (emeralds and books); before buying, a **fingerprint** (slot, price, and the **book's full enchantment set**) must match the detection exactly — otherwise it is a drift and the run does a free reroll instead of buying the wrong book.
- **Retry cap**: `lockMaxRetries` (−1 = retry forever).
- **When the matched book is unaffordable**:
  - an affordable qualifying book exists → buy the cheapest one;
  - no qualifying book is affordable, but some other book is → **emergency-buy the cheapest affordable book** just to lock the villager (yellow chat note: "this is not one of your targets");
  - nothing at all is affordable → stop with an out-of-materials message; if a combo book caused it, an extra hint suggests enabling the 64 floor.
- **After a successful lock**: **all** matched targets of that villager count as obtained → (optional) auto-disable them in the config, (optional) record each price into its price slot, (optional) naming.

## Naming

- Needs an **anvil + name tag** (optional feature, off by default).
- Name format: `enchantment roman-numeral price` (e.g. `Sharpness V 12`); several enchantments on one book are joined with `+`; several books on one villager are listed in trade-slot order.
- Custom enchantment names are supported; **digits in names become superscripts** (⁰¹²³⁴⁵⁶⁷⁸⁹) and `+` becomes `*` (so it cannot collide with the separator).
- Names longer than the anvil's **50-character** limit → **naming is skipped** (the lock is unaffected).
- Missing name tag / no XP / rename failure → degrades to a plain **send-away**, never a failed lock.
- **Named-villager deduction**: on start/refresh the mod scans named librarians and deducts the enchantments they already provide.

## Storage

### Chest mode
- **Only enchanted books are deposited**, nothing else is ever moved;
- multiple chests are tried **in order**; when every chest is full the run stops;
- the offhand swap has a **configurable retry** (`offhandSwapRetries`, −1 = forever); when retries are exhausted it stops through the normal flow.

### Sand-wheel (grindstone) mode
- Entry conditions (**four elements**): ① `grindstoneEnabled` ② grindstone coordinates ③ drop yaw ④ drop pitch;
- Each book is pushed into the grindstone to be **disenchanted** back into a plain book (every step waits for the server's slot sync so lag cannot make it destroy the wrong item);
- **Cursed books** (vanilla curse tag, or a curse id you configured) can never be disenchanted → they **go to a chest first**, and when every chest refuses them they are **dropped** while facing the configured direction;
- Opening the grindstone has a configurable retry (`grindstoneOpenRetries`);
- **Changing the enable flag or the grindstone position while running stops the run** through the normal flow (with a message); **the drop direction may be edited at any time** and is used immediately.

## Arrival check and staging area

- **Arrival check**: a **single ray** — player → lectern → one more block along that ray; the villager must stand on that point (within ~1.5 blocks).
- **Staging area** (what the minecart can reach):
  - region coordinates **complete** → the box formed by "origin + extent";
  - region coordinates **incomplete** → a **sphere of radius `villagerSphereRadius` around the lectern** (default 8).
- **Start confirmation**: with incomplete region coordinates the start is **refused** and you are asked to type **`/trademaster goon`** to confirm the sphere fallback; **at any other time that command does nothing** (it only prints a notice). After confirming, the run starts automatically.
- **While running**: completing the region coordinates **switches back automatically** on the next scan; making them incomplete again **stops the run normally** with a message.

## Special villagers

| Case | Handling |
|---|---|
| Baby villager | Present at the **arrival point** or in the **staging area** → **freeze and wait** (no summoning, no dispatch, no picking) until no babies remain |
| Nitwit | In the staging area it does not block summoning; detected at the **arrival point** → dispatched immediately |
| Unemployed | Can take the librarian profession → normal flow |
| Any other profession | Will **never** switch at this lectern → dispatched |
| Already-named villager | Participates in the named-villager deduction |

## Anti-stall and self-healing

- **Lectern self-heal**: after the lectern is lost/broken it is re-placed from the offhand within a grace period (`lecternHealGrace`, default 6 s).
- **Ghost blocks**: when the client thinks the placement succeeded but the server never got it, the lectern is verified every tick and re-placed when missing.
- **Material precheck timing**: at start, on every `PRECHECK`, before dispatching a villager, **before every reroll**, and after lock retries are exhausted.
- **Full inventory**: storage is attempted first; with no chest (or all full) the run stops — books are never dropped on the ground.
- **Idle wait**: with no villager in the staging area the run simply waits; that is **not** a stop, and it resumes as soon as someone shows up.
- **Timeouts**: `timeoutEnabled` (default **off**) is the global watchdog; `phaseTimeoutsEnabled` (default **on**) bounds a single phase; the wait-for-villager phase is exempt.

## Anti-cheat / legit mode

| Setting | Default | Notes |
|---|---|---|
| `legitMode` | on | Human-like interaction: smooth rotation, no wasted swings |
| `slowMode` | off | Slower pacing (turning it on enables `legitMode`) |
| `preventAxeBreaking` | on | Refuses to keep breaking when the axe durability is too low |
| `stopOnPlayerMove` | on | Stops when the player moves (the mod's own teleports are exempt for a short window) |
| `placeDelay` / `interactDelay` | 3 / 2 | Delay between placement and interaction (ticks) |
| `tpToVillager` | off | Teleports to the villager before interacting (**can be detected by anti-cheat**) |
| `tpToItem` | off | Teleports to the lectern drops after breaking (**same risk**) |

## Settings reference

In the order of the in-game screen (all three entry points share one config):

| Group | Setting | Default | Notes |
|---|---|---|---|
| Mechanism | Reroll mechanism / detection mode | AXE / PACKET | AXE, TASK_CHAIN / PACKET, EVENT, BOTH |
| Mechanism | Reserve cheaper | off | Leaves a "cheaper only" reservation slot |
| Mechanism | Min lock emeralds | 1 | 1–64 |
| Mechanism | Multi-enchantment book safeguard | off | Forces the floor to ≥ 64 (for datapacks that change the librarian profession) |
| Mechanism | Global timeout / duration | off / 1h | Accepts `1h`, `30min`, `1m`, `90s`; a bare number means ticks |
| Mechanism | Phase timeouts | on | Per-phase watchdog |
| Mechanism | Villager check interval | 1s | 1–72000 ticks |
| Mechanism | Lectern heal grace | 6s | 20–1200 ticks |
| Mechanism | Profession heal cap / count | off / 3 | 1–20 |
| Mechanism | Auto-disable after lock | off | Mutually exclusive with "hunt cheaper" (it wins) |
| Mechanism | Hunt cheaper | off | Can only be turned off while running |
| Mechanism | Lock retry cap | −1 | −1 = forever, ≤ 999 |
| Mechanism | Offhand swap retries | 3 | −1 = forever, ≤ 999 |
| Mechanism | Post-dispatch break retry limit | 2 | How often the lectern break is retried after a villager was dispatched before the run stops with an error; −1 = forever, ≤ 999 |
| Mechanism | Sand-wheel (grindstone) mode | on | Only used when all four elements are set (see "Storage") |
| Mechanism | Grindstone open retries | 1 | −1 = forever, ≤ 999 |
| Mechanism | Custom curse enchantment ids | empty | Comma-separated; vanilla `minecraft:` ids are rejected (vanilla curses are tag-detected) |
| Display | Show roll count | on | HUD shows the current phase and the roll count |
| Display | Debug log | off | Written to the log file |
| Naming | Enable naming / force price 1 | off / off | — |
| Anti-cheat | see the table above | — | — |
| Positions | see "The rig and coordinates" | — | — |
| Chests | dynamic list | empty | add/remove rows |
| Hotkeys | start/stop, pick coordinate | — | **editable only inside maLiLib** |

## Commands and hotkeys

| Input | Effect |
|---|---|
| `/trademaster` (alias `/tm`) | bare command = toggle start/stop |
| `/trademaster start` / `stop` / `status` | start / stop / show status |
| `/trademaster goon` | **only** valid after a start was refused for incomplete staging-region coordinates: confirms the lectern-sphere fallback and starts; does nothing otherwise |
| Hotkey `V` | Opens the main menu (four tabs: enchanted books / settings / keybinds / **aliases**) |
| maLiLib hotkeys `R` / `P` | start-stop / capture the looked-at block for the pending coordinate slot |

## Config entry points

All three share the **same** `config/trade-master.json`:

1. the in-game main menu (hotkey `V`, touch-screen friendly) — four tabs: **enchanted books / settings / keybinds / aliases**;
2. **Mod Menu** (provides the config-screen entry);
3. the **maLiLib** config GUI (the hotkey page lives only here).

> The **Aliases** tab lets you give any enchantment a custom display name (the name used when anvil-naming a villager). Blank = default name; the name **must not duplicate another enchantment's alias or name** (a duplicate turns the row red and is not saved). It only changes the display name, never any trade condition.

On first launch, when this mod's config does not exist yet, the **enchantment table** and **four of the anti-cheat flags** (`preventAxeBreaking`, `tpToVillager`, `legitMode`, `slowMode`) are migrated from the original **LibrarianTradeFinder** `librarian-trade-finder.json`; the remaining flags (`tpToItem`, `stopOnPlayerMove`, `placeDelay`, `interactDelay`) are not migrated and keep this mod's defaults.

## Compatibility and risks

- **Do not** run this alongside LibrarianTradeFinder: both intercept the same merchant packets and will fight each other.
- clientcommands: only a "is it loaded" probe and a coexistence log line; **its API is not used**.
- `tpToVillager` / `tpToItem` can be detected on anti-cheat enabled servers; both default to **off**.
- This is a **client** mod designed for your own rig / single-player setup.

## Building

```bash
# requires JDK 25
./gradlew build
# output: build/libs/trade-master-1.0.jar
```

## License and attribution

### This project

**GNU General Public License v3.0** (full text in [`LICENSE`](LICENSE)). You may use, modify and redistribute it freely, but **redistribution must keep the attribution and the license, and must provide the corresponding source**.

### Author's statement (not a license term)

This project is **intended solely for the author's personal use and open-source sharing**; the author does not and will not engage in any commercial activity with it.

> ⚠️ Note: this is a statement of the author's **intent**, **not an additional license restriction**. The project is released under GPL-3.0 and derives from projects licensed GPL-3.0 / LGPL-3.0; GPL-3.0 forbids adding further restrictions — so **anyone may still use, modify and redistribute it (including commercially) under GPL-3.0**, provided the attribution and license are kept, modifications are marked, and the corresponding source is provided.

### Ported from (license-compatible, and modified)

- **[LibrarianTradeFinder](https://github.com/Greenman999/librariantradefinder)** — GPL-3.0, © Greenman999
  Ported parts: axe break / offhand place reroll, packet interception, offhand lectern restock (`LecternRestock`), smooth rotation (`SmoothRotation`), anti-cheat delays and legit-mode switches.
- **LPCTools tradeReroller** — LGPL-3.0, © Fymasa
  Ported parts: dual-event trade detection, task-chain reroll (`TaskChainRerollStrategy`), the trade lock sequence, max-heap price matching (`TradeMatcher`), named-villager deduction, anvil naming (`NamingExecutor`).
  > LPCTools' `InGameFuture` task framework was **not** carried over; it was replaced by a small internal `StepRunner`.

**The full LGPL-3.0 text is shipped as [`LICENSE-LGPL-3.0.txt`](LICENSE-LGPL-3.0.txt)** (required by the LPCTools-derived parts). The project as a whole is distributed under GPL-3.0: LPCTools' license (LGPLv3) explicitly permits incorporating LGPL code into, and converting it to, GPLv3, provided the original copyright notices and license texts are preserved — this repository keeps both.

**Every derived part has been modified** (rewritten, renamed, split up or re-implemented); none of it is a verbatim redistribution of the original projects, and those projects do not endorse or maintain this mod.

### Dependencies / coexistence only — no code reused

| Project | License | Relationship |
|---|---|---|
| Fabric API | Apache-2.0 | Required |
| maLiLib | see its project | Required (config GUI) |
| Mod Menu | MIT | Optional (config-screen entry) |
| clientcommands | LGPL-3.0-or-later | Optional (**coexistence probe only**) |

> Those licenses apply to those projects; this mod **does not copy** their code and **does not bundle** their jars.

## Development disclosure

**Every part of this mod (`TradeMaster`) — code, config, language files and documentation — was developed by AI:**

- **How it was produced**: in an AI coding-assistant environment, the AI wrote, changed, audited and refactored the project round by round from the human author's requirements; **Qwen3.8-Max** was used early on and **DeepSeek** models later.
- **The human author's role**: stating requirements, **ruling on each design trade-off** (for example whether an "emergency purchase" behaviour should be kept when the matched book is unaffordable), play-testing and reporting bugs, and deciding to publish.
- As a result there is **no human hand-written code** in this repository. If you find a design flaw, please open an issue / PR the normal open-source way.

> AI generation does not change the project's open-source licensing: AI output is treated as the tool-produced work of the human author, and the repository owner carries the copyright and license obligations.

---

**Author**: mknbt ｜ **Version**: 1.0 ｜ **License**: GPL-3.0
