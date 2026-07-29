# Mintgram

a privacy-focused fork (or rather, *patchset*, see below) of Telegram Android, based on [Inugram](https://github.com/teidesu/inugram)

## primary goals

this fork is intended for long-term Telegram users who want a clean, privacy-respecting experience without all the bloat.

on top of everything inherited from Inugram, you can expect:
- a dedicated Mintgram settings section
- ghost mode (no read receipts, no online/typing status)
- sponsored message (ads) removal
- deleted/edited message history

see [FEATURES.md](FEATURES.md) for a non-exhaustive list of what's added/tweaked/fixed (kept in sync as patches land).

feel free to fork this repo and remove patches you don't like or add your own, or even "steal" the features you like to your own fork.

## patchset, not a fork

unlike most alternative clients based on Telegram Android, Mintgram is a patchset.
it is not a fork in the traditional sense, but rather a collection of patches applied to the stock codebase.

a few advantages of such approach:
- easier rebase, since the stock code vs fork code is clearly separated
- easier to audit the changes, since the modifications are all in one place
- easier for bugfixes to land upstream (although i dont think they really care)

the patchset is managed using stgit and a few supporting scripts in `scripts/`.

## repo layout

- `src/kotlin`: our custom Kotlin code
- `src/res`: our custom resources
- `patches/`: stock patches
- `series`: patch apply order
- `upstream-commit`: pinned Telegram commit
- `worktree/`: local Telegram checkout, gitignored

patches are grouped by their type:

| type | description |
| --- | --- |
| `bugfix` | fixes a bug in the upstream codebase |
| `feature` | adds a contained feature (one or more, if they're related) to the app (qol, ui tweaks, etc.) |
| `debloat` | hiding stock "features" behind a toggle. you could also call it "un-feature" |
| `hooks` | small hooks into the various parts of the app to jump into our custom kotlin code for easier maintenance |
| `misc` | everything else, stuff like build support and such |

each patch strives to be small and self-contained. as a rule of thumb, we should be able to remove a patch and still have the app build, although this is not always possible.

in stgit, patch names are delimited using double underscore, e.g. `patches/misc/whatever.patch` becomes `misc__whatever` in stgit.

## contributing

contributions are welcome, but before implementing a new feature please ping me to discuss it

requirements: Node.js 20+, `git`, `stg`

```sh
pnpm install
pnpm run setup
```

this will clone the upstream into `worktree/` and set up stgit in it, along with all the current patches.
you can then simply open (not import!) `worktree/` in Android Studio and start hacking. it should build right away.

### adding a new patch

```bash
stg new misc__my-patch -m 'my patch description' # to create a patch
# ...do whatever you need in worktree/...
stg refresh # to "commit" the worktree changes into the topmost patch
pnpm run export # to export stgit into patches/
```

### modifying an existing patch

```bash
# option 1: edit the patch in-place via stg refresh
# ...do whatever you need in worktree/...
stg refresh -p misc__my-patch # --index to only append staged changes
pnpm run export

# *pretty much* same as above, but manually
# ...do whatever you need in worktree/...
stg new tmp-patch
stg refresh
stg rebase -i # move tmp1 below the patch you want to edit, and replace "edit" with "s"
pnpm run export

# option 2: push the patch to the top of the stack
stg float misc__my-patch
# ...do whatever you need in worktree/...
stg refresh
pnpm run export
```

as a rule of thumb: prefer the former, but if you get a lot of merge conflicts, try `float`-ing instead.

## acknowledgements

- [Inugram](https://github.com/teidesu/inugram) by teidesu - the direct basis for this fork
- the original [Telegram Android](https://github.com/DrKLO/Telegram) - the basis for Inugram
- a bunch of features were ported from [Nekogram](https://github.com/Nekogram/Nekogram), [NagramX](https://github.com/risin42/NagramX), [materialgram](https://github.com/kukuruzka165/materialgram)
- ghost mode / deleted & edited message history / ad removal are conceptually based on [AyuGram](https://github.com/AyuGram/AyuGram4A)
- `src/res/drawable/icplaceholder.jpg` is a blurred version of [this artwork by Chobles](https://www.pixiv.net/en/artworks/128756420)
- Tabler icons by [Tabler Team](https://tabler.io/icons)
- Solar icon pack by [480 Design](https://t.me/Design480)
- AdGuard URL Tracking filter by [AdGuard](https://adguard.com/)

this project is llm-assisted: a bunch of the code and the patches were (and will be) written by claude.

## license

abolish copyright law tbh, but let's just say the repo is licensed under MIT
