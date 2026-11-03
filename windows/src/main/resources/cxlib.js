// cxlib.js - inject into a hidden WebView that has a comix.to page loaded (after the app has started).
// Uses comix's OWN api module (it adds the ?_= token and decrypts {"e":...}), and hands back PLAIN JSON
// (normal field names), without depending on the per-build mangled names:
//  - the api method name is read from the queryFn source of a matching TanStack query in the page's cache
//    (query keys like ["manga","chapters",hid,{...}] are plain strings),
//  - the module export is resolved from apicore's own `export{local as X}` list,
//  - plain data = the raw XHR body (list endpoints answer plain JSON) or, for encrypted endpoints, the plain
//    arrays the site's mapper iterates (Array.prototype.map wrapped only during our own call).
// Tested headless (Chromium 145) on 2026-11-01 against build main-tnswp0-DCtXfAe1.js.
(() => {
  if (window.__cxlib) return;
  const lib = {
    _methods: {},   // "manga,chapters" -> {local, path}
    async api() {
      if (this._api) return this._api;
      const main = [...document.querySelectorAll('script[src]')].map(s => s.src).find(u => /\/main-[^/]+\.js$/.test(u));
      if (!main) throw new Error('no main bundle');
      const src = await (await fetch(main)).text();
      const m = src.match(/from"\.\/(apicore-[^"]+\.js)"/);
      if (!m) throw new Error('no apicore import');
      const apiUrl = main.slice(0, main.lastIndexOf('/') + 1) + m[1];
      const asrc = await (await fetch(apiUrl)).text();
      const ex = asrc.match(/export\{([^}]*)\};?\s*$/);
      if (!ex) throw new Error('no apicore export list');
      const alias = {};
      for (const p of ex[1].split(',')) { const [l, e] = p.split(' as '); alias[l.trim()] = (e || l).trim(); }
      const A = await import(apiUrl);
      return (this._api = {A, alias, build: main});
    },
    qc() {
      if (this._qc) return this._qc;
      for (const el of document.querySelectorAll('*')) {
        const k = Object.keys(el).find(k => k.startsWith('__reactContainer$') || k.startsWith('__reactFiber$'));
        if (!k) continue;
        let f = el[k];
        if (f && f.stateNode && f.stateNode.current) f = f.stateNode.current;
        while (f && f.return) f = f.return;
        const q = [f], seen = new Set();
        while (q.length) {
          const x = q.shift();
          if (!x || seen.has(x)) continue;
          seen.add(x);
          const p = x.memoizedProps;
          if (p && p.client && typeof p.client.getQueryCache === 'function') return (this._qc = p.client);
          if (x.child) q.push(x.child);
          if (x.sibling) q.push(x.sibling);
        }
        break;
      }
      return null;
    },
    // Learn the api method behind a query key prefix from a query that is in the cache now
    learn() {
      const qc = this.qc();
      if (!qc) return this._methods;
      for (const q of qc.getQueryCache().getAll()) {
        const key = q.queryKey;
        if (!Array.isArray(key) || typeof key[0] !== 'string' || typeof key[1] !== 'string') continue;
        const m = String(q.options.queryFn || '').match(/^\(\)=>([\w$]+)((?:\.[\w$]+)+)\(/);
        if (m) this._methods[key[0] + ',' + key[1]] = {local: m[1], path: m[2].slice(1).split('.')};
      }
      return this._methods;
    },
    // The app may keep learnt methods per build (lib.learn() result + (await lib.api()).build) and teach them back
    teach(methods) { Object.assign(this._methods, methods || {}); return this._methods; },
    async fn(prefix) {
      this.learn();
      const k = this._methods[prefix];
      if (!k) throw new Error('method for ' + prefix + ' not seen yet (load a page that uses it first)');
      const {A, alias} = await this.api();
      let obj = A[alias[k.local]];
      for (const p of k.path.slice(0, -1)) obj = obj[p];
      const name = k.path[k.path.length - 1];
      return (...a) => obj[name](...a);
    },
    // Run fn(...args); return raw XHR bodies of /api/ calls it made + plain arrays the mapper walked
    async capture(run) {
      const bodies = [], arrays = [];
      const M = Array.prototype.map, O = XMLHttpRequest.prototype.open, S = XMLHttpRequest.prototype.send;
      XMLHttpRequest.prototype.open = function (m, u, ...r) { this.__u = u; return O.call(this, m, u, ...r); };
      XMLHttpRequest.prototype.send = function (...a) {
        if (String(this.__u).includes('/api/')) this.addEventListener('load', () => {
          try { bodies.push({url: this.responseURL, status: this.status, text: this.responseText}); } catch (e) {}
        });
        return S.apply(this, a);
      };
      Array.prototype.map = function (...a) {
        try { if (this.length && this[0] && typeof this[0] === 'object' && !Array.isArray(this[0])) arrays.push(this); } catch (e) {}
        return M.apply(this, a);
      };
      try { await run(); } finally {
        Array.prototype.map = M; XMLHttpRequest.prototype.open = O; XMLHttpRequest.prototype.send = S;
      }
      return {bodies, arrays};
    },
    // GET /api/v1/manga?... (plain JSON) -> {status, result:{items, meta}}
    async list(params) {
      const f = await this.fn('manga,list');
      const c = await this.capture(() => f(params));
      const b = c.bodies.find(b => /\/api\/v1\/manga\?/.test(b.url));
      if (!b) throw new Error('no list response');
      const j = JSON.parse(b.text);
      if (j.e) throw new Error('list answer is encrypted now');
      return Object.assign(j, {_url: b.url});
    },
    // GET /api/v1/manga/{hid}/chapters (encrypted) -> {items:[plain chapter], url}
    async chapters(hid, params) {
      const f = await this.fn('manga,chapters');
      const c = await this.capture(() => f(hid, params));
      const items = c.arrays.find(a => 'number' in a[0] && 'url' in a[0] && 'id' in a[0]) || [];
      const b = c.bodies.find(b => b.url.includes('/chapters'));
      return {items: JSON.parse(JSON.stringify(items)), url: b && b.url, status: b && b.status};
    },
    // GET /api/v1/chapters/{id} (encrypted) -> {pages:[{width,height,url}]}
    async chapter(id) {
      const f = await this.fn('chapters,detail');
      const c = await this.capture(() => f(id));
      const pages = c.arrays.find(a => 'url' in a[0] && 'width' in a[0] && 'height' in a[0]) || [];
      const b = c.bodies.find(b => b.url.includes('/chapters/'));
      return {pages: JSON.parse(JSON.stringify(pages)), url: b && b.url, status: b && b.status};
    },
  };
  window.__cxlib = lib;
})();
