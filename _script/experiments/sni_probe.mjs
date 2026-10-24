// Feasibility probe: does direct TLS with a spoofed SNI reach blocked sites from this network?
// Also measures DNS poisoning / DoH resolution. Run with: node _script/experiments/sni_probe.mjs
import tls from 'node:tls';

const HOSTS = ['github.com', 'api.github.com', 'raw.githubusercontent.com', 'codeload.github.com', 'www.google.com', 'www.gstatic.com', 'objects.githubusercontent.com'];

async function dohResolve(host, provider) {
  const urls = {
    alidns: `https://dns.alidns.com/resolve?name=${host}&type=A`,
    tencent: `https://doh.pub/dns-query?name=${host}&type=A`,
    cloudflare: `https://cloudflare-dns.com/dns-query?name=${host}&type=A`,
  };
  const url = urls[provider];
  try {
    const r = await fetch(url, { headers: provider === 'cloudflare' ? { accept: 'application/dns-json' } : {}, signal: AbortSignal.timeout(8000) });
    const j = await r.json();
    const ips = (j.Answer || []).filter((a) => a.type === 1).map((a) => a.data);
    return ips;
  } catch (e) {
    return [`ERR:${e.cause?.code || e.message}`];
  }
}

function tlsProbe(host, servername, timeout = 12000) {
  return new Promise((resolve) => {
    const t0 = Date.now();
    let done = false;
    const finish = (o) => { if (!done) { done = true; resolve({ ...o, ms: Date.now() - t0 }); } };
    let sock;
    try {
      sock = tls.connect({ host, port: 443, servername, rejectUnauthorized: false, ALPNProtocols: ['http/1.1'] }, () => {
        const cert = sock.getPeerCertificate() || {};
        finish({ ok: true, proto: sock.getProtocol(), cn: cert.subject?.CN, san: (cert.subjectaltname || '').slice(0, 160), issuer: cert.issuer?.O });
        sock.destroy();
      });
    } catch (e) { return finish({ ok: false, err: e.code || e.message }); }
    sock.setTimeout(timeout, () => { finish({ ok: false, err: 'timeout' }); sock.destroy(); });
    sock.on('error', (e) => finish({ ok: false, err: e.code || e.message }));
  });
}

(async () => {
  console.log('== DNS resolution (DoH; direct, no local proxy) ==');
  const ipsByHost = {};
  for (const host of HOSTS) {
    const [ali, tencent] = await Promise.all([dohResolve(host, 'alidns'), dohResolve(host, 'tencent')]);
    ipsByHost[host] = ali.filter((x) => !x.startsWith('ERR'));
    console.log(`${host.padEnd(32)} alidns=${JSON.stringify(ali)} tencent=${JSON.stringify(tencent)}`);
  }
  console.log('\n== TLS probe to DoH-resolved IP, original SNI vs spoofed SNI ==');
  for (const host of HOSTS) {
    const ip = (ipsByHost[host] || [])[0];
    if (!ip) { console.log(`${host}: no IP`); continue; }
    for (const sni of [host, 'baidu.com']) {
      const r = await tlsProbe(ip, sni);
      console.log(`${host.padEnd(32)} ip=${ip.padEnd(15)} sni=${sni.padEnd(24)} => ${JSON.stringify(r)}`);
    }
  }
})();
