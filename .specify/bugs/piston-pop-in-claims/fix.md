# Bug Fix: Pistons cannot pop blocks (sugar cane) inside claims

- **Slug**: piston-pop-in-claims
- **Fixed**: 2026-10-09
- **Assessment**: ./assessment.md
- **Status**: applied

## Summary

The destroy loop in `PistonStructureResolverMixin` cancelled the entire piston move whenever a
to-be-popped block (`PushReaction.POPPED` — sugar cane, bamboo, torches, crops) stood inside **any**
claim, with no boundary comparison, so piston sugar-cane farms silently did nothing in claimed land. It
now applies the same boundary rule the push loop already had: a pop is cancelled only when it crosses a
claim boundary. Piston farms work inside a claim; a piston outside a claim still cannot pop blocks
inside it.

## Changes

| File | Change | Notes |
|------|--------|-------|
| `src/main/java/io/github/andrewwwwwwwwwwwwwww/shopguard/mixin/PistonStructureResolverMixin.java` | modified | Destroy loop compares the doomed block's claim with the claim behind it (the piston itself, when nothing is pushed) instead of forbidding every pop inside a claim; class javadoc documents the pop rule |
| `src/main/java/io/github/andrewwwwwwwwwwwwwww/shopguard/claim/ClaimBoundaries.java` | added | The boundary rule as a pure function (`crossesBoundary`), so it is testable without a server; documents that the comparison is reference identity and that `null` means wilderness |
| `src/test/java/io/github/andrewwwwwwwwwwwwwww/shopguard/claim/ClaimBoundariesTest.java` | added | Six tests: the five boundary cases plus the reference-identity assumption from `ClaimStore` |
| `CHANGELOG.md` | modified | New Unreleased section describing the fix |

## Diff Highlights

```java
 for (BlockPos doomed : self.getToDestroy()) {
-    if (claimAt(dim, doomed) != null) {
+    // The push reaches a popped block from the block behind it, so the pop crosses a claim
+    // boundary exactly when the doomed block and that block sit in different claims. With
+    // nothing being pushed (a piston facing a grown sugar-cane stalk), the block behind is
+    // the piston itself, and the rule reads: a piston may pop blocks in its own claim. Two
+    // unclaimed positions compare equal, so pops in the wilderness stay pure vanilla.
+    if (ClaimBoundaries.crossesBoundary(claimAt(dim, doomed.relative(dir.getOpposite())), claimAt(dim, doomed))) {
         cir.setReturnValue(false);
         return;
     }
 }
```

## Tests Added or Updated

- `ClaimBoundariesTest.aPopEntirelyInsideOneClaimCrossesNoBoundary` — the farm case that was broken
- `ClaimBoundariesTest.wildernessToWildernessIsVanillaTerritory` — vanilla behavior outside claims is untouched
- `ClaimBoundariesTest.aPistonOutsideAClaimCannotPopIntoIt` — the grief vector stays closed
- `ClaimBoundariesTest.aPistonInsideAClaimCannotPopOutOfIt` — symmetric, matching the push loop's philosophy
- `ClaimBoundariesTest.aPopBetweenTwoDifferentClaimsCrosses` — neighbouring claims stay separate
- `ClaimBoundariesTest.claimAtReturnsTheSameLiveObjectForEveryPositionInsideOneClaim` — pins the reference-identity assumption the rule (and the mixin's push loop) rest on

## Local Verification

- Commands run: `./gradlew build` → **BUILD SUCCESSFUL**, full suite **61 tests, 0 failures, 0 errors** (was 55).
- Manual checks: the vanilla side of the diagnosis was verified by disassembling the Minecraft 26.3
  server jar — sugar cane registers `pushReaction(PushReaction.POPPED)`; cobblestone and gravel set no
  reaction (default `PUSH_PULL`); `PistonStructureResolver.resolve()` puts a directly-adjacent `POPPED`
  block in `toDestroy` with `toPush` left empty; `PistonBaseBlock` drops the popped item at the doomed
  position. Current Fabric API has no piston event (no Bukkit `BlockPistonExtendEvent` equivalent), so
  the mixin remains the canonical mechanism.
- Deployed to the production server with the stopped-server procedure and a hash gate; boot verified
  clean (`Done`, zero ERRORs, claim pricing `economy=present`). **In-game exercise still open** — it
  needs a player: (1) a cane farm fully inside a claim pops and drops; (2) a piston outside a claim
  facing border cane does nothing; (3) same-claim cobblestone pushes still work; (4) wilderness pops
  unchanged.

## Deviations from Assessment

None. The fix is the assessment's preferred remediation verbatim; the alternatives were not needed.

## Follow-ups

- Cut a release version for the two Unreleased sections when convenient (version was deliberately left
  at 0.11.0, matching this repo's convention that Unreleased work ships under the current version).
- In-game checklist above, once an operator is online.
- Operational documentation (live jar state, changelogs) was updated in the operations tree, not here.
