# Bug Assessment: Pistons cannot pop blocks (sugar cane) inside claims

- **Slug**: piston-pop-in-claims
- **Created**: 2026-10-09
- **Source**: pasted text
- **Verdict**: valid
- **Severity**: medium

## Report (verbatim or summarized)

> "[shopguard] piston can not break things like sugar cane in claimed land … though it
> can still push cobblestone. i notice gravel can still be broke with piston. the
> intended action for sugarcane should be breakable by piston for farm."

Reported in game by the operator. Cleaned up only for spelling; meaning preserved.

## Symptom

Inside a claim, a powered piston silently does nothing when its push would **pop** a
`PushReaction.POPPED` block (sugar cane, bamboo, torches, crops): the move is cancelled,
the piston does not even extend, and the cane stays untouched. Pushing ordinary blocks
(cobblestone, gravel) within the same claim still works, and pops outside any claim work
exactly vanilla. Expected: a piston inside a claim can pop blocks inside the same claim —
piston sugar-cane farms are the canonical use.

## Reproduction

1. Create a claim.
2. Inside it, grow sugar cane to 2 blocks; place a piston one block in front of the
   upper cane block, facing it; power the piston.
3. Observed: the piston does not extend; the cane is untouched. Expected: the piston
   extends, the upper cane block pops and drops its item.
4. Contrast A — replace the cane with cobblestone or gravel: the piston still pushes it
   (same-claim push is allowed).
5. Contrast B — build the same cane setup outside any claim: the pop works.

The report's "gravel can still be broke with piston" matches gravel being **pushed** —
it never enters the pop list (see evidence below), so the piston visibly still acts on
it inside a claim.

## Suspected Code Paths

- `src/main/java/io/github/andrewwwwwwwwwwwwwww/shopguard/mixin/PistonStructureResolverMixin.java:39-44`
  — the `toDestroy` loop cancels the **entire** resolve whenever the doomed block lies
  inside **any** claim: `if (claimAt(dim, doomed) != null) { cir.setReturnValue(false); … }`.
  No boundary or ownership comparison, unlike the `toPush` loop directly above it
  (lines 33-38), which compares the claim on both sides of every pushed block and allows
  same-claim moves.
