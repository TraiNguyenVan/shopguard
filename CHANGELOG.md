# Changelog

## Unreleased — An undecided player is an Anarchist

EconomyCraft's default party is Anarchism, so a player who has never run `/eco party` already has that id.
Since 0.11.0 the faction rules took the **record** as well, so such a player could claim, be trusted, receive
transfers and keep land while paying full tax and showing no party tag. They now get the whole of Anarchism:
exempt from every tax scope, tagged `Ⓐ`, and under its land restrictions.

### Changed
- `ClaimPermissions` keys on the party id alone again. `mustReleaseLand` no longer takes a `hasChosen`
  argument, and the three permissive two-argument overloads are gone — with no gate, there was nothing for
  them to decide.
- **`PlayerFactions.Backend` loses `hasChosen`.** It had no other caller.
- A player who has chosen nothing is refused a new claim, refused a transfer in, refused a trust entry, and
  has land they already hold released through the ordinary `/claim remove` refund path.

That last one is the only rule here that moves money, and it is worth reading twice: **a claim held by a
player who never chose a party is released and refunded.** It is correct under the rule — they cannot claim,
so land they hold is a contradiction — but it is not free, and it now applies to a much larger set of
players than it did. Watch the first live-fire.

Choosing a party lifts all of it.

### Unchanged, deliberately
- A server with no EconomyCraft installed is still unrestricted. `null` means "no party system", which is a
  genuinely different situation from "the default party" and still answers permissively.
- An unknown party id is still permissive, so a typo or a party from a newer EconomyCraft cannot lock every
  player out of claiming.

## 0.11.0
- **New: priced land claims, tied to the server's inflation.** New claim area costs money, and the price
  moves with the money supply automatically. **Disabled by default** — `claimCostEnabled` and
  `claimRefundEnabled` both ship `false`, so the jar is inert until you turn them on.
  - `cost = claimCostBaseFee + ceil(newBlocks x claimCostPerBlock x f)`, where
    `f = clamp(inflationMultiplier / claimCostReferenceMultiplier, claimCostMinFactor, claimCostMaxFactor)`
    and `inflationMultiplier` comes from EconomyCraft's dynamic price engine (median active balance over
    `startingBalance`, refreshed hourly). At today's 7.36x reading against the default 7.36 reference,
    `f = 1.0` and a block costs 2.5.
  - **`f` is compressed and anchored, not the raw multiplier.** The raw signal spans 0.5x..100x, which at
    any sane rate would price poorer players out of land they already hold within a few months. Anchored
    to a reference it moves proportionally instead, and the clamps keep a collapse from making land free
    and runaway inflation from making it unclaimable.
  - **Only new area is billed.** Touching claims merge, so selecting a rectangle that overlaps what you
    already hold costs nothing — expanding a claim never re-charges the region.
  - **New: `/claim remove` refunds.** You get back `claimRefundFeeRate`'s complement of what the claim
    actually cost (default: half), drawn from a per-player daily allowance set by the new
    `claimRefundDailyLimit` (default 2500). A release too large for today's allowance is **refused**, not
    partly paid — the economy has no negative balances and no escrow, so carve the claim down or come
    back tomorrow.
  - **The refund pace is ShopGuard's own key, not EconomyCraft's `dailySellLimit`.** That flag is the
    ceiling on residual `/sell` proceeds and nothing else; reading it as a general money-creation budget
    would have coupled two unrelated policies. The defaults match so the pace is familiar, but they are
    independent — re-check `claimRefundDailyLimit` if you retune `dailySellLimit`.
  - **Refunds use the price you paid, not today's price.** Re-deriving it from the live factor would let
    you buy land cheap in a deflationary stretch and cash out in an inflationary one.
  - **Carving pays nothing but reduces the claim's recorded cost** in proportion to the area removed —
    otherwise you could claim a large area, carve it to one block, and release it for the full amount.
  - Carving a claim to nothing is a release, so it can be refused; if so the carve is rolled back and you
    keep both the land and the money.
  - Money movement is logged as `shopguard:claimcost` and `shopguard:claimrefund`. Merging claims sums
    their recorded cost, so a merge never destroys refundable value.
  - **New: every constant is a config key** in `shopguard.json` — nothing in the derivation is hardcoded.
    Bare `/claim` prints the rate, the factor, the inflation reading against the reference, what your
    balance buys, and your remaining refund allowance; each claim shows paid/refunded/returnable. Setting
    the first shovel corner states the current rate.
  - **Requires two EconomyCraft API additions** (`inflationMultiplier`, `medianActiveBalance`).
    EconomyCraft is a *soft* dependency: without it ShopGuard still works and claims are simply free. A
    gradle `verifyEconomycraftJar` task fails the build if `libs/` holds a jar that predates them.
  - 35 tests over the pricing arithmetic and the refund ledger.

