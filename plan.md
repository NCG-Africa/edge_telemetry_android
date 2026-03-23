# Naming Convention Remediation Plan

## Problem Statement
The codebase contains files, classes, and functions named with temporal prefixes/suffixes ("Phase", "Stage") instead of descriptive functional names. This violates proper nomenclature and makes the code harder to understand and maintain.

## Identified Breaches

### 1. Documentation Files (14 files)

#### Phase-Named Documentation
1. `PHASE_1_SUMMARY.md` → Event Name Alignment
2. `PHASE_1_MIGRATION.md` → Event Name Migration Guide
3. `PHASE_2_SUMMARY.md` → Standard Attributes Implementation
4. `PHASE_2A_SUMMARY.md` → App/Device Attributes
5. `PHASE_2C_SUMMARY.md` → Enhanced Crash Context
6. `PHASE_2_IMPLEMENTATION.md` → Standard Attributes Implementation Details
7. `PHASE_3_SUMMARY.md` → Event Cleanup
8. `PHASE_4_SUMMARY.md` → Testing & Validation
9. `PHASE_4_QUICK_REFERENCE.md` → Testing Quick Reference
10. `PHASE_4_TESTING_GUIDE.md` → Testing Guide
11. `PHASE_4_TEST_SUITE_SUMMARY.md` → Test Suite Summary
12. `PHASE_5_SUMMARY.md` → Documentation
13. `phase-2b-completion.md` → User/Session Attributes
14. `phase-2c-completion.md` → Enhanced Context Completion
15. `phase-3-testing-summary.md` → Testing Summary

#### Stage-Named Documentation
1. `STAGE_2_REMEDIATION_SUMMARY.md` → Identity & User Profile
2. `STAGE_9_IMPLEMENTATION_SUMMARY.md` → Automatic Instrumentation

#### Root-Level Documentation
1. `PHASE_4_IMPLEMENTATION_COMPLETE.md` → Should be in docs/

### 2. Test Files (6 files)

#### Phase-Named Tests
1. `Phase4EventIntegrationTest.kt` → Event integration tests
2. `Phase2cEnhancedContextTest.kt` → Enhanced crash context tests
3. `Phase4IntegrationTest.kt` → Integration tests

#### Stage-Named Tests
1. `TelemetryManagerStage2Test.kt` → TelemetryManager tests
2. `IdGeneratorStage2Test.kt` → IdGenerator tests
3. `UserProfileManagerStage2Test.kt` → UserProfileManager tests

### 3. Code Functions/Methods (2 occurrences)

1. `TelemetryManager.performStage9InitSequence()` → Should be `performInitializationSequence()` or `initializeSdkComponents()`
2. Comments/docs referencing "Phase" or "Stage" in code

### 4. Workflow Files (1 file)

1. `.windsurf/workflows/sdk-refcator.md` → Contains Phase/Stage references

---

## Remediation Plan

### Step 1: Define Functional Names Mapping

#### Documentation Renaming Strategy

| Current Name | Functional Name | Description |
|--------------|-----------------|-------------|
| `PHASE_1_SUMMARY.md` | `EVENT_NAME_ALIGNMENT.md` | Event name standardization |
| `PHASE_1_MIGRATION.md` | `EVENT_NAME_MIGRATION_GUIDE.md` | Migration guide for event names |
| `PHASE_2_SUMMARY.md` | `STANDARD_ATTRIBUTES_IMPLEMENTATION.md` | Standard attributes |
| `PHASE_2A_SUMMARY.md` | `APP_DEVICE_ATTRIBUTES.md` | App and device attributes |
| `PHASE_2C_SUMMARY.md` | `ENHANCED_CRASH_CONTEXT.md` | Enhanced crash reporting |
| `PHASE_2_IMPLEMENTATION.md` | `STANDARD_ATTRIBUTES_DETAILS.md` | Implementation details |
| `PHASE_3_SUMMARY.md` | `EVENT_CLEANUP_IMPLEMENTATION.md` | Event cleanup and feature flags |
| `PHASE_4_SUMMARY.md` | `TESTING_VALIDATION_SUMMARY.md` | Testing and validation |
| `PHASE_4_QUICK_REFERENCE.md` | `TESTING_QUICK_REFERENCE.md` | Quick reference guide |
| `PHASE_4_TESTING_GUIDE.md` | `TESTING_GUIDE.md` | Comprehensive testing guide |
| `PHASE_4_TEST_SUITE_SUMMARY.md` | `TEST_SUITE_SUMMARY.md` | Test suite overview |
| `PHASE_5_SUMMARY.md` | `DOCUMENTATION_SUMMARY.md` | Documentation completion |
| `phase-2b-completion.md` | `USER_SESSION_ATTRIBUTES.md` | User and session attributes |
| `phase-2c-completion.md` | `ENHANCED_CONTEXT_COMPLETION.md` | Enhanced context completion |
| `phase-3-testing-summary.md` | `EVENT_CLEANUP_TESTING.md` | Event cleanup testing |
| `STAGE_2_REMEDIATION_SUMMARY.md` | `IDENTITY_USER_PROFILE_IMPLEMENTATION.md` | Identity and user profile |
| `STAGE_9_IMPLEMENTATION_SUMMARY.md` | `AUTOMATIC_INSTRUMENTATION_IMPLEMENTATION.md` | Automatic instrumentation |
| `PHASE_4_IMPLEMENTATION_COMPLETE.md` | `docs/TESTING_IMPLEMENTATION_COMPLETE.md` | Move to docs/ |

