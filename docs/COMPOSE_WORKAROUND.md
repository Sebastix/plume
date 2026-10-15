# Detached Material 3 semantics node

The GRASP crash report `nevent1qqsgqc68z3s46nu5zzyshsxv9xs2y3ssya0u5uc9959g9wsx8ctc9aspz3mhxue69uhhyetvv9ujumn8d96zuer9wclv0pku` came from 0.64.0 (119), source `c3a86a1`.

Using that release's R8 mapping, the relevant frames resolve to:

- `TraversableNodeKt.traverseAncestors`, `TraversableNode.kt:380`
- `ChildSemanticsNode.applySemantics`, `ChildParentSemantics.kt:92`
- `LayoutNode.calculateSemanticsConfiguration`, `LayoutNode.kt:482`
- `SnapshotStateObserver.observeReads`
- `LayoutNode.invalidateSemantics`
- `OwnerSnapshotObserver.onCommitAffectingSemantics`, `OwnerSnapshotObserver.kt:47`

The apparent ImageGallery/ProfileSearch frames in the obfuscated trace are merged synthetic lambdas, not evidence of those screens triggering the crash. A snapshot callback is recalculating semantics on a node whose modifier is detached. Material 3 1.4.0's implementation unconditionally traverses ancestors; Compose UI requires attachment for that traversal.

`buildSrc` applies a narrow AGP ASM transformation to `ChildSemanticsNode.applySemantics`: return immediately when `Modifier.Node.isAttached` is false. Attached nodes run the original implementation unchanged. This preserves accessibility for active controls, does not catch unrelated exceptions, and adds no runtime dependency. Both debug and release use the guard before D8/R8; AGP recomputes stack frames for the changed method. An unexpected method signature fails the build instead of silently omitting the fix.

Review/remove the transformation when upgrading Material 3 once its implementation safely handles detached semantics nodes. No Compose dependency upgrade or downgrade is part of this workaround.

Sources inspected: Google Maven `material3:1.4.0` and `ui:1.12.1` source JARs; [upstream ChildParentSemantics.kt](https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/ChildParentSemantics.kt). Physical-device confirmation remains necessary; no emulator or automated test run was requested.
