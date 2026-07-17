# Catalog schema lifecycle

`1.json` is the canonical first-release Room schema for `KelliKanvasDatabase`.

The Task 5 branch is isolated from the runnable integration branch and has not
been tagged, shipped, or merged into an installable release. Earlier `1.json`
contents existed only in intermediate development commits. Integration is
expected to squash or cherry-pick the final Task 5 state, so those intermediate
files are not migration baselines and the final schema remains version 1.

If an intermediate Task 5 commit is ever distributed or merged into a runnable
build before this final state, this decision must be revisited: preserve that
released schema and introduce a tested version 1 to version 2 migration instead
of replacing `1.json`.
