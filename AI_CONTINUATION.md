# Telegram X - Estado y continuación

## Estado actual
- Rama: feature/official-flows
- Código de beta.392: 9733f3336f8241074a5b7a5f2103030cdfc45f45; el commit de documentación va después sin mover el tag.
- Último release publicado: beta.392 (prerelease, arm64-v8a y x64); ver sección «Lote publicado beta.392».
- URL release: https://github.com/webr0m/Telegram-X/releases/tag/beta.392
- Fecha de trabajo: 2026-11-27
- Cambios pendientes locales no relacionados: submódulos app/jni/tgvoip/... y app/thirdparty/androidx-media/..., más gradle/wrapper/gradle-wrapper.jar. No tocarlos.

## Objetivo original
Implementar integraciones faltantes de Telegram X frente a Telegram oficial, subirlas a GitHub y generar releases. Luego se eligió Accesibilidad/TalkBack como integración grande y se avanzó en lotes pequeños, compilando, commiteando, pusheando y publicando release por cada lote.

Regla de continuación: no crear commits, pushes, tags ni releases sin autorización explícita del owner para ese lote. Un OK para un lote anterior no arrastra al siguiente.

## Integraciones grandes completadas
1. Mini Apps:
   - web_app_share_to_story
   - web_app_verify_age
   - Resize_frame
   - Commit: d6235939
   - Release: beta.309
2. App shortcuts estáticos:
   - app/src/main/res/xml/shortcuts.xml
   - manifest meta-data
   - Commit: 7889e1b9
   - Release: beta.310
3. PiP de llamadas:
   - CallActivity, CallController
   - Commits: 9978c468, 15c1d4bb
   - Releases: beta.311, beta.312

## Accesibilidad/TalkBack completada
Se realizaron 80 commits con prefijo feat(a11y). El lote beta.392 está commiteado, pusheado y publicado con ambos ABI. Patrón usado por lote:
1. Editar una o pocas vistas.
2. Compilar:
   ``powershell
   $env:JAVA_HOME="C:\Program Files\Microsoft\jdk-25.0.5.102-hotspot"
   $env:PATH="C:\Program Files\Microsoft\jdk-25.0.5.102-hotspot\\bin;C:\Program Files\Microsoft\jdk-25.0.5.102-hotspot\bin;C:\Program Files\Microsoft\jdk-21.0.13.102-hotspot\bin;C:\WINDOWS\system32;C:\WINDOWS;C:\WINDOWS\System32\Wbem;C:\WINDOWS\System32\WindowsPowerShell\v1.0\;C:\WINDOWS\System32\OpenSSH\;C:\Program Files\PuTTY\;C:\Program Files\dotnet\;C:\Program Files (x86)\cloudflared\;C:\Windows\System32\Scripts\;C:\Program Files\Git\cmd;C:\Program Files\GitHub CLI\;C:\Program Files\Calibre2\;C:\Program Files\nodejs\;C:\Program Files\Cloudflare\Cloudflare WARP\;C:\Users\ridro_nr05yut\AppData\Local\Programs\gitleaks;C:\Users\ridro_nr05yut\.cargo\bin;C:\Users\ridro_nr05yut\AppData\Local\Programs\Streamlink\bin;C:\WINDOWS\system32;C:\WINDOWS;C:\WINDOWS\System32\Wbem;C:\WINDOWS\System32\WindowsPowerShell\v1.0\;C:\WINDOWS\System32\OpenSSH\;C:\Program Files\PuTTY\;C:\Program Files\dotnet\;C:\Program Files (x86)\cloudflared\;C:\Windows\System32\Scripts\;C:\Program Files\Git\cmd;C:\Program Files\GitHub CLI\;C:\Users\ridro_nr05yut\AppData\Local\Programs\Python\Python313;C:\Users\ridro_nr05yut\AppData\Local\Microsoft\WindowsApps;C:\Users\ridro_nr05yut\instl\teldrive;C:\Users\ridro_nr05yut\.installer\bin;C:\Users\ridro_nr05yut\AppData\Local\Microsoft\WindowsApps;C:\Users\ridro_nr05yut\AppData\Local\Programs\Microsoft VS Code\bin;C:\Users\ridro_nr05yut\AppData\Local\GitHubDesktop\bin;C:\Program Files\socialstream;C:\Users\ridro_nr05yut\AppData\Local\Programs\DockerDesktop\resources\bin;C:\Users\ridro_nr05yut\AppData\Local\Programs\Ollama;C:\Users\ridro_nr05yut\AppData\Local\Microsoft\WinGet\Packages\CaddyServer.Caddy_Microsoft.Winget.Source_8wekyb3d8bbwe;C:\Users\ridro_nr05yut\.dotnet\tools;C:\Users\ridro_nr05yut\AppData\Local\Programs\Microsoft VS Code\bin\copilot-shim;C:\Users\ridro_nr05yut\AppData\Roaming\npm;C:\Users\ridro_nr05yut\AppData\Local\Programs\glab;C:\Users\ridro_nr05yut\AppData\Local\Microsoft\WinGet\Packages\aria2.aria2_Microsoft.Winget.Source_8wekyb3d8bbwe\aria2-1.37.0-win-64bit-build1;C:\Users\ridro_nr05yut\AppData\Local\Microsoft\WinGet\Packages\nilaoda.N_m3u8DL-RE_Microsoft.Winget.Source_8wekyb3d8bbwe;C:\Users\ridro_nr05yut\AppData\Local\Microsoft\WinGet\Packages\DenoLand.Deno_Microsoft.Winget.Source_8wekyb3d8bbwe;C:\Users\ridro_nr05yut\AppData\Local\Microsoft\WinGet\Packages\yt-dlp.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-N-127043-g5a54fcf75e-win64-gpl\bin;C:\Users\ridro_nr05yut\AppData\Local\Microsoft\WinGet\Packages\yt-dlp.yt-dlp_Microsoft.Winget.Source_8wekyb3d8bbwe"
   cd D:\Descargas\tgx
   .\gradlew :app:assembleLatestDebug '-Pabi=arm64' '-PbetaVersionSuffix=-beta.NNN' --offline
   ``
