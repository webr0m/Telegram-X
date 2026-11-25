# [Telegram X](https://play.google.com/store/apps/details?id=org.thunderdog.challegram) — a slick experimental Telegram client based on [TDLib](https://core.telegram.org/tdlib).

![Telegram X](/images/feature.png)

This is the complete source code and the build instructions for the official alternative Android client for the Telegram messenger, based on the [Telegram API](https://core.telegram.org/api) and the [MTProto](https://core.telegram.org/mtproto) secure protocol via [TDLib](https://github.com/TGX-Android/tdlib).

* [**Telegram X** on Google Play](http://play.google.com/store/apps/details?id=org.thunderdog.challegram) ([subscribe to beta](https://play.google.com/apps/testing/org.thunderdog.challegram))
* [APKs and Build Info](https://t.me/tgx_log)
* [Bot to verify APK hash](https://t.me/tgx_bot)

<details>
<summary>Other sources</summary>

* [**Telegram X** on Huawei AppGallery](https://appgallery.huawei.com/app/C101754199)
* [**GitHub Releases**](https://github.com/TGX-Android/Telegram-X/releases)

</details>

## Mini Apps work in this fork

This fork carries incremental [Mini Apps](https://core.telegram.org/bots/webapps) parity work on
[`feature/official-flows`](https://github.com/webr0m/Telegram-X/tree/feature/official-flows) on top of
[`main`](https://github.com/TGX-Android/Telegram-X), which stays identical to upstream. None of that branch
is merged upstream, and debug APKs of each step are published as
[beta releases](https://github.com/webr0m/Telegram-X/releases).

Compared to the release this fork is based on:

* **Page contract.** 63 incoming `web_app_*` events are dispatched, and every payload key the specification
  defines for them is read except `force_request` and `return_back`, which have no field in the TDLib binding
  this build uses. `error` values are the ones a page can actually switch on instead of a client-specific
  dialect. Header, background and bottom-bar colors apply without covering the page's own controls; the
  bottom bar color also drives the system navigation bar — its fill where the platform still honors one, and
  the icon contrast everywhere — and is handed back on every transition that takes the page off screen, so a
  parked Mini App cannot repaint the bar of the one in front. The bottom button honors its full field set
  (text, both colors, shine, active, visible, progress) for the main and the secondary button, along with
  back button, closing behavior, settings button and swipe behavior. A request that cannot be served — a second
  popup while one is already open, a duplicated phone-number or write-access prompt, an invoice page that fails
  to load — is answered with the matching close or failure event rather than dropped, because the SDK releases
  its one-shot request slot only inside the reply handler and a silent refusal would make every later call of
  that kind throw until the page is reloaded.
* **Device APIs.** Clipboard read, geolocation (check, request, open system settings), accelerometer,
  gyroscope and orientation lock, nine haptic sensations mapped onto the effect an Android release actually
  has, a QR scanner carrying the label a page asked for, home-screen install state, camera / microphone
  access prompts, and file downloads a page requests.
* **Secure data.** `SecureStorage` and device-storage keys (get, save, clear, restore) with biometric info,
  access request, authentication and token update driven by the system authenticator.
* **Chats and payments.** `requestChat` with a native picker, phone-number and write-access requests, emoji
  status read and set, sending a prepared message, switching an inline query, joining a chat through an
  invite link, and invoice / checkout flows with native payment providers and an account-scoped payment
  history whose receipts a resumable historical search can recover.
* **Links.** `need_absolute`, `try_instant_view`, and `try_browser` so a page can name the browser package it
  wants — falling back to the system default when that browser is not installed instead of dropping the tap.
* **Tabs.** Up to five saved Mini Apps restored with fresh authenticated sessions, and switching between live
  apps while all minimized slots are occupied without destroying the outgoing WebView.

Still open: Gifts / TON surfaces beyond checkout, Stories and Business sharing, auto-download presets, painting
the inset strip behind the gesture pill on Android 15+ edge-to-edge, and end-to-end payment flows against a
signed-in test account.

Each step is gated by offline regression harnesses rather than by a device: suites that replay each ported flow
against a mutated copy of the sources, plus auditors for the incoming and outgoing event contracts, the reachable
producers of each event, the thread each bridge site runs on, the reply every one-shot request slot is given before
a page can throw, the system navigation-bar tint, and the Mini App copy contract in `values/` and `values-es/`.
Every gate has been seen failing on a planted defect before its result was trusted.

To build the current beta:

```shell
git clone --recursive https://github.com/webr0m/Telegram-X -b feature/official-flows tgx
cd tgx
./gradlew :app:assembleLatestDebug -Pabi=arm64 "-PbetaVersionSuffix=-beta.305"
```

The beta installs as a **separate application** (`org.example.tgx`, debug-signed) and does not replace or touch
an official Telegram X install. It is not signed for Google Play, so it has to be side-loaded.

## Build instructions

### Prerequisites

* Repository must be fetched via `git`
* **JDK** or **[Android Studio](https://developer.android.com/studio/)** (with compatible bundled JDK)
* At least **8GB** of RAM
* At least **7,32GB** of free disk space when cloning with `--shallow-submodules --depth=1`
* At least **2-5x** times more disk space for files generated during build process.

#### macOS

* [Homebrew](https://brew.sh)
* git with LFS: `$ brew install git git-lfs && git lfs install`
* JDK: `$ brew install openjdk@25`
* [rustup](https://rustup.rs), then Rust of `version.rust` of `version.properties` with targets of Android: `$ scripts/setup-rust.sh`, which prints `rust.dir` for `local.properties`

#### Ubuntu

* git with LFS: `# apt install git git-lfs`
* Run `$ git lfs install` if you just installed `git-lfs`
* JDK: `# apt install openjdk-25-jdk`
* rustup: `# apt install rustup`, then Rust of `version.rust` of `version.properties` with targets of Android: `$ scripts/setup-rust.sh`, which prints `rust.dir` for `local.properties`. Run it again after a change of `version.rust`
* If multiple JDK versions are installed:<br/>`# update-java-alternatives --list`<br/>`# update-java-alternatives --set java-1.25.0-openjdk-amd64` (or other compatible version)

#### Windows

* [MSYS2](https://www.msys2.org/#installation) (**UCRT64** environment)
* Update packages: `pacman -Syu`
* Run `pacman -S --needed git mingw-w64-ucrt-x86_64-git-lfs perl make diffutils`
* Run `git lfs install`
* Run `git config --global core.longpaths true`
* [rustup](https://rustup.rs), then Rust of `version.rust` of `version.properties` with targets of Android: `scripts/setup-rust.sh`, which prints `rust.dir` for `local.properties`
* Clone the repository
* Set `msys2.dir` in `local.properties` after cloning
* Note: you have to use `\\` or `/` as path separator in properties files

### Building

1. `$ git clone --recursive https://github.com/TGX-Android/Telegram-X tgx`
2. In case you forgot the `--recursive` flag, `cd` into `tgx` directory and run: `$ git submodule update --init --recursive`
3. Open project via **[Android Studio](https://developer.android.com/studio/)** or build manually from the command line: `./gradlew assembleLatestDebug`
4. If build fails, follow the instructions provided in the error message.

#### Publishing

1. [Obtain Telegram API credentials](https://core.telegram.org/api/obtaining_api_id)
2. Create `local.properties` file in the root project folder using any text editor:<br/><pre># Location where you have Android SDK installed
   sdk.dir=YOUR_ANDROID_SDK_FOLDER
   \# Home of rustup with Rust of version.rust, printed by scripts/setup-rust.sh
   rust.dir=YOUR_RUSTUP_HOME_FOLDER
   \# Telegram API credentials obtained at previous step
   telegram.api_id=YOUR_TELEGRAM_API_ID
   telegram.api_hash=YOUR_TELEGRAM_API_HASH</pre>
3. [Setup Firebase](https://firebase.google.com/docs/android/setup) and replace `google-services.json` with the one that's suitable for your `app.id`

### Commands

<details>
<summary>Reference of commands for building and maintenance</summary>

`-Pabi` limits native libraries to given ABIs, separated by commas: `arm64`, `arm32`, `x64` or `x86`. Without it, all ABIs are built. `Latest` in task names can be replaced with another flavor.

#### Building

Set up Android SDK, NDK and `local.properties`, on Windows in MSYS2:
```shell
scripts/setup.sh
```

Check that submodules and Git LFS files are fetched:
```shell
./gradlew :app:validateGitSetup
```

Build and install debug build on connected device:
```shell
./gradlew :app:installLatestDebug -Pabi=arm64
```

Build `legacy` flavor for Android 4.x, which requires older NDK:
```shell
./gradlew :app:assembleLegacyDebug -PuseLegacyNdk=true -Pabi=arm32
```

Build libvpx, FFmpeg and tlottie for all flavors and ABIs, add `-PuseLegacyNdk=true` for `legacy` flavor:
```shell
./gradlew :app:buildLibvpx
./gradlew :app:buildFfmpeg
./gradlew :app:buildTlottie
```

Builds of tlottie for `arm64-v8a` are optimized by the profile of its drawing, `app/jni/tlottie/arm64-v8a.profdata`. After updates of tlottie or Rust, builds warn that it doesn't match, and it's made again from animated stickers and emoji on a connected device of the ABI, see [`GenerateTlottieProfileTask`](buildSrc/src/main/kotlin/tgx/gradle/task/GenerateTlottieProfileTask.kt):
```shell
ANDROID_SERIAL=<device> ./gradlew ":app:generateTlottieProfile[arm64-v8a]" -Ptlottie.samples=<samples>
```

</details>

## Reproducing public builds

In order to verify that there is no additional source code injected inside official APKs, you must use one of the following versions of **Ubuntu**:

* **21.04**: for builds published before [26th May 2023](https://github.com/TGX-Android/Telegram-X/commit/e9a054a0f469a98a13f7e0d751539687fef8759b)
* **22.04.2 LTS**: for builds published before 27th September 2025
* **24.04 LTS**: for builds published before 1st October 2026
* **26.04 LTS**: for any newer releases

And update its configuration:

1. Create user called `vk` with the home directory located at `/home/vk`
2. Clone `tgx` repository to `/home/vk/tgx`
3. Check out the specific commit you want to verify
4. In rare cases of builds that include unmerged pull requests, you must follow actions performed by [Publisher's](https://github.com/TGX-Android/Publisher/blob/main/main.js) `fetchPr` and `squashPr` tasks
5. `cd` into `tgx` folder and install dependencies: `# apt install $(cat reproducible-builds/dependencies.txt)`, then Rust with its targets of Android by `scripts/setup-rust.sh` as in the requirements above
6. Follow up the build instruction from the previous section
7. Run `$ apkanalyzer apk compare --different-only <remote-apk> <reproduced-apk>`
8. If only signature files and metadata differ, build reproduction is successful.

## Verifying side-loaded APKs

If you downloaded **Telegram X** APK from somewhere and would like to simply verify whether it's an original APK without any injected malicious source code, you need to get checksum (`SHA-256`, `SHA-1` or `MD5`) of the downloaded APK file and find whether it corresponds to any known **Telegram X** version.

In order to obtain **SHA-256** of the APK:

* `$ sha256sum <path-to-apk>` on **Ubuntu**
* `$ shasum -a 256 <path-to-apk>` on **macOS**
* `$ certutil -hashfile <path-to-apk> SHA256` on **Windows**

Once obtained, there are three ways to find out the commit for the specific checksum:

* Sending checksum to [`@tgx_bot`](https://t.me/tgx_bot)
* Searching for a checksum in [`@tgx_log`](https://t.me/tgx_log). You can do so without need in installing any Telegram client by using this URL format: [`https://t.me/s/tgx_log?q={checksum}`](https://t.me/s/tgx_log?q=c541ebb0a3ae7bb6e6bd155530f375d567b8aef1761fdd942fb5d69af62e24ae) (click to see in action). Note: unpublished builds cannot be verified this way.

## License

`Telegram X` is licensed under the terms of the GNU General Public License v3.0.

For more information, see [LICENSE](/LICENSE) file.

License of components and third-party dependencies it relies on might differ, check `LICENSE` file in the corresponding folder.

### Third-party dependencies

List of third-party components used in **Telegram X** can be found [here](/docs/THIRDPARTY.md). Additionally you can check the specific commit of the third-party component used, for example, [here](/app/jni/thirdparty) and [here](/thirdparty).

## Contributions

**Telegram X** welcomes contributions. Check out [pull request template](/docs/PULL_REQUEST_TEMPLATE.md), [code style](/docs/CODE_STYLE.md) and [guide for contributors](/docs/GUIDE.md) to learn more about Telegram X internals before creating the first pull request.

If you are a regular user and experience a problem with Telegram X, the best place to look for solution is [Telegram X chat](https://t.me/tgandroidtests) — a community with over 4 thousand members. Please do not use this repository to ask questions: if you have general issue with Telegram, refer to [FAQ](http://telegram.org/faq) or contact [Telegram Support](https://telegram.org/faq#telegram-support).
