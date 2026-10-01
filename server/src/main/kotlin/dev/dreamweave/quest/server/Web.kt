// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.server

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.datetime.Instant
import kotlinx.datetime.toLocalDateTime
import java.net.URLEncoder

/** Server-rendered admin pages: small, dependency-free HTML in the app's visual language. */
object Web {
    fun esc(s: String?): String = (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    fun qrSvg(text: String, size: Int = 220): String {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
        val sb = StringBuilder("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${m.width} ${m.height}" width="$size" height="$size" shape-rendering="crispEdges" role="img" aria-label="Pairing QR code"><rect width="100%" height="100%" fill="#fff"/><path fill="#12201B" d="""")
        for (y in 0 until m.height) for (x in 0 until m.width) if (m.get(x, y)) sb.append("M$x ${y}h1v1h-1z")
        sb.append("\"/></svg>")
        return sb.toString()
    }

    fun time(ms: Long?, config: Config): String = ms?.let {
        val dt = Instant.fromEpochMilliseconds(it).toLocalDateTime(config.timeZone)
        "${dt.date} ${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
    } ?: "never"

    fun page(config: Config, title: String, body: String): String = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)} · ${esc(config.appName)}</title>
<style>
:root{--bg:#F3F6F1;--surface:#FFFFFF;--alt:#EEF3EF;--border:#E1E8E2;--text:#12201B;--muted:#56675F;--lime:#C8F135;--violet:#5B4BFF;--tint:#EEEBFF}
@media (prefers-color-scheme:dark){:root{--bg:#0F1513;--surface:#18201D;--alt:#212B27;--border:#2C3833;--text:#EAF2EC;--muted:#A3B3AB;--violet:#8C80FF;--tint:#252046}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:720px;margin:0 auto;padding:24px 16px 64px}h1{font-size:28px;margin:8px 0 16px}h2{font-size:18px;margin:0 0 8px}
.card{background:var(--surface);border:1px solid var(--border);border-radius:16px;padding:16px;margin:0 0 16px}
.meta{font:500 12px/1.4 ui-monospace,Menlo,Consolas,monospace;letter-spacing:.06em;text-transform:uppercase;color:var(--violet)}
.muted{color:var(--muted)}code,.mono{font-family:ui-monospace,Menlo,Consolas,monospace}code{background:var(--alt);padding:2px 6px;border-radius:6px;word-break:break-all}
button,.btn{min-height:48px;padding:10px 18px;border-radius:14px;border:1px solid var(--border);background:var(--surface);color:var(--text);font:600 15px system-ui;cursor:pointer}
button.primary{background:var(--lime);color:#12201B;border-color:var(--lime)}input{min-height:48px;width:100%;padding:10px 12px;border-radius:12px;border:1px solid var(--border);background:var(--surface);color:var(--text);font-size:16px}
table{width:100%;border-collapse:collapse;font-size:14px}td,th{padding:8px 4px;border-bottom:1px solid var(--border);text-align:left;vertical-align:top}
.code{font:600 32px ui-monospace,Menlo,monospace;letter-spacing:.1em}.row{display:flex;gap:12px;flex-wrap:wrap;align-items:center}
form.inline{display:inline}.ok{color:var(--violet)}.err{color:var(--muted)}
</style></head><body><main>$body</main></body></html>"""

    fun login(config: Config, error: String? = null, next: String = "/"): String = page(
        config, "Sign in",
        """<div class="meta">${esc(config.appName)} server</div><h1>Sign in</h1>
<div class="card"><form method="post" action="/login"><input type="hidden" name="next" value="${esc(next)}">
<label for="pw">Admin password</label><input id="pw" name="password" type="password" autocomplete="current-password" autofocus required>
${error?.let { "<p class=\"err\">${esc(it)}</p>" } ?: ""}<p><button class="primary" type="submit">Sign in</button></p></form></div>""",
    )

    fun pairing(config: Config, code: String, expiresAt: Long): String {
        val link = "dwquest://pair?server=${URLEncoder.encode(config.publicUrl, "UTF-8")}&code=${URLEncoder.encode(code, "UTF-8")}"
        return page(
            config, "Pair a device",
            """<div class="meta">Pair a device</div><h1>Scan with your phone</h1>
<div class="card"><div class="row"><div>${qrSvg(link)}</div><div><p class="muted">Or in the app: Settings → Sync → Pair, then enter</p>
<p class="code">${esc(code)}</p><p class="muted">Server: <code>${esc(config.publicUrl)}</code><br>Valid until ${esc(time(expiresAt, config))}, once.</p></div></div></div>
<p><a class="btn" href="/">Done</a></p>""",
        )
    }
}
