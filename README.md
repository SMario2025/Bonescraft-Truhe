# BonesChestProtect

**Funktionen**
- Truhen/Barrels werden beim Platzieren geschützt.
- Owner + Trusted dürfen ganz normal rein (nehmen/legen).
- Andere dürfen öffnen, aber **nur ansehen** (keine Items nehmen/legen).
- Abbau ist für Nicht-Owner/Untrusted gesperrt.
- **Hopper/Trichter & Automationen funktionieren weiterhin** (Inventar-Transfers werden nicht blockiert).
- OP/Admin hat immer Vollzugriff.

## Commands
- `/bcp info` (schau auf eine Truhe)
- `/bcp trust <Spielername>` (schau auf eine Truhe)
- `/bcp untrust <Spielername>`
- `/bcp claim` (schützt eine bestehende Truhe, wenn noch nicht geschützt)
- `/bcp reload`

## Permissions (LuckPerms)
- `boneschestprotect.openall` – kann alle geschützten Truhen öffnen (view-only wenn nicht take)
- `boneschestprotect.takeall` – kann aus allen Truhen nehmen
- `boneschestprotect.admin` – alles (inkl. abbauen)