3. Commit y push (sólo con autorización explícita del owner para beta.NNN):
   ``powershell
   git add <archivos>
   git commit -m "feat(a11y): ..."
   git push origin feature/official-flows
   ``
4. Release prerelease:
   ``powershell
   gh release create beta.NNN "app/build/outputs/apk/latest/debug/TGX-Example-0.29.2.1834-beta.NNN-arm64-v8a-debug.apk" --repo webr0m/Telegram-X --title "beta.NNN" --notes "..." --prerelease
   ``

APK esperado:
- app/build/outputs/apk/latest/debug/TGX-Example-0.29.2.1834-beta.NNN-arm64-v8a-debug.apk

Patrones de accesibilidad usados:
- Describir vistas custom con onInitializeAccessibilityNodeInfo y info.setText(...).
- Marcar vistas puramente visuales con setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO).
- Exponer estados con info.setCheckable(true), info.setChecked(...).
- Agregar texto a botones/iconos con setContentDescription(...).
- Usar strings existentes de strings.xml cuando conviene (AccessibilityClose, AccessibilityEdit, etc.).
- Para vistas con texto propio (TextView), normalmente no hace falta tocarlas.

Archivos principales tocados:
- app/src/main/java/org/thunderdog/challegram/component/chat/...
- app/src/main/java/org/thunderdog/challegram/component/attach/...
- app/src/main/java/org/thunderdog/challegram/component/user/...
- app/src/main/java/org/thunderdog/challegram/component/base/...
- app/src/main/java/org/thunderdog/challegram/component/sticker/...
- app/src/main/java/org/thunderdog/challegram/component/inline/...
- app/src/main/java/org/thunderdog/challegram/component/passcode/...
- app/src/main/java/org/thunderdog/challegram/mediaview/...
- app/src/main/java/org/thunderdog/challegram/widget/...
- app/src/main/java/org/thunderdog/challegram/charts/...
- app/src/main/res/values/strings.xml

