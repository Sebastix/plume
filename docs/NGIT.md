# Nostr repository and issues

Plume uses Nostr issues through [GitWorkshop](https://gitworkshop.dev/sebastix.dev/plume3). Sign in with your Nostr identity to report a bug; no GitHub account is required.

GRASP servers host the Git copies and repository relays. ngit publishes signed repository state and provides command-line issue and pull-request tools. The APK is released through Zapstore and the repository release.

## Repository

- Identifier: `plume3`
- Maintainer: `npub1qe3e5wrvnsgpggtkytxteaqfprz0rgxr8c3l34kk3a9t7e2l3acslezefe`
- GRASP hosts: `relay.ngit.dev`, `gitnostr.com`
- Repository: `https://gitworkshop.dev/sebastix.dev/plume3`

With [ngit](https://ngit.dev/quickstart) installed:

```sh
git clone nostr://npub1qe3e5wrvnsgpggtkytxteaqfprz0rgxr8c3l34kk3a9t7e2l3acslezefe/relay.ngit.dev/plume3
```

The maintainer checkout uses `origin` for Nostr, so pushes through `origin` update the listed Git servers.

```sh
git push origin master
git push origin --tags
ngit repo
```

The remote signer must be online for signed pushes. If a mirror misses an update, inspect `ngit repo` and run `ngit sync` to reconcile the published state. Avoid force pushes. Changes made through the web forge must first be fetched and reconciled locally before pushing through `origin`.

## Issues

Plume also offers bug and feature-request buttons in settings, and a prefilled report action in the crash dialog. Reports are editable public Nostr issues, signed by the reporting user through their Android signer. The Issues tab displays kind-1621 activity.

Use the GitWorkshop link above, or the CLI from a clone:

```sh
ngit issue list
ngit issue create --subject "Short description" --body "Steps to reproduce and what happened"
ngit issue view <issue-id> --comments
```

Reports and crash details posted as issues are public. Review the contents before submitting; exclude connection secrets, private keys, and other private data. Existing GitHub issues and release assets are not migrated by mirroring the Git repository.

## Maintainer identity

The maintainer uses the same Amber identity as Zapstore publishing, with a dedicated ngit connection. The connection is stored in the local OS credential store, with the repository selecting the `ngit-plume3` alias. This connection can be revoked independently of Zapstore. Credentials must never be added to tracked files.

See the upstream [mirroring guide](https://ngit.dev/repositories/mirroring) and [issue workflow](https://ngit.dev/issues).