## 0.10.0
- **New: `/claim transfer <player>`.** Hand a claim to another player without losing the shape or the
  trust list — the old "delete the claim, have them re-claim it with the shovel" dance is gone.
  - Dry run first: `/claim transfer <player>` reports the claim and its size and changes nothing;
    `/claim transfer <player> confirm` performs it. Owner only, ops can transfer anyone's.
  - **Clean handover by default:** the seller loses access to the claim, like any other land-claim
    mod. New config `keepOldOwnerTrusted: true` leaves them on the trust list instead, so nobody is
    locked out of a shop they just gave away. The dry run tells you which way it's set.
  - The recipient's `maxTotalPerPlayer` limit is enforced (ops exempt), and they get a chat notice.

## 0.9.0
- **Minecraft 26.3 build.** ShopGuard now ships for 26.3 (Fabric Loader 0.19.3 → 0.19.5, Fabric API
  0.152.1+26.2 → 0.160.5+26.3). The 26.2 build carries on alongside it. No behaviour changes.

## 0.8.1
- **Fixed: non-owners could place liquids in a claim.** Bucket use now checks where the liquid actually
  lands, so pouring water/lava across a claim border is blocked (the old check only looked at the
  clicked block).
- **Fluids can't flow across a claim border** — water/lava spreading from outside stops at the edge
  (and can't leak out of a claim either); flows normally within a claim or in unclaimed land.
- **Admins have no claim-size limit** — ops are now exempt from the per-claim cap too (not just the
  total), so they can claim as large an area as they want. `/claim` shows admins "no limit".

## 0.8.0
- **Phase 3 grief protection.**
  - **Explosions can't destroy claimed blocks** — TNT, creepers, ghasts, end crystals, wither blasts,
    beds/respawn anchors. The blast still hurts mobs and breaks unclaimed blocks nearby; it just can't
    chew into a claim (this also stops a creeper's block griefing).
  - **Fire can't consume claimed blocks** — it can flicker up to a claim's edge but not eat into it.
  - (Pistons already couldn't push/pull across a claim border, since 0.5.0.)
- Known minor gaps left for a later pass: endermen stealing blocks, and fluid (lava/water) flowing
  across a claim border.

## 0.7.2
- **Fixed a single right-click instantly creating a one-block claim.** One physical click can reach
  the server as two interaction packets (block-use + item-use); the second was being read as the
  second corner. Tool clicks are now debounced — the first click sets a corner and waits, as intended.
  (Same fix applied to the admin zone hoe.)
- **Claim numbers are now per-player** — your claims are #1, #2, … regardless of anyone else's — and
  **admin zones number independently** on their own sequence. Existing saves migrate automatically.

## 0.7.1
- **Slimmer commands.** Players now see just three options under `/claim`:
  - bare **`/claim`** shows everything (claim here + your claims + used/max totals — replaces
    `info` and `list`),
  - **`/claim show`** toggles borders (replaces `/claim zones`),
  - **`/claim trust <player>`** now toggles (replaces `untrust`),
  - `/claim remove` unchanged; `/claim admin remove` dropped (ops can already `/claim remove`
    anyone's claim). Ops keep `/claim zone add|list|remove`.
- Internal cleanup: removed dead code left behind by earlier reworks; the claim/zone tools forget
  your pending corner and mode when you log out; README brought up to date.

## 0.7.0
- **The admin zone tool (golden hoe) now works exactly like the claim shovel, but for zones.**
  Right-click two corners to **add** area; right-click the **sky** to toggle ADD ↔ CARVE mode and
  carve pieces back out — zones can now be any shape, not just rectangles. Touching zones merge into
  one; carving a zone to nothing deletes it.
- **Zone borders show automatically while an op holds the hoe** (the admin counterpart of the shovel
  showing claim outlines). `/claim zones` remains the way players see where they may build.
- Existing rectangular zones from older versions load unchanged.

## 0.6.3
- **Fixed the shovel toggling mode when aiming at distant blocks.** Vanilla treats any right-click past
  ~4.5 blocks as an "air click"; the tool now raycasts your view up to 64 blocks — a block in your
  crosshair sets that corner (so you can set corners from across the plot), and the CLAIM/CARVE toggle
  only fires when you're genuinely aiming at the sky.
- The admin golden hoe gets the same long-range corner targeting (sky-aim does nothing for it).

## 0.6.2
- Admin-zone borders are now **dark red** (dust particles) instead of white — clearly distinct from
  the green (yours) / orange (others') claim outlines.

## 0.6.1
- **`/claim zones` now shows everything around you**, not just the admin zones: every claim's outline
  too — **green** for your claims, **orange** for other players' — so you can see exactly what land is
  taken before trying to claim. Still no tool needed, still resets on relog. (Holding the shovel shows
  claim outlines as before, with the same colors.)

## 0.6.0
- **`/claim zones`** (any player): toggle admin-zone borders on/off — white end-rod particles trace the
  build-area perimeter so players can see exactly where they're allowed to claim, no tool needed.
  Turns itself off on logout. (Zone borders look distinct from the green claim outlines.)

## 0.5.1
- The shovel **mode toggle** and **"first corner set"** prompts now show above the hotbar (action bar,
  fades after a moment) instead of filling chat. Results (created/updated/carved/removed) and errors
  stay in chat.

## 0.5.0
- **Touching claims merge into one.** Adding a rectangle that touches any of your own claims fuses them
  into a single claim — extending no longer piles up separate claims, and removing one removes the whole
  thing. Non-touching claims stay separate, so multiple shops still work.
- **The shovel no longer blocks breaking.** Carve moved off left-click: **right-click the air** to
  toggle CLAIM ↔ CARVE mode, then right-click two corners. You and trusted players can break blocks
  with the shovel again.
- **Redstone control policy.** By default non-owners can't use buttons/levers in a claim (doors, gates,
  trapdoors, and pressure plates still work for walking through). Set `allowRedstoneControls: true` in
  `config/shopguard.json` to allow them.
- **Piston protection.** Pistons can't push or pull blocks across a claim boundary.
- **Zone command uses block-position corners:** `/claim zone add <corner1> <corner2>` with crosshair
  tab-completion and `~ ~ ~` support; zone IDs also tab-complete for `zone remove`.
- **Admin zone tool:** ops can hold a **golden hoe** and right-click two corners to define a claim zone.

## 0.4.1
- Claim outlines now refresh **immediately** after you add, carve, or remove a claim, instead of
  waiting up to ~0.75s for the next pulse.

## 0.4.0
- Claim outlines now only show while you're **holding the golden shovel** (no more always-on clutter).
- **Adjacent claims of the same owner merge** into one outline — the shared border between them isn't
  drawn, so they read as attached.
- **Carve by digging:** left-click two corners with the shovel to remove a rectangle from your claim
  (replaces the fiddly sneak + right-click). While it's the claim tool the golden shovel no longer digs
  real blocks — use another tool for that.

## 0.3.0
- **Claim outlines.** Claim boundaries are traced with particles for nearby players, server-side, so
  it works on any client without a client mod. (A client-rendered glowing outline was attempted first
  but deferred — Minecraft 26.2's reworked render pipeline makes it impractical to build without live
  iteration; the particle outline is a reliable stand-in.)

## 0.2.0
- **Phase 1 core.** Claim land with a golden shovel (right-click two corners to add a rectangle,
  sneak + right-click to carve to any shape); claims are protected from block break/place and
  container theft by non-owners, while doors/gates/buttons/levers/pressure plates and shop (villager)
  trading stay open so a claimed area is still shoppable.
- Admin **claim zones** gate where claiming is allowed (`/claim zone add|list|remove`, ops); with no
  zones set, players may claim anywhere.
- Configurable limits in `config/shopguard.json` (max area per claim, max total per player), plus
  no-overlap enforcement.
- Commands: `/claim info|list|remove|trust|untrust`, `/claim admin remove`.
- Not yet runtime-tested; client-rendered claim outlines and grief-hardening (explosions, mob
  griefing, fire/fluid) are still to come.

## 0.1.0
- Project scaffold: Fabric 26.2 land-claim grief-protection mod (ShopGuard).