## Commits de accesibilidad
- 12f90430 feat(a11y): add content descriptions to send and record buttons
- 731bd283 feat(a11y): add content descriptions to attach/camera/schedule/commands/silent buttons
- aa408967 feat(a11y): add content description to call close button
- 80e2f766 feat(a11y): announce message sent via TalkBack
- e0c8c5b9 feat(a11y): expose chat titles to accessibility services
- 6472d62a feat(a11y): describe messages with sender and preview
- 92165638 feat(a11y): describe profile header actions
- 228f47aa feat(a11y): announce recording start
- 2e46f07f feat(a11y): announce call states
- 6129aa81 feat(a11y): mark avatars as decorative
- b779ffd4 feat(a11y): expose setting rows to TalkBack
- 2fec66bd feat(a11y): expose user rows to TalkBack
- 5269a6d2 feat(a11y): expose drawer items to TalkBack
- 5b1639c0 feat(a11y): expose detached chat header to TalkBack
- bd4ed981 feat(a11y): expose toggler state to TalkBack
- c43df115 feat(a11y): expose checkbox state to TalkBack
- 8a729ccd feat(a11y): expose radio state to TalkBack
- bb358ede feat(a11y): describe call control button
- 7adf1ade feat(a11y): describe forum topics to TalkBack
- ada6146c feat(a11y): describe overlay FABs
- d30f72c7 feat(a11y): describe chat floating buttons
- dc8b9c97 feat(a11y): describe emoji keyboard circle button
- bf243b01 feat(a11y): describe map controls
- 6ed71e4e feat(a11y): describe password next button
- b35288b8 feat(a11y): describe create topic button
- 9f1db31e feat(a11y): describe theme button
- 641c335a feat(a11y): describe chat folders close button
- d7af7f6c feat(a11y): describe location map button
- b8d79a06 feat(a11y): expose silent toggle to TalkBack
- 15756bb6 feat(a11y): expose chat bottom bar to TalkBack
- e051a316 feat(a11y): mark message overlay as decorative
- df01ba95 feat(a11y): mark double image as decorative
- 06fbdc3d feat(a11y): describe emoji to TalkBack
- 3eec91d7 feat(a11y): describe join requests bar to TalkBack
- ebb31267 feat(a11y): mark gif view as decorative
- 0a8045ea feat(a11y): mark wallpaper as decorative
- cbba2624 feat(a11y): expose media check circle to TalkBack
- c98e6b79 feat(a11y): describe play pause button to TalkBack
- eec34361 feat(a11y): describe edit button to TalkBack
- 1fd5bec8 feat(a11y): mark buffering progress as decorative
- e70eb61c feat(a11y): mark media other view as decorative
- 8a798c9e feat(a11y): describe pin pad buttons to TalkBack
- 9a7c3fba feat(a11y): mark location point as decorative
- df8df926 feat(a11y): mark gallery image as decorative
- de6926fe feat(a11y): mark sticker thumbnail as decorative
- ede183f4 feat(a11y): mark radial progress as decorative
- 88328483 feat(a11y): mark charts as decorative
- 79caac95 feat(a11y): mark tooltip overlay as decorative
- 333dad73 feat(a11y): mark bubble wrap as decorative
- 6aecb647 feat(a11y): include unread count in chat description
- 43b44ca8 feat(a11y): describe voice video button state to TalkBack
- 89312313 feat(a11y): mark sticker preview as decorative
- 4ea33d49 feat(a11y): describe media cell to TalkBack
- 6f71dd38 feat(a11y): describe media thumbnail to TalkBack
- f376b3c4 feat(a11y): mark media cell image as decorative
- 1aab07ea feat(a11y): mark media subsampling image as decorative
- cc02b224 feat(a11y): describe location place row to TalkBack
- da1c06ea feat(a11y): describe sender button to TalkBack
- 620cb1b1 feat(a11y): describe pinned messages bar controls
- 455ab8ff feat(a11y): describe message preview and color tone
- 20f14883 feat(a11y): cover remaining custom views
- 8aae7db0 feat(a11y): describe counters, commands anchor and progress
- ae4c061c feat(a11y): label top bar, filter values and colors
- d9b8f233 feat(a11y): describe blur options and camera cell
- d85fcf2c feat(a11y): label reply bar actions and chart decorations
- a8dde6c6 feat(a11y): mark progress spinners as decorative
- 6e034975 feat(a11y): describe check view and media bottom bar
- 8b413135 feat(a11y): mark overlay, separator and record background decorative
- b3a08c7c feat(a11y): announce voip rating value
- e6e84cc1 feat(a11y): label wallpaper blur and suggested chats
- 19fb3409 feat(a11y): label setting stupid view
- 9e66b7a2 feat(a11y): describe color palette hue
- fd284506 feat(a11y): label chat folder icons
- 657eb4a2 feat(a11y): mark more decorative views
- 6bdd57a0 feat(a11y): label drawing canvas and emoji section
- 50c078b8 feat(a11y): describe slider and decorative brightness icon
- 8991a792 feat(a11y): mark gear view decorative
- e401129a feat(a11y): mark force touch view decorative
- a4f87f9e feat(a11y): label link preview toggle
- 9733f333 feat(a11y): label search, camera and biometrics controls

