package com.jedon.kellikanvas.feature.collection

/** Self-contained: no third-party scripts, fonts, analytics, cookies, or credential persistence. */
data class PhonePairingOptions(
    val title: String = "DarklingNAS",
    val help: String = "Enter your NAS login here, then confirm the connection on your TV.",
    val needsEndpoint: Boolean = false,
    val needsUsername: Boolean = true,
    val endpointHint: String = "https://your-server/",
    val fixedEndpoint: String = "",
    val secretLabel: String = "NAS password",
    val usernameLabel: String = "NAS username",
)

private fun html(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

internal fun phoneNasPage(nonce: String, logoBase64: String, options: PhonePairingOptions = PhonePairingOptions()): String = """
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Connect ${html(options.title)} · KelliKanvas</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#0d0912;color:#f8f0fc;font:16px/1.5 system-ui,sans-serif}
main{max-width:480px;margin:0 auto;padding:28px 24px 40px}header{display:flex;gap:12px;align-items:center;font-size:21px;font-weight:650}
header img{width:38px;height:38px}small,.hint{color:#c8b6d3}.eyebrow{color:#e8c76b;letter-spacing:.15em;font-size:12px;margin-top:36px}
h1{font-size:30px;line-height:1.2;margin:10px 0 16px}.panel{padding:22px;background:#20152d;border:1px solid #604577;border-radius:18px;margin-top:24px}
label{display:block;font-weight:600;margin-bottom:8px}input{display:block;width:100%;border:1px solid #604577;border-radius:10px;background:#140d1e;color:#f8f0fc;font:inherit;padding:13px;margin:0 0 22px}
input:focus{outline:2px solid #e8c76b;outline-offset:2px}button{width:100%;border:0;border-radius:11px;padding:15px;font:inherit;font-weight:650;color:#261c08;background:#e8c76b;cursor:pointer}
button:disabled{opacity:.55;cursor:default}.hint{font-size:14px;margin-top:22px}#status{margin:0 0 18px}#status:empty{display:none}.error{color:#ffb4a8}a{color:#e8c76b}
</style></head><body><main>
<header><img src="data:image/png;base64,$logoBase64" alt="KelliKanvas logo">KelliKanvas</header>
<div class="eyebrow">CONNECT YOUR PHOTOS</div><h1>Connect ${html(options.title)}</h1>
<p class="hint">${html(options.help)}</p>
<div class="panel"><p id="status" role="status" aria-live="polite"></p>
<form id="login" autocomplete="off">
${if (!options.needsEndpoint) {
    ""
} else if (options.fixedEndpoint.isNotEmpty()) {
    "<input name=\"endpoint\" type=\"hidden\" value=\"${html(options.fixedEndpoint)}\">"
} else {
    "<label for=\"endpoint\">Server or WebDAV URL</label><input id=\"endpoint\" name=\"endpoint\" type=\"url\" required maxlength=\"2048\" placeholder=\"${html(options.endpointHint)}\" autocomplete=\"off\" autocapitalize=\"none\" spellcheck=\"false\">"
}}
${if (options.needsUsername) "<label for=\"username\">${html(options.usernameLabel)}</label><input id=\"username\" name=\"username\" required maxlength=\"128\" autocomplete=\"off\" autocapitalize=\"none\" spellcheck=\"false\">" else "<input id=\"username\" name=\"username\" type=\"hidden\" value=\"\">"}
<label for="password">${html(options.secretLabel)}</label><input id="password" name="password" type="password" required maxlength="${if (options.needsEndpoint) 4096 else 256}" autocomplete="off">
<button id="send" type="submit">Send to my TV</button></form></div>
<p class="hint">Use the same trusted home Wi-Fi as your TV. This temporary local page uses HTTP and expires after 10 minutes. Your TV stores the password in its encrypted credential store after connecting.</p>
</main><script nonce="$nonce">
const token=location.hash.slice(1);history.replaceState(null,'',location.pathname);
const form=document.getElementById('login'),status=document.getElementById('status'),send=document.getElementById('send');
if(!/^[a-f0-9]{64}${'$'}/.test(token)){form.hidden=true;status.textContent='Scan the QR code on your TV to open a new pairing link.';}
form.addEventListener('submit',async function(event){
event.preventDefault();if(!form.reportValidity())return;send.disabled=true;status.className='';status.textContent='Sending to your TV…';
try{const body=new URLSearchParams(new FormData(form));const response=await fetch('/credentials',{method:'POST',headers:{'X-KelliKanvas-Pairing':token},body:body,credentials:'omit',cache:'no-store'});
if(!response.ok){status.className='error';status.textContent=response.status===410?'This link expired. Start again on your TV.':'Could not send the login. Check the form or scan a new QR code.';send.disabled=false;return;}
form.reset();form.hidden=true;status.textContent='${if (options.needsEndpoint) "Login sent. Confirm the connection on your TV. You can close this page." else "Login sent. Select Connect NAS on your TV. You can close this page."}';
}catch(error){status.className='error';status.textContent='Your TV could not be reached. Keep this setup screen open on the TV and use the same Wi-Fi.';send.disabled=false;}
});
</script></body></html>
""".trimIndent()
