# Moataz Brand Identity

## Product names

| Layer | User-facing name |
|---|---|
| Master brand | Moataz |
| Product | Moataz Alalqami AI |
| Developer workspace | Moataz Code |
| Terminal | Moataz Terminal |
| Local Linux | Moataz Runtime |
| Agents | Moataz Agents |
| Model routing | Moataz Gateway |
| Projects | Moataz Workspace |

`MoatazBrand` is the Kotlin source of truth. UI code must not introduce another
product-name literal. The assistant persona is a separate `AssistantIdentity`;
the default prompt explicitly identifies it as an AI and forbids impersonating
the human named Moataz.

## Compatibility boundary

The following remain unchanged intentionally: `com.inspiredandroid.kai`,
`KaiDatabase`, existing preference keys, `kai_build` resource identifiers,
persisted paths such as `kai-build-home/projects`, and the `kai-ui` wire/fence
format. They are internal compatibility identifiers, not product identity.

The upstream Kai attribution and Apache-2.0 notices remain in README and legal
documents. Generated resource packages are not renamed manually.

## Visual language

The central design package uses neutral carbon/graphite surfaces, a restrained
teal signal accent, limited blue secondary accent, and independent semantic
colors. `ProvideMoatazTheme` supports light, dark, and the existing OLED mode;
layout direction remains supplied by Compose, so RTL/LTR behavior is preserved.