## Candidatos restantes del escaneo amplio
El escaneo amplio anterior encontró 113 archivos custom con extends View/ViewGroup/... sin marcadores de accesibilidad. El lote beta.392 cubre cuatro de esos candidatos (ViewPagerPositionView, CameraBlurView, WebsiteEmptyImageView y CircleCounterBadgeView); la lista siguiente conserva los 26 restantes de la shortlist anterior y debe reejecutarse antes de darla por una medición actual:
- app\src\main\java\org\thunderdog\challegram\widget\NoScrollTextView.java (35 lines)
- app\src\main\java\org\thunderdog\challegram\ui\SingleViewAdapter.java (40 lines)
- app\src\main\java\org\thunderdog\challegram\widget\AnimatedView.java (43 lines)
- app\src\main\java\org\thunderdog\challegram\ui\MiniAppViewport.java (44 lines)
- app\src\main\java\org\thunderdog\challegram\widget\LickView.java (47 lines)
- app\src\main\java\org\thunderdog\challegram\widget\SparseDrawableView.java (48 lines)
- app\src\main\java\org\thunderdog\challegram\component\RelativeSessionLayout.java (49 lines)
- app\src\main\java\org\thunderdog\challegram\widget\SparseDrawableViewGroup.java (50 lines)
- app\src\main\java\org\thunderdog\challegram\navigation\MenuMoreWrapAbstract.java (54 lines)
- app\src\main\java\org\thunderdog\challegram\widget\CustomImageView.java (60 lines)
- app\src\main\java\org\thunderdog\challegram\data\BaseComponent.java (63 lines)
- app\src\main\java\org\thunderdog\challegram\v\MaxHeightScrollView.java (66 lines)
- app\src\main\java\org\thunderdog\challegram\data\PageBlockSimple.java (67 lines)
- app\src\main\java\org\thunderdog\challegram\navigation\ContentRelativeLayout.java (75 lines)
- app\src\main\java\org\thunderdog\challegram\texture\CustomTextureView.java (83 lines)
- app\src\main\java\org\thunderdog\challegram\component\chat\AttachLinearLayout.java (84 lines)
- app\src\main\java\org\thunderdog\challegram\widget\ImageReceiverView.java (85 lines)
- app\src\main\java\org\thunderdog\challegram\widget\EmojiEditText.java (85 lines)
- app\src\main\java\org\thunderdog\challegram\navigation\ComplexScrollView.java (86 lines)
- app\src\main\java\org\thunderdog\challegram\component\chat\MessagesLayout.java (87 lines)
- app\src\main\java\org\thunderdog\challegram\data\PageBlockFile.java (92 lines)
- app\src\main\java\org\thunderdog\challegram\ui\camera\CameraTextureView.java (92 lines)
- app\src\main\java\org\thunderdog\challegram\widget\KeyboardFrameLayoutContent.java (100 lines)
- app\src\main\java\org\thunderdog\challegram\widget\EmbeddableStickerView.java (109 lines)
- app\src\main\java\org\thunderdog\challegram\widget\SwirlView.java (117 lines)
- app\src\main\java\org\thunderdog\challegram\widget\GiftHeaderView.java (123 lines)

Notas sobre candidatos:
- Muchos son bases (SparseDrawableView, AnimatedView, NoScrollTextView) o contenedores; no conviene marcarlos globalmente.
- Algunos son puramente visuales y pueden marcarse decorativos.
- RelativeSessionLayout no recibió acción accesible personalizada porque AccessibilityAction no compila con el compileSdk actual.

