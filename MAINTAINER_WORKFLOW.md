# Maintainer Workflow

This document describes the normal maintenance and release loop for `generic-object-pool`. It is one workflow with switches for issue fixes, dependency maintenance, release preparation, and historical bookkeeping.

Repository facts:

- GitHub: `bbottema/generic-object-pool`
- Maven: `com.github.bbottema:generic-object-pool`
- Default and only long-lived branch: `master`
- Runtime and bytecode baseline: Java 8
- Release-note surfaces: `RELEASE.txt`, the current-release section in `README.md`, and the tag-specific GitHub release
- Release automation: `.circleci/config.yml` using the `github-maven-deploy` orb

---

## 1. Interpret the request

Decide whether the request is implementation only, release preparation, an explicitly authorized release, or metadata-only bookkeeping. Do not publish a new version unless the maintainer explicitly asks for a semantic-version release. Creating or correcting GitHub metadata for an existing tag is not a new code release.

When a release is authorized, preserve the requested patch, minor, major, or as-is level through the CircleCI approval gate, Maven Central verification, GitHub release, and milestone closure. Do not ask for a second approval unless the requested release level changes.

## 2. Start from live state

```powershell
git status --short --branch
git fetch --prune --tags origin
git branch -vv
gh auth status
```

The checkout must be understood before edits. Preserve unrelated local changes and never rewrite shared history as a shortcut.

Use Java 8 for release-grade local verification:

```powershell
java -version
mvn -version
```

If local Maven HTTPS traffic is intercepted by the Windows trust store, try `-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT` before considering any insecure workaround.

## 3. Branch model

`master` is the only long-lived branch. There is no `develop` branch.

For normal implementation work, create a short-lived branch from current `origin/master` and target the pull request at `master`. For a maintainer-authorized direct bookkeeping or documentation update, commit only the requested files to `master` after confirming it is clean and aligned.

Never force-push `master`. If a fast-forward is not possible, stop and inspect the divergence.

## 4. Triage GitHub work

Read the issue or pull request, its comments, current labels, milestone, linked commits, and related upstream/downstream work before editing:

```powershell
gh issue view 12 --repo bbottema/generic-object-pool --comments --json number,title,body,labels,milestone,author,url,comments
gh issue list --repo bbottema/generic-object-pool --state open --limit 50 --json number,title,labels,milestone,author,url
gh pr list --repo bbottema/generic-object-pool --state open --limit 50 --json number,title,author,baseRefName,headRefName,url,labels,statusCheckRollup
```

Use the canonical Simple Java Mail ecosystem labels. Added functionality uses exactly one of `enhancement` or `major feature`; other work types are `bug`, `maintenance`, `documentation`, `security`, `dependencies`, and `3rdparty-problem`. Workflow labels include `Priority-Low`, `Priority-Medium`, `Priority-High`, `question`, `need-user-input`, `needs-research`, `invalid`, `duplicate`, `will close soon`, `postponed indefinitely`, and `wontfix`. `java` and `help wanted` are available when applicable.

Re-read an item's labels immediately before changing them. Treat a maintainer's removal or replacement as deliberate. Migrate assignments before removing a duplicate-sounding alias.

### Release milestones

Every released semantic version has one milestone whose title is the exact numeric tag, without a `v` prefix. Its description is empty, its due date is the original release/tag date at UTC midnight, every represented issue and pull request belongs to it, and it is closed only after every member is closed.

```powershell
gh api 'repos/bbottema/generic-object-pool/milestones?state=all&per_page=100' --paginate `
  --jq '.[] | [.number,.title,.state,.due_on,.open_issues,.closed_issues] | @tsv'
