# ShopGuard

A land-claim / grief-protection mod for Fabric (Minecraft 26.3). Fully server-side — vanilla
clients are protected and see everything (outlines are particles, not client rendering).

## Claiming (golden shovel)
- **Right-click two corners** → claim that rectangle. Touching claims you own merge into one, so you
  can build up any shape. Corners can be set from up to 64 blocks away by aiming.
- **Right-click the sky** → toggle CLAIM ↔ CARVE mode. In carve mode the two corners **remove** area
  instead — shape the claim to fit your build; carving a claim to nothing deletes it.
- Hold the shovel to see outlines: **green** = your claims, **orange** = other players'.

## Protection
Inside a claim, non-owners can't break or place blocks, open containers, or touch item frames and
armor stands — but they can still walk through (doors, gates, trapdoors, pressure plates) and trade
with villager shops, so a protected shopping district stays fully shoppable. Buttons and levers are
blocked for visitors by default (`allowRedstoneControls` in the config). Pistons can't move blocks
across a claim border. Ops bypass all protection.

## Commands
| Command | Who | Does |
|---|---|---|
| `/claim` | anyone | The claim you're standing in, your claims, and your used/max totals |
| `/claim show` | anyone | Toggle borders: zones dark red, your claims green, others orange |
| `/claim remove` | owner (ops: anyone's) | Remove the claim you're standing in — refunds what it cost (see below) |
| `/claim trust <player>` | owner | Toggle a player's build access on this claim |
| `/claim transfer <player> confirm` | owner (ops: anyone's) | Hand this claim to another player |
| `/claim zone add|list|remove` | ops | Manage claim zones by command (tab-completes coordinates) |

## Selling / handing over a claim
`/claim transfer <player>` **describes** the transfer first (size, who's getting it, what happens to
you) and changes nothing; re-run it as `/claim transfer <player> confirm` to actually hand it over.
Useful points:

- **Clean by default.** The old owner loses all access, like any other land-claim mod — set
  `keepOldOwnerTrusted: true` in the config if you'd rather they kept build access (so nobody is
  locked out of a shop they just gave away). The new owner can always re-add them with
  `/claim trust <player>` either way.
- **The new owner's claim limit still applies.** If the footprint would push them over
  `maxTotalPerPlayer`, the transfer is refused (ops are exempt).
- **Trust carries over**, and so does the shape — it is a change of owner, not a re-claim, so nothing
  has to be re-selected with the shovel.
- The recipient gets a chat message telling them they now own it.

## Admin zones (golden hoe, ops)
Zones define **where players may claim**: no zones = claim anywhere; once any zone exists, claims
must be fully inside one. The golden hoe works exactly like the claim shovel, but on zones —
right-click corners to add, sky-toggle to carve, touching zones merge — and zone borders show
(dark red) while an op holds it. Players use `/claim show` to see where they're allowed to build.

## Config (`config/shopguard.json`)
- `maxClaimArea` — max footprint of a single claim (default 10,000)
- `maxTotalPerPlayer` — max total footprint per player (default 40,000, ops exempt)
- `allowRedstoneControls` — let visitors use buttons/levers in claims (default false)
- `keepOldOwnerTrusted` — after `/claim transfer`, does the seller keep build access? `false` (default)
  = clean handover, `true` = they stay on the trust list

## Claim cost (needs EconomyCraft, off by default)
New claim area can be priced off the server's inflation signal, so the cost of land rises and falls with
the money supply on its own. `claimCostEnabled` and `claimRefundEnabled` both default to `false`.

```
f      = clamp(inflationMultiplier / claimCostReferenceMultiplier, min, max)
cost   = claimCostBaseFee + ceil(newBlocks x claimCostPerBlock x f)
refund = min((paid x (1 - claimRefundFeeRate)), claimRefundDailyLimit - refundedToday)
```

- **Only new area is billed.** Claims merge when they touch, so re-selecting ground you already hold is free.
- **Refunds use what you paid**, never the live price — otherwise you could buy low and cash out high.
- **A release is refused, never part-paid**, when the refund exceeds today's allowance
  (`claimRefundDailyLimit`, default 2500 — ShopGuard's own key, deliberately not EconomyCraft's
  `dailySellLimit`, which only caps residual `/sell`). The economy has no negative balances and no
  escrow, so the remainder can't be represented. Note `claimRefundDailyLimit: 0` locks players into land
  they paid for; the mod warns at startup if you set that.
- **Carving pays nothing** but scales the claim's recorded cost down with the area, so you can't claim
  large, carve to nothing, and refund at the original price.
- Ops are exempt; `/claim transfer` neither charges nor refunds, and the recorded cost travels with the
  claim. Claims made before pricing existed record zero, so they refund nothing.
- Run `/claim` to see the exact numbers in force, and see `CHANGELOG.md` for the full key list.

Every constant in the derivation is a config key, and `verifyEconomycraftJar` fails the build against an
EconomyCraft jar that predates the three API methods this uses.