## Bloqueos / decisiones
- No tocar submódulos ni gradle-wrapper.jar.
- RelativeSessionLayout: pendiente una estrategia compatible para exponer "Remove".
- No se han implementado integraciones grandes adicionales después de PiP; solo accesibilidad.

## Recomendación para continuar

1. Siguiente lote recomendado: `CameraControlButton`.
   - Añadir etiquetas localizadas para cambiar de cámara y para flash off/on/auto.
   - Actualizar la etiqueta al cambiar icono y durante el giro de cámara.
   - Etiquetar también el contenedor clicable de `RecordAudioVideoController` y marcar su hijo visual como no importante.
   - No inferir acciones sólo por icono: en `HeaderButton` un mismo dibujo puede representar acciones distintas.
2. `HeaderButton` requiere un lote aparte y mayor: mapeo ID → recurso en `HeaderView.genButton`, casos directos, subclases con estado y exclusión de controles de vista previa sin acción.
3. Mantener `RelativeSessionLayout` bloqueado hasta definir una acción compatible con el SDK actual.
4. Si se quiere otra integración grande, definir cuál (notificaciones, widgets, CarPlay/Android Auto, etc.) antes de implementar.
5. Mantener el patrón de un lote pequeño por release para no perder trazabilidad.

## Lote publicado beta.392 (2026-11-27)

- `CircleCounterBadgeView` ahora delega la descripción al `CircleButton` real y propaga `setEnabled`; la galería etiqueta su cámara y la búsqueda del chat etiqueta resultado anterior/siguiente con recursos localizados.
- Los dos accesos de desbloqueo biométrico de `PasscodeController` usan la etiqueta existente `passcode_biometrics`.
- `ViewPagerPositionView`, `CameraBlurView` y `WebsiteEmptyImageView` quedan marcados como decorativos.
- Verificación estática: 11 invariantes de accesibilidad en verde.
- Commit de producto: `9733f3336f8241074a5b7a5f2103030cdfc45f45`; empujado a `origin/feature/official-flows`.
- Release: <https://github.com/webr0m/Telegram-X/releases/tag/beta.392> (prerelease); tag y target apuntan al commit de producto, y la documentación va en un commit posterior sin mover el tag.
- Build arm64: `:app:assembleLatestDebug -Pabi=arm64 -PbetaVersionSuffix=-beta.392 --offline`, `BUILD SUCCESSFUL in 1m 6s`.
- APK arm64: `TGX-Example-0.29.2.1834-beta.392-arm64-v8a-debug.apk`; versión `0.29.2.1834-beta.392-arm64-v8a-debug`; tamaño `99.730.151` bytes; SHA-256 `d782c3928090a0bfe443047ceddd90e988313bb202bc896630135d9fc4d7f3a3`.
- Build x64: el mismo comando con `-Pabi=x86_64`, `BUILD SUCCESSFUL in 1m 8s`.
- APK x64: `TGX-Example-0.29.2.1834-beta.392-x64-debug.apk`; versión `0.29.2.1834-beta.392-x64-debug`; tamaño `106.883.274` bytes; SHA-256 `a8cee6ca2069bc3d3d42f6e575f82919564c5242f7700cabdeb5ffba074f327b`.
- Ambos APK: paquete `org.example.tgx`, `versionCode=1834000`, target SDK 37, firma V2 válida y certificado debug `6e9530ff0d13b296292dad593eb82770c5bc25147a05e6bb32a7afca36adf15f`; tamaños y digestos remotos cotejados.
- Dispositivo: no hay uno utilizable (`adb` muestra un transporte `offline`), por lo que la prueba en pantalla queda pendiente.
- Los submódulos y `gradle-wrapper.jar` siguen sin tocarse.

