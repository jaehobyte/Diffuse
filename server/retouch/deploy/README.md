# Deploying the skin retouch server

Two systemd **user** units on the GPU host:

| Unit | Listens | What |
|---|---|---|
| `retouch-server.service` | `127.0.0.1:8084` (loopback only) | `python -m app`, real engines, one process |
| `retouch-public.service` | `:8094` | Caddy HTTP test proxy → `127.0.0.1:8084`, body ≤ 90 MiB, read/write 65 s |

The proxy runs with `admin off` (no admin listener, so it cannot collide with MonetGPT's Caddy
admin on `127.0.0.1:20203`) and `persist_config off` (it does not overwrite
`~/.config/caddy/autosave.json`). With the admin endpoint off, `caddy reload` does not work:
restart the unit instead.

> **The public route is plain HTTP.** The bearer token, face ROIs and masks cross the network
> unencrypted. Use it only for test photos that are allowed to be sent that way, and move to HTTPS
> (below) before real use. The backend port 8084 and ADB ports are never opened publicly; only
> 8094 is, and only after the host firewall / cloud ingress change is made deliberately.

Check before installing that the ports are free: `ss -ltn | grep -E ':(8084|8094) '`.

## 1. Environment file

```bash
cd ~/vibe_editor_260905/server/retouch
cp deploy/retouch-server.env.example deploy/retouch-server.env
chmod 600 deploy/retouch-server.env
python3 -c "import secrets; print(secrets.token_urlsafe(32))"   # paste into RETOUCH_AUTH_TOKEN
```

`deploy/*.env` is git-ignored. Never commit it, paste it into logs or tickets, or reuse the
MonetGPT / SAM 3 token. The app receives the token through its runtime server settings.

Model files must be in `RETOUCH_MODEL_DIR` (`acne_640_fp32.onnx`, `acne_640_fp32.manifest.json`,
`migan_pipeline_v2.onnx`); see `scripts/retouch/README.md` for how they are obtained.

## 2. Install and start

```bash
mkdir -p ~/.config/systemd/user
ln -sf ~/vibe_editor_260905/server/retouch/deploy/retouch-server.service ~/.config/systemd/user/
ln -sf ~/vibe_editor_260905/server/retouch/deploy/retouch-public.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now retouch-server.service
# wait for readiness (503 loading → 200 ready)
curl -s -H "Authorization: Bearer $(sed -n 's/^RETOUCH_AUTH_TOKEN=//p' deploy/retouch-server.env)" \
  http://127.0.0.1:8084/health
systemctl --user enable --now retouch-public.service
```

## 3. Keep running after logout and start at boot

User units stop at logout and do not start at boot unless lingering is enabled:

```bash
loginctl show-user "$USER" --property=Linger     # Linger=yes is required
sudo loginctl enable-linger "$USER"              # if it says no (needs admin rights)
systemctl --user is-enabled retouch-server.service retouch-public.service   # enabled
```

A restart test (`systemctl --user restart ...`) is not the same evidence as a boot test; record
them separately.

## 4. Operate

```bash
systemctl --user status retouch-server.service retouch-public.service --no-pager
systemctl --user restart retouch-server.service      # reloads models; health is 503 until warm
systemctl --user restart retouch-public.service      # apply Caddyfile changes (no admin reload)
systemctl --user stop retouch-public.service retouch-server.service
journalctl --user -u retouch-server.service -n 100 --no-pager   # request_id/kind/status/ms only
tail -n 20 ~/vibe_editor_260905/server/retouch/deploy/public-access.log  # Authorization is REDACTED
```

Recovery:

| Symptom | Check | Fix |
|---|---|---|
| health `503 loading` for long | `journalctl --user -u retouch-server` | wait for `engine … ready`; CUDA init + digest checks take seconds |
| health `503 failed` | journal line `engine <kind> failed to load: …` | restore the model file / digest, or remove that kind from `RETOUCH_ENABLED_KINDS` / `RETOUCH_EVALUATION_KINDS`; then restart |
| `CUDA execution provider unavailable` warning | `nvidia-smi` | the server runs on CPU (slower); fix the driver/GPU memory, restart |
| unit in `failed` state | `systemctl --user status` | `systemctl --user reset-failed retouch-server.service && systemctl --user start retouch-server.service` |
| refuses to start: `RETOUCH_AUTH_TOKEN is empty` | env file | set the token, `chmod 600`, restart |
| 413 at the proxy | request size | the client exceeded 90 MiB; the backend has the same limit |
| 429 bursts | queue | 1 running + 4 waiting requests (counted from before the body is read) by design; clients retry explicitly after `Retry-After` |

Smoke after any change (never prints the token):

```bash
server/retouch/.venv/bin/python server/retouch/scripts/smoke_client.py \
  --base-url http://127.0.0.1:8084 --manifest <case manifest> --all-kinds --negative-auth \
  --token-file <mode-600 file containing only the token>
```

Then the same against the public URL from outside the host.

## 5. Uninstall

```bash
systemctl --user disable --now retouch-public.service retouch-server.service
rm ~/.config/systemd/user/retouch-server.service ~/.config/systemd/user/retouch-public.service
systemctl --user daemon-reload
```

Remove the 8094 firewall / ingress rule that was added for the public route.

## 6. HTTPS migration

When a DNS name pointing at the host is available:

1. Open 80 and 443 (or only 443 with the DNS challenge) in the host firewall / cloud ingress;
   close 8094 afterwards.
2. Replace the site block with the hostname and let Caddy obtain certificates (remove
   `auto_https off`; keep `admin off` and `persist_config off`, or give the admin endpoint a
   dedicated address that is not `127.0.0.1:20203`):

   ```caddyfile
   retouch.example.com {
       request_body {
           max_size 90MiB
       }
       reverse_proxy 127.0.0.1:8084 {
           transport http {
               dial_timeout 10s
               read_timeout 65s
               write_timeout 65s
           }
       }
       handle_errors 413 {
           header Content-Type application/json
           respond `{"error":"too_large"}` 413
       }
       log {
           output file /home/jaeho/vibe_editor_260905/server/retouch/deploy/public-access.log
       }
   }
   ```

3. `caddy validate --config <file> --adapter caddyfile`, restart `retouch-public.service`, run the
   smoke against `https://retouch.example.com`, then change the app's server URL.
4. Rotate the token: it has travelled over plain HTTP during testing.
