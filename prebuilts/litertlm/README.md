Drop `libopenaicore_litertlm.so` for each supported ABI here, built as described in
`native/litertlm/README.md`, and pin the LiteRT-LM tag it was built from in `PINNED_TAG`.

These binaries are intentionally not committed. They are large, they are build outputs, and
committing them would make the branch impossible to review. crDroid's release process should
publish them as a signed artefact and fetch them at build time.
