package com.maluca.challenge;

/**
 * Self-contained challenge HTML. The PoW page runs SHA-256 hashcash with
 * SubtleCrypto (requires a secure context — https or localhost); the JS-lite
 * page just proves JS execution by echoing the signed token back. Both POST
 * to /_maluca/challenge/verify and reload on success.
 */
final class ChallengePages {

    private ChallengePages() {
    }

    static String pow(String token, int difficultyBits) {
        return """
                <!doctype html>
                <html><head><title>Checking your browser…</title>
                <meta name="robots" content="noindex">
                <style>
                  body{font-family:system-ui,sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#0f1318;color:#e6e8eb}
                  .card{max-width:420px;text-align:center;padding:2rem}
                  .spin{width:36px;height:36px;border:3px solid #2d3640;border-top-color:#4da3ff;border-radius:50%%;animation:r 1s linear infinite;margin:0 auto 1rem}
                  @keyframes r{to{transform:rotate(360deg)}}
                  small{color:#8a93a0}
                </style></head>
                <body><div class="card">
                  <div class="spin"></div>
                  <h2>Checking your browser</h2>
                  <p>This takes a moment and happens only once.</p>
                  <small id="st">solving…</small>
                </div>
                <script>
                const TOKEN = %s, BITS = %d;
                function lz(buf){const v=new Uint8Array(buf);let n=0;for(const b of v){if(b===0){n+=8;continue}n+=Math.clz32(b)-24;break}return n}
                async function solve(){
                  const enc=new TextEncoder();
                  for(let nonce=0;;nonce++){
                    const d=await crypto.subtle.digest('SHA-256',enc.encode(TOKEN+':'+nonce));
                    if(lz(d)>=BITS)return String(nonce);
                    if(nonce%%2000===0)document.getElementById('st').textContent='solving… '+nonce;
                  }
                }
                (async()=>{
                  try{
                    const nonce=await solve();
                    const r=await fetch('/_maluca/challenge/verify',{method:'POST',headers:{'Content-Type':'application/json'},
                      body:JSON.stringify({token:TOKEN,nonce:nonce})});
                    if(r.ok){location.reload()}else{document.getElementById('st').textContent='verification failed'}
                  }catch(e){document.getElementById('st').textContent='error: '+e}
                })();
                </script></body></html>
                """.formatted(jsString(token), difficultyBits);
    }

    static String jsLite(String token) {
        return """
                <!doctype html>
                <html><head><title>One moment…</title>
                <meta name="robots" content="noindex"></head>
                <body>
                <noscript>This site requires JavaScript.</noscript>
                <script>
                fetch('/_maluca/challenge/verify',{method:'POST',headers:{'Content-Type':'application/json'},
                  body:JSON.stringify({token:%s,nonce:''})})
                  .then(r=>{if(r.ok)location.reload()});
                </script></body></html>
                """.formatted(jsString(token));
    }

    private static String jsString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
