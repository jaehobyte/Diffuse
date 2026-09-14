# Device / server validation — 2026-09-14

## Status

PARTIAL. EC2-side checks completed below; phone end-to-end verification is blocked on the local ADB tunnel. This report does not replace the existing implementation review or approve the full task.

## Device connection

- `adb devices -l`: empty device list.
- `adb -H 127.0.0.1 -P 5038 devices -l`: connection refused.
- Installed APK version, saved server overrides, phone UI, preview, Apply, Undo and export: not verified.
- Local PC must expose its existing ADB server through SSH; USB debugging must be authorized on the phone.

Run on the local PC, replacing the SSH destination and adding the usual identity options if needed:

```bash
adb start-server
adb devices -l
ssh -N -o ExitOnForwardFailure=yes -R 5038:127.0.0.1:5037 -L 18082:127.0.0.1:8082 <EC2-SSH-destination>
```

Once connected, EC2 can inspect `adb -H 127.0.0.1 -P 5038 devices -l`. For the selected device, `adb -H 127.0.0.1 -P 5038 -s <serial> reverse tcp:8082 tcp:18082` will route phone port 8082 to the local PC's SSH forward, which terminates at EC2 port 8082. This reverse command has NOT been executed. A reverse on the remote ADB server alone would target the local PC, not EC2. This is a development-only route and does not establish a public download-app connection.

## Feature status from current source

- Auto: `AiModule.kt` binds `MonetAutoEnhanceProvider`; actual inference uses MonetGPT. The configured `.env` URL is `http://127.0.0.1:8082`, which is the phone itself when compiled into a local-credentials APK. Existing saved overrides can differ; device inspection remains necessary.
- Skin: `SkinRetouchSheet.kt` has `applyEnabled = false`, marks all four kinds unsupported, and shows the preparation message. `AiModule.kt` has no production `SkinRetouchProvider` binding. Connecting a phone or server cannot activate this unimplemented feature.

## Executed validation

- Authenticated EC2 `GET /health`: HTTP 200, `{"status":"ready","model":"monetgpt"}` using configured app credentials. Tokens were not printed.
- Missing and deliberately invalid token: HTTP 401 for each.
- Three-style actual inference: all passed (process exit 0), using the existing server smoke_client.py and repository fixtures/photo_512.png. Balanced: 71.0 s / 7 operations; Vibrant: 71.1 s / 7 operations; Retro: 73.4 s / 11 operations. All returned HTTP 200 and string content with known operations and finite values within -100..100; app-compatible parsing succeeded within the 120 s timeout. This validates execution from EC2, not photographic quality or the phone network route.
- Final ADB tunnel recheck: 127.0.0.1:5038 still refused the connection.
- Targeted Gradle command: BUILD SUCCESSFUL; `core:ai` tests FROM-CACHE, editor tests executed. Skin tests assert the disabled placeholder, not successful correction.

```bash
./gradlew :core:ai:testDebugUnitTest --tests 'com.diffuse.core.ai.monet.*' :feature:editor:testDebugUnitTest --tests 'com.diffuse.feature.editor.tools.auto.*' --tests 'com.diffuse.feature.editor.tools.retouch.*'
```

- `git diff --check`: exit 0 before report creation.
- No implementation changes, APK installation, app data clearing, service restarts or public listener changes performed.


## Follow-up: ADB port 15038

The initial disconnected observations above are historical. User opened port 15038.

- ADB now recognizes one authorized SM_S948U running Android 16, state device.
- Before installation, `pm list packages diffuse` was empty and `dumpsys package com.diffuse` returned package not found.
- Reverse list was empty; added `reverse --no-rebind tcp:8082 tcp:18082` successfully. The destination is the local PC running the remote ADB server; that PC needs SSH `-L 18082:127.0.0.1:8082` to EC2.
- Phone-side `curl -sS -i --max-time 10 http://127.0.0.1:8082/health` repeatedly returned exit 52 (Empty reply from server). No HTTP response reached the phone. This does not distinguish an absent local SSH forward from a misconfigured destination.
- Built `./gradlew :app:assembleDebug -Pdiffuse.localCreds`: BUILD SUCCESSFUL, version 0.6.0-local / code 12. Private test APK, not for public distribution.
- Installation completed with Success. Launched com.diffuse/.MainActivity successfully. Pushed repository fixture to /sdcard/Pictures/diffuse_validation_20260914.png and requested media scan.
- Phone initially locked; later policy check confirmed showing=false, inputRestricted=false, awake.
- Phone UI verified: AI -> 자동 opens 서버 설정 and shows the connection-failure message, also reported by the user. Phone inference, preview, Apply, Undo and export remain unverified because the HTTP route fails. Current source skin correction remains unimplemented and disabled; phone skin completion is not claimed.


## Follow-up: external address authorized and configured

User explicitly requested SAM-style external access instead of a local SSH
forward. Created and validated monetGPT/deploy/Caddyfile.test8093 and
monetgpt-public.service, installed/enabled the user unit, and added only TCP
8093 ingress rule sgr-09e570ae56a9d0be7 to the current EC2 security group.
The dedicated proxy forwards to existing 127.0.0.1:8082, uses admin port 20203,
and preserves auth/limits. SAM3 and the existing model process were untouched.

