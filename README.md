# My Bible

Your own Android Bible study app. It reads **unlocked** e-Sword modules (both the
old Access-based `.bbl/.cmt/.dct/.top/.map` files and the newer `.bblx/.bbli/.cmtx/...`
files) and **MySword** `.mybible` modules, converts each one into the app's own
format on your phone, and keeps all your notes, highlights and bookmarks locally.

It comes with the King James Version (with Strong's numbers), the Strong's Hebrew
and Greek dictionaries, and a Bible-in-a-year reading plan built in.

> Locked (password-protected) modules can't be opened. The books inside most of
> them are public domain, so look for free unlocked copies on **biblesupport.com**
> or **mysword.info** and import those.

## How to get the app onto your phone

You don't need any programming tools. GitHub builds the app for you.

1. Push this project to a repository on your GitHub account (if Claude set it up for
   you, this is already done).
2. On your repo's page, open the **Actions** tab. A build called **Build APK** runs
   automatically. Wait for the green check (about 3–4 minutes the first time).
3. Open the finished run and download **MyBible.apk** — either from the
   **Releases** page of your repo, or from the **Artifacts** box at the bottom of
   the build summary.
4. Open that `.apk` on your Android phone and tap to install. The first time, your
   phone will ask you to allow installing apps from your browser/files — say yes.

To make a change, edit the files and push again; a fresh APK builds each time.

## Adding your modules

In the app: **More → My modules → Add files** (or **Add a folder** to import a whole
copy of your e-Sword folder at once). Pick your `.bbl/.cmt/.dct/...`, `.bblx/...`,
or `.mybible` files. Each one is converted and added to your library. Locked files
are skipped with a note.

## Building on your own computer (optional)

Install Android Studio, open this folder, and press Run — or from a terminal:

```
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

## What's where

- `app/src/main/assets/www/` — the whole user interface (HTML/CSS/JS).
- `app/src/main/java/com/ryan/biblestudy/core/` — the module readers and converters
  (plain Java: Access/Jet reader, SQLite reader, RTF→HTML, format detection).
- `app/src/main/java/com/ryan/biblestudy/` — the Android app (screen, library,
  importer).
- `app/src/main/assets/modules/` — the built-in KJV and Strong's databases.

## Credits for the built-in texts

- **King James Version** — public domain. Text from the Open Bibles project; Strong's
  number placement from the public-domain "kjv" JSON dataset.
- **Strong's Hebrew and Greek Dictionaries** — James Strong (public domain); digital
  edition © Open Scriptures, licensed CC-BY-SA.
