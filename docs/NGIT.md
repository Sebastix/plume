# Nostr repository and issues

Feather uses Nostr issues through [GitWorkshop](https://gitworkshop.dev/npub1dergggklka99wwrs92yz8wdjs952h2ux2ha2ed598ngwu9w7a6fsh9xzpc/relay.ngit.dev/feather). Sign in with your Nostr identity to report a bug; no GitHub account is required.

GRASP servers host the Git copies and repository relays. ngit publishes signed repository state and provides command-line issue and pull-request tools. GitHub remains a code mirror and the APK release host.

## Repository

- Identifier: `feather`
- Maintainer: `npub1dergggklka99wwrs92yz8wdjs952h2ux2ha2ed598ngwu9w7a6fsh9xzpc`
- GRASP hosts: `relay.ngit.dev`, `gitnostr.com`
- GitHub: `https://github.com/dergigi/feather.git`

With [ngit](https://ngit.dev/quickstart) installed:

```sh
git clone nostr://npub1dergggklka99wwrs92yz8wdjs952h2ux2ha2ed598ngwu9w7a6fsh9xzpc/relay.ngit.dev/feather
```

The maintainer checkout uses `origin` for Nostr and `github` for the original GitHub remote. GitHub is also advertised as an additional clone server, so pushes through `origin` update the listed Git servers.

```sh
git push origin master
git push origin --tags
ngit repo
```

The remote signer must be online for signed pushes. If a mirror misses an update, inspect `ngit repo` and run `ngit sync` to reconcile the published state. Avoid force pushes. Changes made directly on GitHub must first be fetched and reconciled locally before pushing through `origin`.

## Issues

Use the GitWorkshop link above, or the CLI from a clone:

```sh
ngit issue list
ngit issue create --subject "Short description" --body "Steps to reproduce and what happened"
ngit issue view <issue-id> --comments
```

Reports and crash details posted as issues are public. Review the contents before submitting; exclude connection secrets, private keys, and other private data. Existing GitHub issues and release assets are not migrated by mirroring the Git repository.

## Maintainer identity

The maintainer uses the same Amber identity as Zapstore publishing, with a dedicated ngit connection. The connection is stored in the local OS credential store, with the repository selecting the `feather-ngit` alias. This connection can be revoked independently of Zapstore. Credentials must never be added to tracked files.

See the upstream [mirroring guide](https://ngit.dev/repositories/mirroring) and [issue workflow](https://ngit.dev/issues).
