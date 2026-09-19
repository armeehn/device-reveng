# Security policy

## Scope

This repository holds research notes, a side-loaded Android launcher and the build
pipeline for a system image, all for an aftermarket car head unit. It runs no server,
stores no user accounts and distributes no firmware images.

Relevant issues include:

- A defect in this launcher that leaks data off the unit or escalates privilege
  beyond the root the user already granted.
- A script here that writes to a device when it claims to be read-only.
- Committed secrets (keystore material, credentials, personal device identifiers).

Vulnerabilities in the **vendor's** firmware, apps, or cloud services are out of
scope for this repo. Report those to the vendor.

## Reporting

Email **sasha@ripostelabs.xyz**. Please include what you found, how to reproduce it,
and the affected commit. Expect an acknowledgement within a few days. This is a
personal project, not a staffed product.

Please do not open a public issue for a vulnerability that puts other owners' units
at risk until there has been a chance to fix it.

## Not a vulnerability

- The launcher requests root. That is the documented design; privileged car actions
  are unreachable without it because the vendor platform signing key is unobtainable.
- The launcher declares `QUERY_ALL_PACKAGES`. A HOME app must enumerate launchable
  activities to draw an app drawer.
- Release APKs are signed with a self-managed key and side-loaded, not distributed
  through Play. Verify the signature yourself if that matters to you.