#### Test File Renaming Strategy

| Current Name | Functional Name | Description |
|--------------|-----------------|-------------|
| `Phase4EventIntegrationTest.kt` | `EventIntegrationTest.kt` | Event integration tests |
| `Phase2cEnhancedContextTest.kt` | `EnhancedCrashContextTest.kt` | Enhanced crash context tests |
| `Phase4IntegrationTest.kt` | `StandardAttributesIntegrationTest.kt` | Standard attributes integration |
| `TelemetryManagerStage2Test.kt` | `TelemetryManagerTest.kt` | TelemetryManager tests |
| `IdGeneratorStage2Test.kt` | `IdGeneratorTest.kt` | IdGenerator tests |
| `UserProfileManagerStage2Test.kt` | `UserProfileManagerTest.kt` | UserProfileManager tests |

#### Code Function Renaming

| Current Name | Functional Name | Location |
|--------------|-----------------|----------|
| `performStage9InitSequence()` | `performInitializationSequence()` | `TelemetryManager.kt` |

---

### Step 2: Execution Plan (Code & Tests Only)

#### Phase A: Test Files (Priority: High)
**Estimated Time:** 30 minutes

1. **Rename test files** (6 files)
   - Use `git mv` to preserve history
   - Update class names in files
   - Update test descriptions

2. **Files to rename:**
   - `Phase4EventIntegrationTest.kt` → `EventIntegrationTest.kt`
   - `Phase2cEnhancedContextTest.kt` → `EnhancedCrashContextTest.kt`
   - `Phase4IntegrationTest.kt` → `StandardAttributesIntegrationTest.kt`
   - `TelemetryManagerStage2Test.kt` → `TelemetryManagerTest.kt`
   - `IdGeneratorStage2Test.kt` → `IdGeneratorTest.kt`
   - `UserProfileManagerStage2Test.kt` → `UserProfileManagerTest.kt`

#### Phase B: Code Functions (Priority: High)
**Estimated Time:** 20 minutes

1. **Rename functions in TelemetryManager.kt**
   - `performStage9InitSequence()` → `performInitializationSequence()`
   - Update all callers
   - Update comments/documentation

2. **Update code comments**
   - Remove Phase/Stage references from code
   - Replace with functional descriptions

#### Phase C: Verification (Priority: High)
**Estimated Time:** 20 minutes

1. **Search for remaining references**
   - Grep for "Phase" and "Stage" in .kt files
   - Verify no broken references

2. **Build and test**
   - Run all tests to ensure nothing broke
   - Build project successfully

---

### Step 3: Implementation Checklist