```

Assign pull requests through the issues endpoint. Do not milestone rejected, superseded, or unrelated proposals.

```powershell
gh api -X PATCH repos/bbottema/generic-object-pool/issues/8 -F milestone=12
```

For historical work, derive the date from authoritative release notes when present, otherwise from the annotated tag creation date:

```powershell
git for-each-ref --format='%(creatordate:short)' refs/tags/2.4.2
```

After creating or updating a milestone, read the raw API value back and verify that `due_on` is exactly that date followed by `T00:00:00Z`.

## 5. Implement

Read the affected production code and tests before changing behavior. Add a focused regression test for reproducible bugs and exercise concurrency with deterministic synchronization instead of timing-only sleeps wherever possible.

For public API changes, keep these surfaces aligned:

- Java API and Javadocs
- examples and compatibility guidance in `README.md`
- tests in `src/test/java`
- current and historical notes in `RELEASE.txt`

Preserve Java 8 source, bytecode, and dependency compatibility. Avoid introducing a dependency for functionality that is small enough to implement clearly in the project.

This library is the lowest pooling layer in the ecosystem. For changes affecting shutdown, allocation, deallocation, claiming, expiration, or module identity, inspect the downstream `clustered-object-pool` behavior before release. Do not mutate or release a sibling repository without explicit authorization.

## 6. Verify

Run focused tests first, then the same verification shape used by CircleCI:

```powershell
mvn -Dtest=SimpleSingleObjectPoolTest test
mvn verify -Dmaven.javadoc.skip=true -Djacoco.skip=true -Dlicense.skip=true
```

When public packaging or JPMS metadata changes, inspect the built JAR as well as the source tree. The published automatic module name is `org.bbottema.genericobjectpool`.

If a build inserts generated license headers into the working tree, remove them with the configured license goal before staging and confirm that only intentional source changes remain.

For acquisition API changes, also run the checked-in compatibility probe after packaging:

```powershell
./scripts/verify-compatibility.ps1 -Java8Home '<JDK 8>' -ModernJavaHome '<JDK 21>'
```

It compiles a legacy allocator/client against the published 2.4.3 JAR, runs those unchanged classes with the candidate,
and checks the new API on Java 8/21 classpaths and the modern module path. Run the compiled test suite with
`mvn surefire:test -Djacoco.skip=true -Dnet.bytebuddy.experimental=true` under JDK 21 as a separate runtime lane;
the extra property lets the existing test-only Byte Buddy version instrument Java 21 classes. The release compilation
remains Java 8, and the standalone compatibility probes do not depend on Mockito or Byte Buddy.

## 7. Documentation and release notes

`RELEASE.txt` retains the complete release history. `README.md` is the developer landing page: keep its dependency example, current version, JPMS name, and current-release summary aligned without turning it into a second full archive.

Each existing tag must have exactly one published, non-prerelease GitHub release titled `v{numeric-version}` while the tag itself remains numeric. The GitHub release body must be a permanent, self-contained record for that tag:

- state what changed in that version;
- link the relevant issue, pull request, or immutable commit when useful;
- call out source, runtime, behavior, or module compatibility;
- identify an intentionally metadata-only release as such; and
- never delegate the essential explanation to `README.md` or another release.

Do not include test logs, build validation, approval-gate details, or other internal process evidence in public release notes. Routine dependency updates should be grouped unless they fix a material defect or security exposure. Preserve any assets already attached to an existing release; this project does not normally publish repository-specific binary assets on GitHub.

## 8. Commit and push

Stage only intended files and inspect the result:

```powershell
git diff
git add <paths>
git diff --cached --check
git diff --cached --stat
git commit -m "fix(pool): concise summary"
```

Use semantic subjects. Add `[skip ci]` to documentation-only or bookkeeping-only commits. Do not use it for implementation, dependency, build, packaging, or release-lane changes that need CI.

Push implementation branches and open a pull request to `master`. Direct pushes are reserved for explicitly authorized maintainer work.

## 9. Release

Only release when explicitly authorized.

Before approving a release:

1. Confirm `master` is clean, current, and contains only the intended release work.
2. Review open dependency pull requests; include only safe, Java-8-compatible updates within scope.
3. Run the full Maven verification.
4. Align `README.md` and `RELEASE.txt`.
5. Create or reuse the exact numeric milestone, set its planned due date, and assign every represented issue and pull request.
6. Cross-check all issue and pull-request links in the notes against milestone membership.
7. Push `master` and wait for CircleCI `build-and-test`.

CircleCI exposes these approval jobs:

- `approve-deploy-patch-version`
- `approve-deploy-minor-version`
- `approve-deploy-major-version`
- `approve-deploy-as-is-version`

Approve exactly the authorized gate. The deploy job owns the POM version update, commit, tag, and Maven Central publication. Do not edit `pom.xml` merely to prepare a release.

After deployment:

1. Fetch `master` and tags.
2. Verify the artifact and POM under `https://repo.maven.apache.org/maven2/com/github/bbottema/generic-object-pool/{version}/`.
3. Create or update the tag-specific GitHub release.
4. Verify every milestone member is closed.
5. Replace the planned milestone date with the actual tag/release date at UTC midnight and close the milestone.
6. Confirm local `master`, `origin/master`, and the published tag agree and the worktree is clean.

If Maven Central publication succeeded but repository tagging failed, first prove that partial state. Repair only the missing repository state; never republish or retag by guesswork.

## 10. Definition of done

For implementation without release:

- the intended change and regression coverage are committed;
- relevant Maven verification passes or any limitation is explained;
- GitHub labels, comments, and milestone are current;
- documentation and unreleased notes are aligned when user-facing; and
- the worktree is clean and the branch is pushed.

For a release:

- the artifact is available from Maven Central;
- `master` and the numeric tag are present remotely;
- one self-contained GitHub release exists for that tag;
- every represented issue and pull request is closed in the exact-version milestone;
- the milestone is closed with the exact original release date;
- related issue threads have concise availability comments; and
- the local checkout is clean and aligned with `origin/master`.
