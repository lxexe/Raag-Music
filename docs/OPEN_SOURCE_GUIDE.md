# First public GitHub push

## Export the prepared snapshot

The development history contained old screenshots, local logs and discarded branding. Deleting files in a new commit does not remove their historical copies. Use the clean export for the first public repository, rather than pushing the old Git history.

```sh
python3 scripts/public_source.py
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/export_source.py
```

Extract `dist/raag-music-source.zip` into a new folder. It contains the wrapper, source, tests, final assets, licenses, documentation and GitHub templates. It excludes `.git`, local configuration, caches, APKs, signing keys and historical logs/images. Keep the existing checkout private for recovery.

## Initialize GitHub

1. Create an **empty** GitHub repository named `raag-music`; do not initialize another README/license.
2. Work **inside the extracted `raag-music` folder**, not the original development checkout.
3. Choose the Git author identity you want public. Copy your exact GitHub `noreply` email from [GitHub email settings](https://github.com/settings/emails).
4. Replace both identity placeholders below before running the commands. These settings apply only to this extracted repository:

```sh
git init -b main
git config --local user.name "YOUR-PUBLIC-NAME"
git config --local user.email "YOUR-GITHUB-NOREPLY-EMAIL"
git var GIT_AUTHOR_IDENT
git add .
git diff --cached --stat
git diff --cached --check
git commit -m "Initial open-source release of Raag Music"
```

Check the name/email printed by `git var` before committing. The ZIP contains no commit history or author identity; your local Git settings determine the new commit author. GitHub associates that commit with the account owning its author email. Deleting a GitHub repository does not change your computer's Git identity settings.

Replace `YOUR-USERNAME` with the repository owner:

```sh
git remote add origin https://github.com/YOUR-USERNAME/raag-music.git
git push -u origin main
```

The tools do not create a remote or publish anything automatically. Do not copy the old `.git` directory into the extracted source or force-push old history over the clean repository.

After pushing, open Actions → Android checks and inspect the run on `main`. The workflow explicitly installs `platform-tools`, `platforms;android-35` and `build-tools;36.0.0`, avoiding the obsolete SDK package named `tools`. Pull requests still receive checks; branch pushes outside `main` no longer create duplicate runs. The workflow also supports manual runs from the Actions page.

## Repository settings

- Description: “Native Android music player built with Kotlin, Compose and Media3.” Add Android/music/Kotlin topics.
- Enable Issues, Actions, private vulnerability reporting and secret scanning/push protection where available.
- Protect `main`: require pull requests and the `verify` CI check; restrict force pushes.
- Use the included issue/PR templates. Publish a maintainer contact only if you intend to maintain it.
- Let CI pass before tagging. Put APKs on GitHub Releases, not in source commits.

## License and release

The original source uses GPL-3.0-or-later, consistent with NewPipeExtractor. Retain dependency/font notices and publish only material you are authorized to license. The software license does not grant rights to external media or provider brands.

Follow [release/signing instructions](RELEASING.md), including a stable signing identity and matching app/dependency source availability, before distributing production APKs.

## Audit limits

The audit checks allowlisted paths, common credential patterns, local paths, symlinks, unexpected binaries and synchronized legal files. It scans the working snapshot, not Git history, every possible secret format or all licensing/security issues. Clearly synthetic test signatures remain intentionally.

If you choose to publish old history instead, review and clean it separately. See [GitHub's history-cleanup guidance](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository). The prepared export avoids that history.