External base URL: http://44.233.156.159:8093. EC2 requests through this public
address returned 200 ready with the configured token and 401 with a wrong token.
Only MONET_BASE_URL in the private app .env was updated; token and SAM3 settings
were preserved. Local-credentials APK rebuild succeeded; the updated APK is not yet installed on the phone.

Phone verification is currently interrupted: after the public proxy was started,
the 15038 ADB tunnel stopped answering even devices -l. No successful phone
public health/inference response or app URL update is claimed yet. Prior phone
connection errors above concerned the old localhost/SSH route, not this public
route. Public-route Balanced inference passed in 72.2 seconds with 7 valid operations. Dedicated proxy restart also passed: service active and public health 200. Final ADB probe returned connection refused; device URL update and public phone inference remain unverified.

The proxy is a user service with restart-on-failure; Linger=no and the preexisting
MonetGPT process is not a system service, so reboot/logout persistence is not
verified. HTTP is the explicit SAM-style test deployment, without TLS encryption.

Detailed server deployment and rollback: /home/jaeho/monetGPT/work/PUBLIC_ACCESS_2026-09-14.md. Both repositories passed git diff --check after the deployment changes.


## Reinstallation on restored 15038 connection

User explicitly requested reinstall on the reconnected 15038 device. Confirmed
original USB serial R3CYA0AVX2E / SM_S948U, rebuilt the private local-credentials
APK successfully, and verified generated MONET_BASE_URL is
http://44.233.156.159:8093. `adb install -r` completed with Success; no uninstall
or data clear was performed. APK metadata: 0.6.0-local / version code 12.
Monet shared preferences had no saved base_url or token override before update.

Network diagnosis on THIS phone:
- Direct unauthenticated curl to public 8093 /health timed out after 5 seconds.
- Temporarily exposed the same authenticated proxy on already-open, previously
  unused HTTP port 80; phone curl also timed out after 5 seconds. Removed this
  ineffective diagnostic listener afterward; final proxy remains 8093 only.
- General HTTPS HEAD request to https://www.google.com also timed out after
  5 seconds, showing failure is not specific to the MonetGPT port.
- Android reports the active Wi-Fi as PARTIAL_CONNECTIVITY, accepts an
  unvalidated/partially connected network, and does not mark it VALIDATED.
  Airplane mode is off and no global HTTP proxy is configured.
- These observations establish a phone-network reachability problem; they do
  not prove whether its cause is Wi-Fi access policy, authentication, routing,
  firewall, or another network condition. App inference remains unverified on
  this device. A network with working internet access is needed for follow-up.

The earlier successful unauthenticated phone public health (401) was from a
different SM_S948U on the 15037 connection, serial ending AW11R; it must not be
attributed to this target device.


## Direct device verification and Wi-Fi recovery — 2026-09-14

Target: original SM_S948U, serial R3CYA0AVX2E, through restored ADB 15038.
Installed version confirmed 0.6.0-local / code 12. Opened the existing project,
navigated AI -> 자동, and reproduced the reported failure directly.

Evidence before recovery:
- Settings UI showed http://44.233.156.159:8093 in 자동 보정 서버 주소.
- No stale Monet URL/token override before re-save. Compared the effective
  device token with the configured credential without printing it: match.
- Same credentials from EC2 returned 200 ready.
- Actual button tap produced MonetClient GET /health unreachable with
  InterruptedIOException timeout while connecting the socket (device log
  14:50:31.966 KST); saving the same settings did not fix it.
- Phone curl to public /health and general Google HTTPS both timed out.
- Active network was WIFI with PARTIAL_CONNECTIVITY, not VALIDATED.

Recovery performed:
- Disabled and re-enabled existing Wi-Fi via svc wifi, preserving saved networks
  and USB ADB. No new Wi-Fi credentials, mobile data, VPN or proxy configured.
- After reconnection the active Wi-Fi became VALIDATED and no longer partial.
- Phone unauthenticated public health immediately returned expected 401.
- Tapped 자동 to recheck: app displayed 자동 보정 서버에 연결됐어요.
- Executed automatic correction from the phone. Caddy recorded okhttp POST
  /v1/chat/completions HTTP 200 at 05:57:16Z (86.19 s), 05:58:55Z (85.84 s),
  and 06:03:09Z (85.42 s). These are actual phone requests through the external
  address; no ADB reverse/SSH HTTP forwarding is involved.
- UI displayed automatic correction result, model explanation and intensity 100.
  An early observation of a remaining busy overlay was followed by an additional
  completed request; a controlled repeat completed with no busy overlay and
  Apply enabled. No persistent loading defect is asserted from that observation.
- Pressed Apply: sheet closed and Undo enabled. Exercised Undo/Redo and left the
  editor at the pre-final-application state (Undo disabled, Redo available).
  No export or data clear was performed; original files were not overwritten.

Conclusion: the reported connection failure was reproduced and recovered by
reconnecting this phone's partially connected Wi-Fi. Address/token changes and
another app reinstall were not required. The underlying cause of the Wi-Fi
partial state is not established, and recurrence cannot be ruled out.
Automatic server invocation, result UI, Apply and history controls were directly
verified. This does not complete all three-style/quality/export acceptance
criteria or implement the still-disabled skin correction feature.