- Same file, class javadoc (lines 18-22) — the documented intent is boundary protection
  ("can't push or pull blocks into, out of, or between claims; it can still move blocks
  freely within a single claim or in unclaimed land"). The `toDestroy` loop does not
  implement that intent.

## Root Cause Hypothesis

**Confidence: high.** The `toDestroy` loop checks "is the doomed block in a claim at
all" where it should check "does the pop cross a claim boundary". Vanilla piston
mechanics split blocks into *pushed* (`toPush`) and *popped* (`toDestroy`); ShopGuard's
`toPush` rule was written as a boundary comparison, but the `toDestroy` rule was
written as a blanket in-claim prohibition. Every piston pop inside a claim — including a
cane farm fully inside one player's claim — is therefore cancelled.

Vanilla evidence, verified by disassembling the Minecraft **26.3** server jar (the game
has shipped unobfuscated with official Mojang names since 26.1, so the jar is the
authoritative API):

- `net.minecraft.world.level.material.PushReaction` values are `PUSH_PULL`, `PUSH`,
  `POPPED`, `IMMOVEABLE`, `IGNORE_ENTITY` (`POPPED` was named `DESTROY` before 26.1).
- In `Blocks`' registration, **sugar cane registers
  `pushReaction(PushReaction.POPPED)` explicitly; cobblestone and gravel set no push
  reaction and default to `PUSH_PULL`.** That is exactly the reported contrast: pushed
  blocks pass the (correct) `toPush` boundary check inside a claim; popped blocks die at
  the (broken) `toDestroy` check.
- `PistonStructureResolver.resolve()`: when the first scanned block — the space the head
  extends into — is not pushable, the piston is extending, and its reaction is `POPPED`,
  that position is added to `toDestroy` and `resolve()` returns true, with **`toPush`
  left empty**. This is precisely the cane-farm geometry: nothing is pushed, one block
  is popped. `addBlockLine` does the same mid-line: a non-pushable `POPPED` block is
  added to `toDestroy` and the line ends successfully.
- `PistonBaseBlock` consumes each `toDestroy` position with `dropResources(...)` at that
  position and then sets it to air — the pop drops the item **where the block stood**.

Fabric API check (0.161.0+26.3, current `26.3` branch): **no piston event API exists** —
Fabric has no equivalent of Bukkit's `BlockPistonExtendEvent`/`BlockPistonRetractEvent`
(confirmed against the current module list; third-party mods track pistons with their
own mixins for the same reason). The `PistonStructureResolver.resolve()` mixin is the
canonical mechanism; only the `toDestroy` policy inside it is wrong.

## Proposed Remediation

**Preferred**: give the `toDestroy` loop the same boundary semantics the `toPush` loop
already has — allow the pop when the doomed block and the block behind it (relative to
the push direction) are in the same claim, or both unclaimed:

```java
for (BlockPos doomed : self.getToDestroy()) {
    if (claimAt(dim, doomed) != claimAt(dim, doomed.relative(dir.getOpposite()))) {
        cir.setReturnValue(false);
        return;
    }
}
```

- Directly-adjacent pop (`toPush` empty — the cane farm): the block behind is the piston
  itself, so the rule reads *"a piston may pop blocks in its own claim"*.
- Pushed line ending in a pop: the behind block is the last pushed block's source
  position, whose destination **is** the doomed position — the `toPush` loop already
  compares that exact pair, so the new check is consistent with it.
- Cross-boundary pops stay blocked in both directions: a piston outside a claim cannot
  pop blocks inside it (the grief vector the loop was presumably written against), and a
  piston inside a claim cannot pop blocks outside it — matching the mixin's documented
  "nothing crosses a claim boundary, either direction" philosophy.
- Wilderness untouched: `null == null`, pure vanilla.

Extract the comparison into a tiny pure helper (e.g.
`PistonRules.popCrossesClaimBoundary(Claim doomed, Claim behind)`) so the rule is
unit-testable without a server, following the pattern the faction tests established.

**Alternatives**:

- Remove the `toDestroy` loop entirely (pure vanilla pops). Restores farms everywhere
  but reopens cross-border grief: a piston placed outside a claim could pop every
  `POPPED` block within reach inside it (cane, bamboo, torches, crops). Rejected —
  under-protects.
- Config key (e.g. `pistonPopsInClaims`). A pop within one claim has no grief vector, so
  the boundary rule needs no tuning; a knob only adds a way to be wrong. Rejected —
  complexity for nothing.

**Files likely to change**:

- `src/main/java/io/github/andrewwwwwwwwwwwwwww/shopguard/mixin/PistonStructureResolverMixin.java`
- a new small pure-rule class for the comparison (for tests)
- `CHANGELOG.md`

**Tests to add or update**:

- Unit tests (JUnit, Minecraft-free) for the pop rule: same-claim pop allowed;
  doomed-in-claim / behind-other-claim blocked; doomed-in-claim / behind-wilderness
  blocked; doomed-wilderness / behind-claim blocked; both-wilderness allowed.
- In-game checklist for the deploy: cane farm inside a claim pops and drops; piston
  outside a claim facing border cane does nothing; cobblestone push inside a claim still
  works; pops outside claims still work.

## Risks & Considerations

- Popped drops land at the doomed position, inside the claim. Ground item drops are not
  claim-protected in ShopGuard (as in most claim mods), so a visitor standing inside the
  claim could pick up popped items — identical exposure to the owner hand-breaking a
  block there. Not a new vector; noted so nobody mistakes it for one introduced by this
  fix.
- A visitor can fire an owner's farm by stepping on a pressure plate (plates are always
  allowed for movement). Same pre-existing redstone exposure as any owner-built farm.
- No config keys involved, so no migration and no coupled config restart.
- Reference comparison (`!=`) on `Claim` is exactly how the `toPush` loop already works;
  `ClaimStore.claimAt` returns the same live object for the same claim, and distinct
  claims never share an object, so equality semantics are consistent between both loops.
- The class javadoc already documents the boundary intent, so the fix makes the code
  match its own documentation — no documented behavior is repudiated.

## Open Questions

- None blocking. The report's gravel wording is interpreted as "the piston still acts
  on gravel (pushes it)" — the code confirms gravel is `PUSH_PULL` and never enters
  `toDestroy`.