#### Documentation Files
- [ ] Rename `PHASE_1_SUMMARY.md` → `EVENT_NAME_ALIGNMENT.md`
- [ ] Rename `PHASE_1_MIGRATION.md` → `EVENT_NAME_MIGRATION_GUIDE.md`
- [ ] Rename `PHASE_2_SUMMARY.md` → `STANDARD_ATTRIBUTES_IMPLEMENTATION.md`
- [ ] Rename `PHASE_2A_SUMMARY.md` → `APP_DEVICE_ATTRIBUTES.md`
- [ ] Rename `PHASE_2C_SUMMARY.md` → `ENHANCED_CRASH_CONTEXT.md`
- [ ] Rename `PHASE_2_IMPLEMENTATION.md` → `STANDARD_ATTRIBUTES_DETAILS.md`
- [ ] Rename `PHASE_3_SUMMARY.md` → `EVENT_CLEANUP_IMPLEMENTATION.md`
- [ ] Rename `PHASE_4_SUMMARY.md` → `TESTING_VALIDATION_SUMMARY.md`
- [ ] Rename `PHASE_4_QUICK_REFERENCE.md` → `TESTING_QUICK_REFERENCE.md`
- [ ] Rename `PHASE_4_TESTING_GUIDE.md` → `TESTING_GUIDE.md`
- [ ] Rename `PHASE_4_TEST_SUITE_SUMMARY.md` → `TEST_SUITE_SUMMARY.md`
- [ ] Rename `PHASE_5_SUMMARY.md` → `DOCUMENTATION_SUMMARY.md`
- [ ] Rename `phase-2b-completion.md` → `USER_SESSION_ATTRIBUTES.md`
- [ ] Rename `phase-2c-completion.md` → `ENHANCED_CONTEXT_COMPLETION.md`
- [ ] Rename `phase-3-testing-summary.md` → `EVENT_CLEANUP_TESTING.md`
- [ ] Rename `STAGE_2_REMEDIATION_SUMMARY.md` → `IDENTITY_USER_PROFILE_IMPLEMENTATION.md`
- [ ] Rename `STAGE_9_IMPLEMENTATION_SUMMARY.md` → `AUTOMATIC_INSTRUMENTATION_IMPLEMENTATION.md`
- [ ] Move `PHASE_4_IMPLEMENTATION_COMPLETE.md` → `docs/TESTING_IMPLEMENTATION_COMPLETE.md`

#### Test Files
- [ ] Rename `Phase4EventIntegrationTest.kt` → `EventIntegrationTest.kt`
- [ ] Rename `Phase2cEnhancedContextTest.kt` → `EnhancedCrashContextTest.kt`
- [ ] Rename `Phase4IntegrationTest.kt` → `StandardAttributesIntegrationTest.kt`
- [ ] Rename `TelemetryManagerStage2Test.kt` → `TelemetryManagerTest.kt`
- [ ] Rename `IdGeneratorStage2Test.kt` → `IdGeneratorTest.kt`
- [ ] Rename `UserProfileManagerStage2Test.kt` → `UserProfileManagerTest.kt`

#### Code Changes
- [ ] Rename `performStage9InitSequence()` → `performInitializationSequence()`
- [ ] Update function callers
- [ ] Update code comments referencing Phase/Stage

#### Cross-References
- [ ] Update README.md links
- [ ] Update CHANGELOG.md references
- [ ] Update internal doc cross-references
- [ ] Update workflow files

#### Verification
- [ ] Grep for remaining "Phase" references
- [ ] Grep for remaining "Stage" references
- [ ] Run all tests
- [ ] Verify documentation links
- [ ] Build project successfully

---

### Step 4: Risk Mitigation

**Risks:**
1. **Broken links** - Documentation cross-references may break
2. **Git history** - File renames may complicate git blame
3. **External references** - README/CHANGELOG may have broken links
4. **Build failures** - Test file renames may break build configuration

**Mitigations:**
1. Use `git mv` to preserve history
2. Update all cross-references in same commit
3. Comprehensive grep before committing
4. Run full test suite before committing
5. Single atomic commit for all renames

---

### Step 5: Success Criteria

- [ ] Zero files with "Phase" or "Stage" in filename
- [ ] Zero functions with "Phase" or "Stage" in name
- [ ] All documentation cross-references working
- [ ] All tests passing
- [ ] Project builds successfully
- [ ] Git history preserved for renamed files
- [ ] README.md and CHANGELOG.md updated
- [ ] All links verified working

---

## Timeline

**Total Estimated Time:** 3-4 hours

1. **Documentation Renames:** 1-2 hours
2. **Test File Renames:** 30 minutes
3. **Code Function Renames:** 30 minutes
4. **Verification & Testing:** 30 minutes
5. **Documentation Updates:** 30 minutes

---

## Next Steps

1. Review and approve this plan
2. Execute Step 2 (Execution Plan) in order
3. Commit all changes atomically
4. Verify success criteria
5. Update this plan.md with completion status