## Comandos de referencia
Compilar:
``powershell
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-25.0.5.102-hotspot"
$env:PATH="C:\Program Files\Microsoft\jdk-25.0.5.102-hotspot\\bin;C:\Program Files\Microsoft\jdk-25.0.5.102-hotspot\bin;C:\Program Files\Microsoft\jdk-21.0.13.102-hotspot\bin;C:\WINDOWS\system32;C:\WINDOWS;C:\WINDOWS\System32\Wbem;C:\WINDOWS\System32\WindowsPowerShell\v1.0\;C:\WINDOWS\System32\OpenSSH\;C:\Program Files\PuTTY\;C:\Program Files\dotnet\;C:\Program Files (x86)\cloudflared\;C:\Windows\System32\Scripts\;C:\Program Files\Git\cmd;C:\Program Files\GitHub CLI\;C:\Program Files\Calibre2\;C:\Program Files\nodejs\;C:\Program Files\Cloudflare\Cloudflare WARP\;C:\Users\ridro_nr05yut\AppData\Local\Programs\gitleaks;C:\Users\ridro_nr05yut\.cargo\bin;C:\Users\ridro_nr05yut\AppData\Local\Programs\Streamlink\bin;C:\WINDOWS\system32;C:\WINDOWS;C:\WINDOWS\System32\Wbem;C:\WINDOWS\System32\WindowsPowerShell\v1.0\;C:\WINDOWS\System32\OpenSSH\;C:\Program Files\PuTTY\;C:\Program Files\dotnet\;C:\Program Files (x86)\cloudflared\;C:\Windows\System32\Scripts\;C:\Program Files\Git\cmd;C:\Program Files\GitHub CLI\;C:\Users\ridro_nr05yut\AppData\Local\Programs\Python\Python313;C:\Users\ridro_nr05yut\AppData\Local\Microsoft\WindowsApps;C:\Users\ridro_nr05yut\instl\teldrive;C:\Users\ridro_nr05yut\.installer\bin;C:\Users\ridro_nr05yut\AppData\Local\Microsoft\WindowsApps;C:\Users\ridro_nr05yut\AppData\Local\Programs\Microsoft VS Code\bin;C:\Users\ridro_nr05yut\AppData\Local\GitHubDesktop\bin;C:\Program Files\socialstream;C:\Users\ridro_nr05yut\AppData\Local\Programs\DockerDesktop\resources\bin;C:\Users\ridro_nr05yut\AppData\Local\Programs\Ollama;C:\Users\ridro_nr05yut\AppData\Local\Microsoft\WinGet\Packages\CaddyServer.Caddy_Microsoft.Winget.Source_8wekyb3d8bbwe;C:\Users\ridro_nr05yut\.dotnet\tools;C:\Users\ridro_nr05yut\AppData\Local\Programs\Microsoft VS Code\bin\copilot-shim;C:\Users\ridro_nr05yut\AppData\Roaming\npm;C:\Users\ridro_nr05yut\AppData\Local\Programs\glab;C:\Users\ridro_nr05yut\AppData\Local\Microsoft\WinGet\Packages\aria2.aria2_Microsoft.Winget.Source_8wekyb3d8bbwe\aria2-1.37.0-win-64bit-build1;C:\Users\ridro_nr05yut\AppData\Local\Microsoft\WinGet\Packages\nilaoda.N_m3u8DL-RE_Microsoft.Winget.Source_8wekyb3d8bbwe;C:\Users\ridro_nr05yut\AppData\Local\Microsoft\WinGet\Packages\DenoLand.Deno_Microsoft.Winget.Source_8wekyb3d8bbwe;C:\Users\ridro_nr05yut\AppData\Local\Microsoft\WinGet\Packages\yt-dlp.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-N-127043-g5a54fcf75e-win64-gpl\bin;C:\Users\ridro_nr05yut\AppData\Local\Microsoft\WinGet\Packages\yt-dlp.yt-dlp_Microsoft.Winget.Source_8wekyb3d8bbwe"
cd D:\Descargas\tgx
.\gradlew :app:assembleLatestDebug '-Pabi=arm64' '-PbetaVersionSuffix=-beta.NNN' --offline
``

Publicar (sólo con autorización explícita del owner para beta.NNN):
``powershell
git add <archivos>
git commit -m "feat(a11y): ..."
git push origin feature/official-flows
gh release create beta.NNN "app/build/outputs/apk/latest/debug/TGX-Example-0.29.2.1834-beta.NNN-arm64-v8a-debug.apk" --repo webr0m/Telegram-X --title "beta.NNN" --notes "..." --prerelease
``
